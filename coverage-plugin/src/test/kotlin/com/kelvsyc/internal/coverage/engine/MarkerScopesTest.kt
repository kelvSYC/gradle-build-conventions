package com.kelvsyc.internal.coverage.engine

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Markers inside one class, against the compiled `fixtures/MarkerScopes.kt`, whose constructors' line tables hold the
 * class's property-initializer lines too. No method is covered; every `return if` line has two missed branches.
 */
class MarkerScopesTest {
    private val path = "src/fixtures/kotlin/fixtures/MarkerScopes.kt"
    private val key = "fixtures/MarkerScopes.kt"
    private val text = File(path).readText()
    private val declarations = scanDeclarations(listOf(File(System.getProperty("fixtures.classes"))))
        .filter { it.sourceFile == key }

    private fun lineOf(fragment: String): Int = text.lines().indexOfFirst { fragment in it } + 1

    private fun evaluate(changed: Set<Int> = (1..text.lines().size).toSet()): CoverageResult {
        val (exemptions, invalid) = scanExemptions(path, text)
        val branchLines = text.lines().withIndex().filter { "return if" in it.value }.map { it.index + 1 }
        val lineCoverage = (1..text.lines().size).associateWith {
            if (it in branchLines) LineCoverage(1, 0, 2, 0) else LineCoverage(1, 0, 0, 0)
        }
        val report = JacocoXmlReport(
            mapOf(key to lineCoverage),
            declarations.map { MethodCoverage(it.className, it.name, it.desc, false) },
        )
        return evaluate(
            ChangedLines(mapOf(path to changed)), report, declarations, exemptions, invalid,
            SourceRoots(listOf("src/fixtures/kotlin"), emptyList()), RuleSettings(), mapOf(path to key),
        )
    }

    private fun unmarkedLine(): Int {
        val start = lineOf("class Unmarked")
        return text.lines().withIndex().first { it.index >= start && "return if" in it.value }.index + 1
    }

    private fun uncovered(result: CoverageResult): List<String> =
        result.uncoveredPublicApi.map { it.className.removePrefix("fixtures/") + "." + it.name + it.desc }

    @Test
    fun secondaryConstructorMarkerKeepsThePrimaryConstructorAndMethodsChecked() {
        val uncovered = uncovered(evaluate())
        assertTrue("Primary.<init>(I)V" in uncovered, uncovered.toString())
        assertFalse("Primary.<init>()V" in uncovered, uncovered.toString())
        assertTrue("Primary.branching(Z)I" in uncovered, uncovered.toString())
    }

    @Test
    fun secondaryConstructorMarkerWithoutPrimaryExemptsOnlyThatConstructor() {
        val uncovered = uncovered(evaluate())
        assertFalse("NoPrimary.<init>(I)V" in uncovered, uncovered.toString())
        assertTrue("NoPrimary.<init>(Ljava/lang/String;)V" in uncovered, uncovered.toString())
        assertTrue("NoPrimary.between(Z)I" in uncovered, uncovered.toString())
    }

    @Test
    fun branchesBetweenInitializersStayCounted() {
        // Each marked class's `return if` line lies between its initializers; none may be exempted.
        assertEquals(6, evaluate().branchesTotal)
    }

    @Test
    fun changeBetweenInitializersDoesNotChangeTheConstructor() {
        // Unmarked's constructor runs lines on both sides of sign(), but not sign()'s own line.
        val result = evaluate(setOf(unmarkedLine()))
        assertEquals(listOf("Unmarked.sign(Z)I"), uncovered(result))
    }

    @Test
    fun functionMarkerDoesNotReachANestedClassOfTheSameName() {
        val uncovered = uncovered(evaluate())
        assertTrue("Factories\$Holder\$Companion.make()I" in uncovered, uncovered.toString())
        assertFalse(uncovered.any { it.startsWith("Factories.Holder(") }, uncovered.toString())
        assertFalse(uncovered.any { it.startsWith("Factories\$Holder\$task\$1.") }, uncovered.toString())
    }

    @Test
    fun localClassMarkerDoesNotReachANestedClassOfTheSameName() {
        val uncovered = uncovered(evaluate())
        assertTrue("LocalOwner\$Helper.other()I" in uncovered, uncovered.toString())
    }

    @Test
    fun constructorOwnLinesSkipTheMethodsBetweenInitializers() {
        val primary = declarations.single { it.className == "fixtures/Unmarked" && it.name == "<init>" }
        assertEquals(setOf(lineOf("class Unmarked"), lineOf("val doubled")), primary.ownLines)
        assertFalse(unmarkedLine() in primary.ownLines)
        assertTrue(unmarkedLine() in primary.lines)
    }
}
