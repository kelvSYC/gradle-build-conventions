package com.kelvsyc.internal.coverage.engine

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Signature edits of declarations whose names follow type parameters, receivers or `@JvmName`, against the compiled
 * `fixtures/Names.kt`. No method is covered; every line holding `if (` has two missed branches.
 */
class DeclarationNamesTest {
    private val path = "src/fixtures/kotlin/fixtures/Names.kt"
    private val key = "fixtures/Names.kt"
    private val text = File(path).readText()
    private val lines = text.lines()
    private val declarations = scanDeclarations(listOf(File(System.getProperty("fixtures.classes"))))
        .filter { it.sourceFile == key }

    private fun lineOf(fragment: String): Int = lines.indexOfFirst { fragment in it } + 1

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
        evaluate(changed.toSet()).uncoveredPublicApi.map { it.name }

    @Test
    fun boundedTypeParameter() {
        assertEquals(listOf("largest"), uncovered(lineOf("a: T,")))
    }

    @Test
    fun receiverWithTypeArguments() {
        assertEquals(listOf("total"), uncovered(lineOf("fun Map<String, Int>.total")))
    }

    @Test
    fun typeParametersAndReceiverWithASpace() {
        assertEquals(listOf("firstKey"), uncovered(lineOf("fun <K, V> Map<K, V>.firstKey")))
    }

    @Test
    fun functionTypeReceiver() {
        assertEquals(listOf("runTwice"), uncovered(lineOf("fun (() -> Unit).runTwice")))
    }

    @Test
    fun extensionPropertyWithTypeArguments() {
        assertEquals(listOf("getDoubleSize"), uncovered(lineOf("val <K, V> Map<K, V>.doubleSize")))
    }

    @Test
    fun functionJvmName() {
        assertEquals(listOf("renamed"), uncovered(lineOf("fun original")))
    }

    @Test
    fun getterJvmName() {
        assertEquals(listOf("isReady"), uncovered(lineOf("val ready")))
    }

    @Test
    fun markerExemptsABoundedGenericFunction() {
        val result = evaluate((lineOf("// coverage-exempt: generic helper")..lines.size).toSet())
        assertEquals(emptyList<Declaration>(), result.uncoveredPublicApi)
        assertEquals(0, result.branchesTotal)
    }

    @Test
    fun unreadableNameMatchesTheSmallestEnclosingFunction() {
        // The name follows the receiver on the next line, where the declaration scanner does not look for it.
        val source = "package p\n\nfun <T> T.\n    weird(): Int {\n    return 1\n}\n"
        val file = "src/p/Odd.kt"
        val weird = Declaration("p/OddKt", "weird", "(Ljava/lang/Object;)I", "p/Odd.kt", 5..5, true, false)
        val result = evaluate(
            ChangedLines(mapOf(file to setOf(3))), JacocoXmlReport(mapOf("p/Odd.kt" to emptyMap()), emptyList()),
            listOf(weird), emptyList(), emptyList(), SourceRoots(listOf("src"), emptyList()), RuleSettings(),
            mapOf(file to "p/Odd.kt"), sources = mapOf(file to source),
        )
        assertEquals(listOf(weird), result.uncoveredPublicApi)
    }
}
