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

    private fun decl(
        name: String = "f",
        lines: IntRange = 10..14,
        publicApi: Boolean = true,
        inline: Boolean = false,
        className: String = "com/a/Foo",
    ) = Declaration(className, name, "()V", key, lines, publicApi, inline)

    private fun report(
        methodCovered: Boolean? = null,
        lines: Map<Int, LineCoverage> = emptyMap(),
        name: String = "f",
    ) = JacocoXmlReport(
        mapOf(key to lines),
        listOfNotNull(methodCovered?.let { MethodCoverage("com/a/Foo", name, "()V", it) }),
    )

    private fun changed(vararg lines: Int, file: String = repoFile) = ChangedLines(mapOf(file to lines.toSet()))

    private fun eval(
        changed: ChangedLines,
        report: JacocoXmlReport,
        declarations: List<Declaration> = emptyList(),
        exemptions: List<Exemption> = emptyList(),
        invalid: List<InvalidMarker> = emptyList(),
        settings: RuleSettings = RuleSettings(),
        sourceKeys: Map<String, String> = emptyMap(),
        hasExecutionData: Boolean = true,
    ) = evaluate(changed, report, declarations, exemptions, invalid, roots, settings, sourceKeys, hasExecutionData)

    private fun branchLine(covered: Int, missed: Int) = LineCoverage(1, 1, missed, covered)

    @Test
    fun uncoveredChangedPublicFunctionFails() {
        val result = eval(changed(10), report(false), listOf(decl()))
        assertFalse(result.passed)
        assertEquals(listOf(decl()), result.uncoveredPublicApi)
    }

    @Test
    fun coveredChangedPublicFunctionPasses() {
        assertTrue(eval(changed(10), report(true), listOf(decl())).passed)
    }

    @Test
    fun unchangedUncoveredPublicFunctionIsIgnored() {
        assertTrue(eval(changed(99), report(false), listOf(decl())).passed)
    }

    @Test
    fun bodyOnlyChangeMakesDeclarationChanged() {
        val result = eval(changed(13), report(false), listOf(decl()))
        assertFalse(result.passed)
        assertEquals(1, result.uncoveredPublicApi.size)
    }

    @Test
    fun publicApiWithoutMethodCoverageCountsAsUncovered() {
        assertFalse(eval(changed(10), report(null), listOf(decl())).passed)
    }

    @Test
    fun nonPublicDeclarationIsNotRequiredToBeCovered() {
        assertTrue(eval(changed(10), report(false), listOf(decl(publicApi = false))).passed)
    }

    @Test
    fun branchFloorFailsBelow75Percent() {
        val result = eval(changed(10, 11), report(lines = mapOf(10 to branchLine(3, 1), 11 to branchLine(2, 2))))
        assertEquals(5, result.branchesCovered)
        assertEquals(8, result.branchesTotal)
        assertFalse(result.branchFloorMet)
        assertFalse(result.passed)
    }

    @Test
    fun branchFloorPassesAt75Percent() {
        val result = eval(changed(10, 11), report(lines = mapOf(10 to branchLine(3, 1), 11 to branchLine(3, 1))))
        assertEquals(6, result.branchesCovered)
        assertEquals(8, result.branchesTotal)
        assertTrue(result.branchFloorMet)
        assertTrue(result.passed)
    }

    @Test
    fun fewerThanMinimumBranchesSkipsFloor() {
        val result = eval(changed(10), report(lines = mapOf(10 to branchLine(0, 2))))
        assertEquals(2, result.branchesTotal)
        assertTrue(result.branchFloorMet)
    }

    @Test
    fun linesAreCountedByCoveredInstructions() {
        val lines = mapOf(10 to LineCoverage(0, 3, 0, 0), 11 to LineCoverage(2, 0, 0, 0), 50 to LineCoverage(0, 1, 0, 0))
        val result = eval(changed(10, 11, 12), report(lines = lines))
        assertEquals(1, result.linesCovered)
        assertEquals(2, result.linesTotal)
    }

    @Test
    fun trailingExemptionRemovesLineBranches() {
        val exemption = Exemption(repoFile, 10, "unreachable", false)
        val result = eval(
            changed(10, 11),
            report(lines = mapOf(10 to branchLine(0, 4), 11 to branchLine(4, 0))),
            exemptions = listOf(exemption),
        )
        assertEquals(4, result.branchesTotal)
        assertTrue(result.passed)
        assertEquals(listOf(exemption), result.exemptions)
    }

    @Test
    fun standaloneExemptionExemptsNextDeclaration() {
        val exemption = Exemption(repoFile, 9, "platform stub", true, MarkerTarget.Function(10, "f"))
        val result = eval(changed(10), report(false), listOf(decl()), exemptions = listOf(exemption))
        assertTrue(result.passed)
        assertEquals(listOf(exemption), result.exemptions)
        assertTrue(result.render("p").contains("Exempted"))
    }

    @Test
    fun standaloneExemptionOnlyReachesTheNextDeclaration() {
        val exemption = Exemption(repoFile, 9, "stub", true, MarkerTarget.Function(10, "f"))
        val other = decl(name = "g", lines = 20..22)
        val result = eval(
            changed(10, 20),
            report(false, name = "g"),
            listOf(decl(), other),
            exemptions = listOf(exemption),
        )
        assertEquals(listOf(other), result.uncoveredPublicApi)
    }

    @Test
    fun exemptionOnUntouchedCodeIsNotListed() {
        val exemption = Exemption(repoFile, 9, "stub", true, MarkerTarget.Function(10, "f"))
        val result = eval(changed(99), report(false), listOf(decl()), exemptions = listOf(exemption))
        assertEquals(emptyList<Exemption>(), result.exemptions)
    }

    @Test
    fun inlineBodyIsNotMeasurable() {
        val inlineDecl = decl(name = "inl", inline = true)
        val result = eval(
            changed(11),
            report(false, lines = mapOf(11 to branchLine(0, 6)), name = "inl"),
            listOf(inlineDecl),
        )
        assertTrue(result.passed)
        assertEquals(listOf("$repoFile (inline inl)"), result.notMeasurable)
        assertEquals(0, result.branchesTotal)
        assertEquals(0, result.linesTotal)
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
    }

    @Test
    fun noChangedCodePasses() {
        val result = eval(ChangedLines(emptyMap()), report())
        assertTrue(result.passed)
        assertTrue(result.render("p").contains("no changed JVM code"))
    }

    @Test
    fun invalidMarkerFails() {
        val result = eval(changed(10), report(), invalid = listOf(InvalidMarker(repoFile, 10)))
        assertFalse(result.passed)
        assertTrue(result.render("p").contains("Foo.kt:10"))
    }

    @Test
    fun invalidMarkerOnUnchangedLineIsIgnored() {
        val result = eval(changed(10), report(), invalid = listOf(InvalidMarker(repoFile, 99)))
        assertTrue(result.passed)
    }

    @Test
    fun renderListsFailuresWithFileAndLine() {
        val text = eval(changed(12), report(false), listOf(decl(lines = 12..14))).render("proj")
        assertEquals("coverage: proj: FAILED", text.lines().first())
        assertTrue(text.contains("Uncovered public API"))
        assertTrue(text.contains("Foo.kt:12"))
    }

    @Test
    fun trailingExemptionInBodyDoesNotExemptTheDeclaration() {
        val exemption = Exemption(repoFile, 12, "unreachable", false)
        val result = eval(changed(10, 12), report(false), listOf(decl()), exemptions = listOf(exemption))
        assertFalse(result.passed)
        assertEquals(1, result.uncoveredPublicApi.size)
    }

    @Test
    fun trailingExemptionOnFirstLineExemptsTheDeclaration() {
        val exemption = Exemption(repoFile, 10, "stub", false, MarkerTarget.Function(10, "f"))
        assertTrue(eval(changed(10), report(false), listOf(decl()), exemptions = listOf(exemption)).passed)
    }

    @Test
    fun changedFileWithDeclarationsButNoReportEntryFails() {
        val result = eval(changed(10), JacocoXmlReport(emptyMap(), emptyList()), listOf(decl(publicApi = false)))
        assertFalse(result.passed)
        assertEquals(listOf(repoFile), result.filesWithoutCoverageData)
        assertTrue(result.render("p").contains("$repoFile"))
    }

    @Test
    fun commentOnlyChangeHasNoExecutableLines() {
        val result = eval(changed(1), report(true), listOf(decl()))
        assertTrue(result.passed)
        assertTrue(result.render("p").contains("no changed executable lines"))
        assertFalse(result.render("p").contains("no changed JVM code"))
    }

    @Test
    fun uncoveredApiIsRenderedWithRepoRelativePath() {
        val text = eval(changed(12), report(false), listOf(decl(lines = 12..14))).render("p")
        assertTrue(text.contains("$repoFile:12"))
    }

    @Test
    fun invalidMarkerOutsideAnyRootIsIgnored() {
        val result = eval(
            ChangedLines(mapOf("build.gradle.kts" to setOf(3))),
            report(),
            invalid = listOf(InvalidMarker("build.gradle.kts", 3)),
        )
        assertTrue(result.passed)
    }

    @Test
    fun renderStartsWithPassed() {
        assertEquals("coverage: proj: PASSED", eval(changed(10), report(true), listOf(decl())).render("proj").lines().first())
    }

    @Test
    fun fileOutsideItsPackageDirectoryIsKeyedByItsPackage() {
        val file = "$root/Foo.kt"
        val result = eval(changed(10, file = file), report(false), listOf(decl()), sourceKeys = mapOf(file to key))
        assertFalse(result.passed)
        assertEquals(listOf(decl()), result.uncoveredPublicApi)
        assertTrue(result.render("p").contains("$file:10"))
    }

    @Test
    fun lineTargetDoesNotExemptTheEnclosingFunction() {
        val exemption = Exemption(repoFile, 11, "unreachable", true, MarkerTarget.Line(12))
        val result = eval(
            changed(10, 12),
            report(false, lines = mapOf(12 to branchLine(0, 4))),
            listOf(decl()),
            exemptions = listOf(exemption),
        )
        assertEquals(listOf(decl()), result.uncoveredPublicApi)
        assertEquals(0, result.branchesTotal)
        assertEquals(listOf(exemption), result.exemptions)
    }

    @Test
    fun classTargetExemptsEveryMemberAndNestedClass() {
        val members = listOf(
            decl(name = "<init>", lines = 3..3),
            decl(name = "f"),
            decl(name = "g", lines = 20..21, className = "com/a/Foo\$Nested"),
        )
        val other = decl(name = "h", lines = 30..31, className = "com/a/FooKt")
        val exemption = Exemption(repoFile, 2, "generated", true, MarkerTarget.Class(3, "Foo"))
        val result = eval(changed(3, 10, 20, 30), report(false), members + other, exemptions = listOf(exemption))
        assertEquals(listOf(other), result.uncoveredPublicApi)
    }

    @Test
    fun functionTargetExemptsTheFunctionAndItsLambdas() {
        val lambda = decl(name = "f\$lambda\$0", lines = 12..13, publicApi = false, className = "com/a/Foo")
        val exemption = Exemption(repoFile, 8, "glue", true, MarkerTarget.Function(9, "f"))
        val result = eval(
            changed(10, 13),
            report(false, lines = mapOf(13 to branchLine(0, 4))),
            listOf(decl(), lambda),
            exemptions = listOf(exemption),
        )
        assertTrue(result.passed)
        assertEquals(0, result.branchesTotal)
    }

    @Test
    fun lineTargetBeforeLambdaDoesNotExemptTheLambdaBeyondItsLine() {
        val lambda = decl(name = "f\$lambda\$0", lines = 12..13, publicApi = false)
        val exemption = Exemption(repoFile, 11, "r", true, MarkerTarget.Line(12))
        val result = eval(
            changed(12, 13),
            report(true, lines = mapOf(12 to branchLine(0, 2), 13 to branchLine(1, 3))),
            listOf(decl(), lambda),
            exemptions = listOf(exemption),
        )
        assertEquals(4, result.branchesTotal)
        assertFalse(result.passed)
    }

    @Test
    fun noExecutionDataSaysNoTestRan() {
        val empty = JacocoXmlReport(emptyMap(), emptyList())
        val result = eval(changed(10), empty, listOf(decl()), hasExecutionData = false)
        assertFalse(result.passed)
        assertTrue(result.render("p").contains("no test executed this project's code"))
    }
}
