package com.kelvsyc.internal.coverage.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoverageRulesTest {
    private val root = "components/x/src/commonMain/kotlin"
    private val jsRoot = "components/x/src/jsMain/kotlin"
    private val repoFile = "$root/com/a/Foo.kt"
    private val key = "com/a/Foo.kt"
    private val roots = SourceRoots(listOf(root), listOf(jsRoot))

    private val covered = LineCoverage(0, 2, 0, 0)
    private val uncovered = LineCoverage(2, 0, 0, 0)

    private fun report(vararg lines: Pair<Int, LineCoverage>) = JacocoXmlReport(mapOf(key to lines.toMap()))

    private fun changed(vararg lines: Int, file: String = repoFile) = ChangedLines(mapOf(file to lines.toSet()))

    private fun eval(
        changed: ChangedLines,
        report: JacocoXmlReport,
        exemptions: Map<String, ExemptionScan> = emptyMap(),
        packages: Map<String, String> = mapOf(repoFile to "com.a"),
        settings: RuleSettings = RuleSettings(),
        compiled: CompiledFacts = CompiledFacts(emptyMap(), setOf(key)),
    ) = evaluate(changed, report, compiled, exemptions, packages, roots, settings)

    private fun branchLine(covered: Int, missed: Int) = LineCoverage(1, 1, missed, covered)

    @Test
    fun uncoveredChangedLineFails() {
        val result = eval(changed(10, 11), report(10 to covered, 11 to uncovered))
        assertFalse(result.passed)
        assertEquals(listOf("$repoFile:11"), result.uncoveredLines)
        val text = result.render("proj")
        assertEquals("coverage: proj: FAILED", text.lines().first())
        assertTrue(text.contains("Uncovered changed lines:\n  $repoFile:11"), text)
    }

    @Test
    fun coveredChangedLinesPass() {
        val result = eval(changed(10, 11), report(10 to covered, 11 to covered))
        assertTrue(result.passed)
        assertEquals("coverage: proj: PASSED", result.render("proj").lines().first())
    }

    @Test
    fun unchangedUncoveredLinesIgnored() {
        assertTrue(eval(changed(10), report(10 to covered, 11 to uncovered)).passed)
    }

    @Test
    fun commentOnlyChangePasses() {
        val result = eval(changed(1), report(10 to uncovered))
        assertTrue(result.passed)
        assertTrue(result.render("p").contains("no changed executable lines"))
        assertFalse(result.render("p").contains("no changed JVM code"))
    }

    @Test
    fun exemptedChangedLineIsNotJudged() {
        val scan = scanExemptions(repoFile, "\n".repeat(10) + "x() // coverage-exempt: unreachable\n")
        val result = eval(changed(11, 12), report(11 to branchLine(0, 4), 12 to covered), mapOf(repoFile to scan))
        assertTrue(result.passed)
        assertEquals(0, result.branchesTotal)
        assertEquals(1, result.linesTotal)
        assertEquals(scan.exemptions, result.exemptions)
        assertTrue(result.render("p").contains("Exempted:\n  $repoFile:11 unreachable"))
    }

    @Test
    fun exemptionOfUnchangedLineIsNotListed() {
        val scan = scanExemptions(repoFile, "\n".repeat(10) + "x() // coverage-exempt: unreachable\n")
        assertEquals(emptyList<Exemption>(), eval(changed(12), report(12 to covered), mapOf(repoFile to scan)).exemptions)
    }

    @Test
    fun markerWithoutReasonOnChangedLineFails() {
        val scan = ExemptionScan(emptySet(), emptyList(), listOf(InvalidMarker(repoFile, 10)))
        val result = eval(changed(10), report(), mapOf(repoFile to scan))
        assertFalse(result.passed)
        assertTrue(result.render("p").contains("Invalid markers:\n  $repoFile:10"))
    }

    @Test
    fun markerWithoutReasonOnUnchangedLineIsIgnored() {
        val scan = ExemptionScan(emptySet(), emptyList(), listOf(InvalidMarker(repoFile, 99)))
        assertTrue(eval(changed(10), report(), mapOf(repoFile to scan)).passed)
    }

    @Test
    fun markerWithoutReasonOutsideAnyRootIsIgnored() {
        val scan = ExemptionScan(emptySet(), emptyList(), listOf(InvalidMarker("build.gradle.kts", 3)))
        assertTrue(eval(changed(3, file = "build.gradle.kts"), report(), mapOf("build.gradle.kts" to scan)).passed)
    }

    @Test
    fun inlineLinesAreNotMeasurable() {
        val compiled = CompiledFacts(mapOf(key to setOf(11, 12)), setOf(key))
        val result = eval(changed(11, 12), report(11 to uncovered, 12 to branchLine(0, 6)), compiled = compiled)
        assertTrue(result.passed)
        assertEquals(listOf("$repoFile:11-12 (inline)"), result.notMeasurable)
        assertEquals(0, result.linesTotal)
    }

    @Test
    fun branchFloorFailsBelow75Percent() {
        val result = eval(changed(10, 11), report(10 to branchLine(3, 1), 11 to branchLine(2, 2)))
        assertEquals(5, result.branchesCovered)
        assertEquals(8, result.branchesTotal)
        assertFalse(result.branchFloorMet)
        assertFalse(result.passed)
        assertTrue(result.render("p").contains("Branches on changed lines: 5/8 (62%) (below floor)"))
    }

    @Test
    fun branchFloorPassesAt75Percent() {
        val result = eval(changed(10, 11), report(10 to branchLine(3, 1), 11 to branchLine(3, 1)))
        assertEquals(6, result.branchesCovered)
        assertEquals(8, result.branchesTotal)
        assertTrue(result.branchFloorMet)
        assertTrue(result.passed)
    }

    @Test
    fun fewerThanMinimumBranchesSkipsFloor() {
        val result = eval(changed(10), report(10 to branchLine(0, 2)))
        assertEquals(2, result.branchesTotal)
        assertTrue(result.branchFloorMet)
    }

    @Test
    fun linesAreCountedByCoveredInstructions() {
        val result = eval(changed(10, 11, 12), report(10 to covered, 11 to uncovered, 50 to covered))
        assertEquals(1, result.linesCovered)
        assertEquals(2, result.linesTotal)
        assertTrue(result.render("p").contains("Lines on changed lines: 1/2 (50%)"))
    }

    @Test
    fun nonJvmSourcesAreNotMeasurable() {
        val js = "$jsRoot/com/a/Bar.kt"
        val result = eval(ChangedLines(mapOf(js to setOf(3, 4, 5, 9))), report())
        assertTrue(result.passed)
        assertEquals(listOf("$js:3-5", "$js:9"), result.notMeasurable)
    }

    @Test
    fun filesUnderNoRootAreIgnored() {
        val result = eval(ChangedLines(mapOf("build.gradle.kts" to setOf(1, 2))), report())
        assertTrue(result.passed)
        assertEquals(emptyList<String>(), result.notMeasurable)
        assertTrue(result.render("p").contains("no changed JVM code"))
    }

    @Test
    fun fileOutsideItsPackageDirectoryIsChecked() {
        val file = "$root/Foo.kt"
        val result = eval(changed(11, file = file), report(11 to uncovered), packages = mapOf(file to "com.a"))
        assertEquals(listOf("$file:11"), result.uncoveredLines)
    }

    @Test
    fun changedCompiledFileWithoutReportEntryFails() {
        val result = eval(changed(10), JacocoXmlReport(mapOf("com/a/Other.kt" to emptyMap())))
        assertFalse(result.passed)
        assertEquals(listOf(repoFile), result.filesWithoutCoverageData)
        assertTrue(result.render("p").contains("No coverage data (run the tests"))
    }

    @Test
    fun changedFileWithoutClassesNeedsNoReportEntry() {
        val result = eval(changed(10), JacocoXmlReport(emptyMap()), compiled = CompiledFacts(emptyMap(), emptySet()))
        assertTrue(result.passed)
    }

    @Test
    fun noExecutionDataSaysNoTestRan() {
        val result = eval(changed(10), JacocoXmlReport(emptyMap()))
        assertFalse(result.passed)
        assertTrue(result.noExecutionData)
        assertTrue(result.render("p").contains("no test executed this project's code"))
    }
}
