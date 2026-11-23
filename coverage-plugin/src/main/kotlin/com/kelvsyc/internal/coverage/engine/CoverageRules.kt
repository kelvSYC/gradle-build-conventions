package com.kelvsyc.internal.coverage.engine

private const val DEFAULT_BRANCH_FLOOR = 0.75
private const val DEFAULT_MINIMUM_BRANCHES = 4

/**
 * Thresholds for the changed-code rules: the branch floor applies once the changed lines hold at least
 * [minimumBranches] branches.
 */
data class RuleSettings(
    val branchFloor: Double = DEFAULT_BRANCH_FLOOR,
    val minimumBranches: Int = DEFAULT_MINIMUM_BRANCHES,
)

/**
 * Repository-relative source directories: [measured] are compiled for the JVM, [notMeasured] are not (JS or native only).
 */
data class SourceRoots(val measured: List<String>, val notMeasured: List<String>)

/**
 * The outcome of [evaluate].
 *
 * [notMeasurable] holds `file:line` (or `file:first-last`) entries and `file (inline name)` entries.
 * [filesWithoutCoverageData] lists changed files that declare code but have no entry in the report, which means the
 * report is stale or missing; [noExecutionData] means no test executed the project's code at all.
 * [changedMeasuredLines] counts changed lines under measured roots, and [repoPaths] maps report keys to
 * repository-relative paths for display.
 */
data class CoverageResult(
    val uncoveredPublicApi: List<Declaration>,
    val branchesCovered: Int,
    val branchesTotal: Int,
    val branchFloorMet: Boolean,
    val linesCovered: Int,
    val linesTotal: Int,
    val notMeasurable: List<String>,
    val exemptions: List<Exemption>,
    val invalidMarkers: List<InvalidMarker>,
    val filesWithoutCoverageData: List<String> = emptyList(),
    val changedMeasuredLines: Int = 0,
    val repoPaths: Map<String, String> = emptyMap(),
    val noExecutionData: Boolean = false,
) {
    /**
     * True when no changed public API is uncovered, the branch floor is met, no marker is invalid and every changed
     * file that declares code has coverage data.
     */
    val passed: Boolean
        get() = uncoveredPublicApi.isEmpty() && branchFloorMet && invalidMarkers.isEmpty() &&
            filesWithoutCoverageData.isEmpty()
}

private sealed interface Location {
    data class Measured(val key: String) : Location
    data object NotMeasured : Location
}

/**
 * Locates [path] under the roots. A measured file is keyed by [sourceKeys] (see [reportKey]), falling back to its path
 * below the root for files whose text is unknown.
 */
private fun SourceRoots.locate(path: String, sourceKeys: Map<String, String>): Location? {
    val measuredRoot = measured.filter { path.startsWith("$it/") }.maxByOrNull { it.length }
    val otherRoot = notMeasured.filter { path.startsWith("$it/") }.maxByOrNull { it.length }
    return when {
        measuredRoot != null && (otherRoot == null || measuredRoot.length >= otherRoot.length) ->
            Location.Measured(sourceKeys[path] ?: path.removePrefix("$measuredRoot/"))
        otherRoot != null -> Location.NotMeasured
        else -> null
    }
}

/**
 * Applies the changed-code rules: every changed public declaration is covered, branches on changed lines meet the
 * floor, and no exemption marker lacks a reason. Inline declarations and non-JVM sources are reported as not
 * measurable rather than judged.
 *
 * [sourceKeys] maps changed files' repository paths to their report keys ([reportKey]), so that a file whose directory
 * does not mirror its package still matches its classes. [hasExecutionData] is false when no test produced execution
 * data for the project.
 */
fun evaluate(
    changed: ChangedLines,
    report: JacocoXmlReport,
    declarations: List<Declaration>,
    exemptions: List<Exemption>,
    invalid: List<InvalidMarker>,
    roots: SourceRoots,
    settings: RuleSettings,
    sourceKeys: Map<String, String> = emptyMap(),
    hasExecutionData: Boolean = true,
): CoverageResult {
    val measuredChanged = linkedMapOf<String, Pair<String, Set<Int>>>()
    val notMeasurable = mutableListOf<String>()
    for ((path, lines) in changed.byFile.toSortedMap()) {
        when (val location = roots.locate(path, sourceKeys)) {
            is Location.Measured -> measuredChanged[location.key] = path to lines
            Location.NotMeasured -> notMeasurable.addAll(lineRanges(lines).map { "$path:$it" })
            null -> Unit
        }
    }

    val declarationsByKey = declarations.groupBy { it.sourceFile }
    val covered = report.methods.filter { it.covered }.map { Triple(it.className, it.name, it.desc) }.toSet()

    val exemptedLines = mutableMapOf<String, MutableSet<Int>>()
    val exemptedDeclarations = mutableSetOf<Declaration>()
    val affectingExemptions = mutableListOf<Exemption>()
    for (exemption in exemptions) {
        val key = (roots.locate(exemption.file, sourceKeys) as? Location.Measured)?.key ?: continue
        val (lines, targets) = exempted(exemption.target, declarationsByKey[key].orEmpty())
        exemptedLines.getOrPut(key) { mutableSetOf() }.addAll(lines)
        exemptedDeclarations.addAll(targets)
        val changedLines = measuredChanged[key]?.second.orEmpty()
        val touched = lines.any { it in changedLines } || targets.any { t -> t.lines.any { it in changedLines } }
        if (touched) affectingExemptions.add(exemption)
    }

    val uncovered = mutableListOf<Declaration>()
    val withoutData = mutableListOf<String>()
    val repoPaths = mutableMapOf<String, String>()
    var changedMeasuredLines = 0
    var branchesCovered = 0
    var branchesTotal = 0
    var linesCovered = 0
    var linesTotal = 0
    for ((key, entry) in measuredChanged) {
        val (path, changedLines) = entry
        repoPaths[key] = path
        changedMeasuredLines += changedLines.size
        if (key !in report.lines && declarationsByKey[key].orEmpty().isNotEmpty()) withoutData.add(path)
        val fileDeclarations = declarationsByKey[key].orEmpty().filter { d -> d.lines.any { it in changedLines } }
        val exempt = exemptedLines[key].orEmpty()
        val inlineLines = mutableSetOf<Int>()
        for (d in fileDeclarations.filter { it.inline }) {
            notMeasurable.add("$path (inline ${d.name})")
            d.lines.forEach { inlineLines.add(it) }
        }
        for (d in fileDeclarations) {
            val isCovered = Triple(d.className, d.name, d.desc) in covered
            if (d.publicApi && !d.inline && !isCovered && d !in exemptedDeclarations) uncovered.add(d)
        }
        val reportLines = report.lines[key].orEmpty()
        for (line in changedLines.sorted()) {
            val coverage = reportLines[line] ?: continue
            if (line in exempt || line in inlineLines) continue
            linesTotal++
            if (coverage.coveredInstructions > 0) linesCovered++
            branchesCovered += coverage.coveredBranches
            branchesTotal += coverage.coveredBranches + coverage.missedBranches
        }
    }

    val floorMet = branchesTotal < settings.minimumBranches ||
        branchesCovered.toDouble() / branchesTotal >= settings.branchFloor
    val invalidOnChangedLines = invalid.filter {
        roots.locate(it.file, sourceKeys) != null && it.line in changed.byFile[it.file].orEmpty()
    }
    return CoverageResult(
        uncovered, branchesCovered, branchesTotal, floorMet, linesCovered, linesTotal,
        notMeasurable, affectingExemptions.sortedWith(compareBy({ it.file }, { it.line })), invalidOnChangedLines,
        withoutData, changedMeasuredLines, repoPaths, noExecutionData = !hasExecutionData,
    )
}

/** The lines and declarations [target] exempts among a file's [declarations]. */
private fun exempted(target: MarkerTarget, declarations: List<Declaration>): Pair<Set<Int>, List<Declaration>> =
    when (target) {
        is MarkerTarget.Line -> setOf(target.line) to emptyList()
        is MarkerTarget.Block -> target.lines.toSet() to emptyList()
        is MarkerTarget.Property -> setOf(target.line) to declarations.filter { it.lines.first == target.line }
        is MarkerTarget.Function -> {
            val firstLine = declarations.filter { it.lines.first >= target.line }.minOfOrNull { it.lines.first }
            val main = declarations.filter { it.lines.first == firstLine }
            val last = main.maxOfOrNull { it.lines.last }
            if (firstLine == null || last == null) {
                setOf(target.line) to emptyList()
            } else {
                val inside = declarations.filter { it.lines.first >= firstLine && it.lines.last <= last }
                (target.line..last).toSet() to (main + inside).distinct()
            }
        }
        is MarkerTarget.Class -> {
            val members = declarations.filter { target.name in it.className.substringAfterLast('/').split('$') }
            (setOf(target.line) + members.flatMap { it.lines }) to members
        }
    }

private fun lineRanges(lines: Set<Int>): List<String> {
    val result = mutableListOf<String>()
    var start: Int? = null
    var previous = 0
    for (line in lines.sorted()) {
        if (start != null && line != previous + 1) {
            result.add(rangeText(start, previous))
            start = null
        }
        if (start == null) start = line
        previous = line
    }
    start?.let { result.add(rangeText(it, previous)) }
    return result
}

private fun rangeText(first: Int, last: Int) = if (first == last) "$first" else "$first-$last"

private fun percent(covered: Int, total: Int) = if (total == 0) "n/a" else "${covered * PERCENT / total}%"

private const val PERCENT = 100

/**
 * Renders the result as text whose first line is `coverage: <projectName>: PASSED` or `FAILED`.
 */
fun CoverageResult.render(projectName: String): String = buildString {
    appendLine("coverage: $projectName: ${if (passed) "PASSED" else "FAILED"}")
    if (changedMeasuredLines == 0) {
        appendLine("no changed JVM code")
    } else if (linesTotal == 0 && uncoveredPublicApi.isEmpty() && filesWithoutCoverageData.isEmpty() &&
        notMeasurable.isEmpty() && exemptions.isEmpty()
    ) {
        appendLine("no changed executable lines")
    }
    if (uncoveredPublicApi.isNotEmpty()) {
        appendLine("Uncovered public API:")
        uncoveredPublicApi.forEach {
            appendLine("  ${repoPaths[it.sourceFile] ?: it.sourceFile}:${it.lines.first} ${it.name}${it.desc}")
        }
    }
    if (filesWithoutCoverageData.isNotEmpty()) {
        if (noExecutionData) {
            appendLine("No coverage data: no test executed this project's code (no JaCoCo execution data):")
        } else {
            appendLine("No coverage data (run the tests and generate the coverage report):")
        }
        filesWithoutCoverageData.forEach { appendLine("  $it") }
    }
    if (branchesTotal > 0) {
        val verdict = if (branchFloorMet) "" else " (below floor)"
        appendLine("Branches on changed lines: $branchesCovered/$branchesTotal (${percent(branchesCovered, branchesTotal)})$verdict")
    }
    if (linesTotal > 0) {
        appendLine("Lines on changed lines: $linesCovered/$linesTotal (${percent(linesCovered, linesTotal)})")
    }
    if (notMeasurable.isNotEmpty()) {
        appendLine("Not measurable:")
        notMeasurable.forEach { appendLine("  $it") }
    }
    if (exemptions.isNotEmpty()) {
        appendLine("Exempted:")
        exemptions.forEach { appendLine("  ${it.file}:${it.line} ${it.reason}") }
    }
    if (invalidMarkers.isNotEmpty()) {
        appendLine("Invalid markers:")
        invalidMarkers.forEach { appendLine("  ${it.file}:${it.line} coverage-exempt marker needs a reason") }
    }
}.trimEnd()
