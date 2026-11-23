package com.kelvsyc.internal.coverage.engine

private const val DEFAULT_BRANCH_FLOOR = 0.75
private const val DEFAULT_MINIMUM_BRANCHES = 4
private const val PERCENT = 100

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
 * The outcome of [evaluate]. Files are repository-relative paths.
 *
 * [uncoveredLines] are the changed lines (`file:line`) that no test executed. [notMeasurable] holds `file:lines`
 * entries, marked `(inline)` for the lines of inline bodies. [filesWithoutCoverageData] lists changed files that
 * compile to classes but have no entry in the report, which means the report is stale or missing; [noExecutionData]
 * means the report is empty, because no test executed the project's code. [changedMeasuredLines] counts changed lines
 * under measured roots.
 */
data class CoverageResult(
    val uncoveredLines: List<String>,
    val branchesCovered: Int,
    val branchesTotal: Int,
    val linesCovered: Int,
    val linesTotal: Int,
    val notMeasurable: List<String>,
    val exemptions: List<Exemption>,
    val invalidMarkers: List<InvalidMarker>,
    val filesWithoutCoverageData: List<String>,
    val noExecutionData: Boolean,
    val changedMeasuredLines: Int,
    val settings: RuleSettings,
) {
    /** True when the changed lines hold no branches, fewer than the minimum, or enough covered ones. */
    val branchFloorMet: Boolean
        get() = branchesTotal == 0 || branchesTotal < settings.minimumBranches ||
            branchesCovered.toDouble() / branchesTotal >= settings.branchFloor

    /**
     * True when every changed line is covered, the branch floor is met, no marker is invalid and every changed file
     * that compiles to classes has coverage data.
     */
    val passed: Boolean
        get() = uncoveredLines.isEmpty() && branchFloorMet && invalidMarkers.isEmpty() &&
            filesWithoutCoverageData.isEmpty()
}

/** The longest root holding [path], and whether it is measured; null when no root holds it. */
private fun SourceRoots.locate(path: String): Pair<String, Boolean>? {
    val measuredRoot = measured.filter { path.startsWith("$it/") }.maxByOrNull { it.length }
    val otherRoot = notMeasured.filter { path.startsWith("$it/") }.maxByOrNull { it.length }
    return when {
        measuredRoot != null && (otherRoot == null || measuredRoot.length >= otherRoot.length) -> measuredRoot to true
        otherRoot != null -> otherRoot to false
        else -> null
    }
}

/**
 * Applies the changed-code rules: every changed line with code (a line in the report) is executed by a test, branches
 * on changed lines meet the floor, and no marker on a changed line lacks a reason. Exempted lines are not judged; lines
 * of inline bodies and of sources under non-JVM roots are reported as not measurable.
 *
 * [scansByFile] holds the markers and declared package of changed files ([scanExemptions]), by repository path. A file
 * is matched to the report by its package and file name, so a file whose directory does not mirror its package still
 * matches its classes; without a known package, by its path below its root.
 */
fun evaluate(
    changed: ChangedLines,
    report: JacocoXmlReport,
    compiled: CompiledFacts,
    scansByFile: Map<String, ExemptionScan>,
    roots: SourceRoots,
    settings: RuleSettings,
): CoverageResult {
    val uncovered = mutableListOf<String>()
    val notMeasurable = mutableListOf<String>()
    val exemptions = mutableListOf<Exemption>()
    val invalid = mutableListOf<InvalidMarker>()
    val withoutData = mutableListOf<String>()
    var changedMeasuredLines = 0
    var branchesCovered = 0
    var branchesTotal = 0
    var linesCovered = 0
    var linesTotal = 0
    for ((path, lines) in changed.byFile.toSortedMap()) {
        val (root, measured) = roots.locate(path) ?: continue
        val scan = scansByFile[path]
        scan?.invalid?.filterTo(invalid) { it.line in lines }
        if (!measured) {
            lineRanges(lines).mapTo(notMeasurable) { "$path:$it" }
            continue
        }
        changedMeasuredLines += lines.size
        scan?.exemptions?.filterTo(exemptions) { it.target in lines }
        val fileName = path.substringAfterLast('/')
        val key = when (val pkg = scan?.packageName) {
            null -> path.removePrefix("$root/")
            "" -> fileName
            else -> pkg.replace('.', '/') + "/" + fileName
        }
        val reportLines = report.lines[key]
        if (reportLines == null) {
            if (key in compiled.compiledSourceFiles) withoutData.add(path)
            continue
        }
        val inline = compiled.inlineLines[key].orEmpty()
        lineRanges(lines.filter { it in inline }).mapTo(notMeasurable) { "$path:$it (inline)" }
        val exempt = scan?.exemptLines.orEmpty()
        for (line in lines.sorted()) {
            val coverage = reportLines[line] ?: continue
            if (line in exempt || line in inline) continue
            linesTotal++
            if (coverage.coveredInstructions > 0) linesCovered++ else uncovered.add("$path:$line")
            branchesCovered += coverage.coveredBranches
            branchesTotal += coverage.coveredBranches + coverage.missedBranches
        }
    }
    return CoverageResult(
        uncovered, branchesCovered, branchesTotal, linesCovered, linesTotal, notMeasurable, exemptions, invalid,
        withoutData, report.lines.isEmpty(), changedMeasuredLines, settings,
    )
}

/** [lines] as sorted ranges of consecutive lines: `3-5`, `9`. */
private fun lineRanges(lines: Collection<Int>): List<String> {
    val result = mutableListOf<String>()
    for (line in lines.sorted()) {
        val last = result.lastOrNull()
        val end = last?.substringAfter('-')?.toInt()
        if (end != null && line == end + 1) {
            result[result.lastIndex] = last.substringBefore('-') + "-" + line
        } else {
            result.add("$line")
        }
    }
    return result
}

private fun percent(covered: Int, total: Int) = "${covered * PERCENT / total}%"

/**
 * Renders the result as text whose first line is `coverage: <projectName>: PASSED` or `FAILED`.
 */
fun CoverageResult.render(projectName: String): String = buildString {
    appendLine("coverage: $projectName: ${if (passed) "PASSED" else "FAILED"}")
    if (changedMeasuredLines == 0) {
        appendLine("no changed JVM code")
    } else if (linesTotal == 0 && filesWithoutCoverageData.isEmpty() && notMeasurable.isEmpty() &&
        exemptions.isEmpty()
    ) {
        appendLine("no changed executable lines")
    }
    if (uncoveredLines.isNotEmpty()) {
        appendLine("Uncovered changed lines:")
        uncoveredLines.forEach { appendLine("  $it") }
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
    if (filesWithoutCoverageData.isNotEmpty()) {
        if (noExecutionData) {
            appendLine("No coverage data: no test executed this project's code (no JaCoCo execution data):")
        } else {
            appendLine("No coverage data (run the tests and generate the coverage report):")
        }
        filesWithoutCoverageData.forEach { appendLine("  $it") }
    }
}.trimEnd()
