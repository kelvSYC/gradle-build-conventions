package com.kelvsyc.internal.coverage.engine

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which declarations a change marks as changed, against the compiled `fixtures/Extents.kt`. No method is covered;
 * every line holding `if (` has two missed branches.
 */
class SourceExtentsTest {
    private val path = "src/fixtures/kotlin/fixtures/Extents.kt"
    private val key = "fixtures/Extents.kt"
    private val text = File(path).readText()
    private val lines = text.lines()
    private val declarations = scanDeclarations(listOf(File(System.getProperty("fixtures.classes"))))
        .filter { it.sourceFile == key }

    private fun lineOf(fragment: String, after: String? = null): Int {
        val start = after?.let { lineOf(it) } ?: 0
        return lines.withIndex().first { it.index >= start && fragment in it.value }.index + 1
    }

    private fun evaluate(changed: Set<Int>): CoverageResult {
        val (exemptions, invalid) = scanExemptions(path, text)
        val lineCoverage = (1..lines.size).associateWith {
            if ("if (" in lines[it - 1]) LineCoverage(1, 0, 2, 0) else LineCoverage(1, 0, 0, 0)
        }
        val report = JacocoXmlReport(
            mapOf(key to lineCoverage),
            declarations.map { MethodCoverage(it.className, it.name, it.desc, false) },
        )
        return evaluate(
            ChangedLines(mapOf(path to changed)), report, declarations, exemptions, invalid,
            SourceRoots(listOf("src/fixtures/kotlin"), emptyList()), RuleSettings(), mapOf(path to key),
            sources = mapOf(path to text),
        )
    }

    private fun uncovered(vararg changed: Int): List<String> =
        evaluate(changed.toSet()).uncoveredPublicApi.map { it.className.removePrefix("fixtures/") + "." + it.name + it.desc }

    @Test
    fun bodyCommentChangesTheFunction() {
        assertEquals(listOf("Extents.commented()I"), uncovered(lineOf("// a comment inside the body")))
    }

    @Test
    fun continuationLineChangesTheFunction() {
        assertEquals(listOf("Extents.continued()I"), uncovered(lineOf("second")))
    }

    @Test
    fun lambdaBodyChangesTheEnclosingFunction() {
        assertEquals(listOf("Extents.withLambda()I"), uncovered(lineOf("x * x")))
    }

    @Test
    fun anonymousObjectBodyChangesTheEnclosingFunction() {
        assertEquals(listOf("Extents.withObject()Ljava/lang/Runnable;"), uncovered(lineOf("println(seed)")))
    }

    @Test
    fun parameterLineOfAMultiLineSignatureChangesTheFunction() {
        assertEquals(listOf("Extents.multiLineSignature(II)J"), uncovered(lineOf("second: Int,")))
        assertEquals(listOf("Extents.multiLineSignature(II)J"), uncovered(lineOf("): Long {")))
    }

    @Test
    fun signatureLineChangesTheFunction() {
        assertEquals(listOf("Extents.returnType()I"), uncovered(lineOf("fun returnType")))
    }

    @Test
    fun defaultArgumentChangesTheFunction() {
        assertEquals(listOf("Extents.defaulted(I)I"), uncovered(lineOf("value: Int = 42")))
    }

    @Test
    fun annotationChangesTheFunction() {
        assertEquals(listOf("Extents.documented()I"), uncovered(lineOf("@Deprecated")))
    }

    @Test
    fun kdocOnlyChangeChangesNothing() {
        val result = evaluate(setOf(lineOf("* Documented.")))
        assertEquals(emptyList<Declaration>(), result.uncoveredPublicApi)
        assertEquals(true, result.passed)
    }

    @Test
    fun methodBetweenInitializersDoesNotChangeTheConstructor() {
        assertEquals(listOf("Extents.commented()I"), uncovered(lineOf("return a + 1")))
    }

    @Test
    fun primaryConstructorParameterChangesTheConstructor() {
        assertEquals(listOf("Header.<init>(II)V"), uncovered(lineOf("second: Int,", after = "class Header")))
    }

    @Test
    fun overloadSignatureChangesOnlyThatOverload() {
        assertEquals(listOf("Extents.overloaded(I)I"), uncovered(lineOf("fun overloaded(x: Int)")))
        assertEquals(
            listOf("Extents.overloaded(Ljava/lang/String;)Ljava/lang/String;"),
            uncovered(lineOf("fun overloaded(x: String)")),
        )
    }

    @Test
    fun functionMarkerExemptsAnExpressionBodiedObjectAndALocalClass() {
        // Every line of both marked functions; the class's own constructor line stays unchanged.
        val result = evaluate((lineOf("// coverage-exempt: factory")..lines.size).toSet())
        assertEquals(emptyList<Declaration>(), result.uncoveredPublicApi)
        assertEquals(0, result.branchesTotal)
    }
}
