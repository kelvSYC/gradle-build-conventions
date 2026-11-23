package com.kelvsyc.internal.coverage.engine

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A marker on an interface's abstract function, against the compiled `fixtures/Delegation.kt`: the compiler generates a
 * same-named method in the class that implements the interface by delegation. It carries no line numbers, so it is
 * never judged, and the marker must not reach it or anything between.
 */
class DelegationMarkerTest {
    private val path = "src/fixtures/kotlin/fixtures/Delegation.kt"
    private val key = "fixtures/Delegation.kt"

    @Test
    fun markerOnInterfaceFunctionDoesNotReachTheDelegatingClass() {
        val text = File(path).readText()
        val declarations = scanDeclarations(listOf(File(System.getProperty("fixtures.classes"))))
            .filter { it.sourceFile == key }
        val (exemptions, invalid) = scanExemptions(path, text)
        val report = JacocoXmlReport(
            mapOf(key to emptyMap()),
            declarations.map { MethodCoverage(it.className, it.name, it.desc, false) },
        )
        val result = evaluate(
            ChangedLines(mapOf(path to (1..text.lines().size).toSet())),
            report, declarations, exemptions, invalid,
            SourceRoots(listOf("src/fixtures/kotlin"), emptyList()), RuleSettings(), mapOf(path to key),
        )
        assertTrue(declarations.none { it.className == "fixtures/Forwarding" && it.name == "speak" }, "$declarations")
        assertEquals(emptyList<Exemption>(), result.exemptions)
        val uncovered = result.uncoveredPublicApi.map { it.className + "." + it.name }
        assertTrue("fixtures/Forwarding.<init>" in uncovered, uncovered.toString())
        assertTrue("fixtures/Between.between" in uncovered, uncovered.toString())
    }
}
