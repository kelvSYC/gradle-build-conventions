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
) {
    /** True when no changed public API is uncovered, the branch floor is met and no marker is invalid. */
    val passed: Boolean
        get() = uncoveredPublicApi.isEmpty() && branchFloorMet && invalidMarkers.isEmpty()
}

private sealed interface Location {
    data class Measured(val key: String) : Location
    data object NotMeasured : Location
}

private fun SourceRoots.locate(path: String): Location? {
    val measuredRoot = measured.filter { path.startsWith("$it/") }.maxByOrNull { it.length }
    val otherRoot = notMeasured.filter { path.startsWith("$it/") }.maxByOrNull { it.length }
    return when {
        measuredRoot != null && (otherRoot == null || measuredRoot.length >= otherRoot.length) ->
            Location.Measured(path.removePrefix("$measuredRoot/"))
        otherRoot != null -> Location.NotMeasured
        else -> null
    }
}

/**
 * Applies the changed-code rules: every changed public declaration is covered, branches on changed lines meet the
 * floor, and no exemption marker lacks a reason. Inline declarations and non-JVM sources are reported as not
 * measurable rather than judged.
 */
fun evaluate(
    changed: ChangedLines,
    report: JacocoXmlReport,
    declarations: List<Declaration>,
    exemptions: List<Exemption>,
    invalid: List<InvalidMarker>,
    roots: SourceRoots,
    settings: RuleSettings,
): CoverageResult {
    val measuredChanged = linkedMapOf<String, Pair<String, Set<Int>>>()
    val notMeasurable = mutableListOf<String>()
    for ((path, lines) in changed.byFile.toSortedMap()) {
        when (val location = roots.locate(path)) {
            is Location.Measured -> measuredChanged[location.key] = path to lines
            Location.NotMeasured -> notMeasurable.addAll(lineRanges(lines).map { "$path:$it" })
            null -> Unit
        }
    }

    val declarationsByKey = declarations.groupBy { it.sourceFile }
    val covered = report.methods.filter { it.covered }.map { Triple(it.className, it.name, it.desc) }.toSet()

    val exemptedLines = mutableMapOf<String, MutableSet<Int>>()
    val affectingExemptions = mutableListOf<Exemption>()
    for (exemption in exemptions) {
        val key = (roots.locate(exemption.file) as? Location.Measured)?.key ?: continue
        val changedLines = measuredChanged[key]?.second.orEmpty()
        if (exemption.standalone) {
            val inFile = declarationsByKey[key].orEmpty().filter { it.lines.first > exemption.line }
            val firstLine = inFile.minOfOrNull { it.lines.first } ?: continue
            val targets = inFile.filter { it.lines.first == firstLine }
            targets.forEach { exemptedLines.getOrPut(key) { mutableSetOf() }.addAll(it.lines) }
            if (targets.any { target -> target.lines.any { it in changedLines } }) affectingExemptions.add(exemption)
        } else {
            exemptedLines.getOrPut(key) { mutableSetOf() }.add(exemption.line)
            if (exemption.line in changedLines) affectingExemptions.add(exemption)
        }
    }

    val uncovered = mutableListOf<Declaration>()
    var branchesCovered = 0
    var branchesTotal = 0
    var linesCovered = 0
    var linesTotal = 0
    for ((key, entry) in measuredChanged) {
        val (path, changedLines) = entry
        val fileDeclarations = declarationsByKey[key].orEmpty().filter { d -> d.lines.any { it in changedLines } }
        val exempt = exemptedLines[key].orEmpty()
        val inlineLines = mutableSetOf<Int>()
        for (d in fileDeclarations.filter { it.inline }) {
            notMeasurable.add("$path (inline ${d.name})")
            d.lines.forEach { inlineLines.add(it) }
        }
        for (d in fileDeclarations) {
            val isCovered = Triple(d.className, d.name, d.desc) in covered
            if (d.publicApi && !d.inline && !isCovered && d.lines.none { it in exempt }) uncovered.add(d)
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
    val invalidOnChangedLines = invalid.filter { it.line in changed.byFile[it.file].orEmpty() }
    return CoverageResult(
        uncovered, branchesCovered, branchesTotal, floorMet, linesCovered, linesTotal,
        notMeasurable, affectingExemptions.sortedWith(compareBy({ it.file }, { it.line })), invalidOnChangedLines,
    )
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
    if (linesTotal == 0 && uncoveredPublicApi.isEmpty() && notMeasurable.isEmpty() && exemptions.isEmpty()) {
        appendLine("no changed JVM code")
    }
    if (uncoveredPublicApi.isNotEmpty()) {
        appendLine("Uncovered public API:")
        uncoveredPublicApi.forEach { appendLine("  ${it.sourceFile}:${it.lines.first} ${it.name}${it.desc}") }
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
