package com.kelvsyc.internal.coverage.engine

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeclarationsTest {
    private val compiled: CompiledFacts by lazy { scanCompiled(listOf(File(System.getProperty("fixtures.classes")))) }

    private val key = "fixtures/Inlining.kt"

    @Test
    fun compiledSourceFilesAreKeyedByPackage() {
        assertTrue(key in compiled.compiledSourceFiles, compiled.compiledSourceFiles.toString())
    }

    @Test
    fun inlineLinesAreTheInlineBodiesOwnLines() {
        // The bodies of twice, sum (without the lines SMAP maps forEach to) and the doubled getter.
        assertEquals(setOf(4, 5, 6, 9, 10, 11, 24), compiled.inlineLines[key])
    }

    @Test
    fun inlineBodyLinesAreNotMeasurable() {
        val path = "src/main/kotlin/fixtures/Inlining.kt"
        val uncovered = LineCoverage(1, 0, 0, 0)
        val report = JacocoXmlReport(mapOf(key to mapOf(4 to uncovered, 5 to uncovered, 16 to uncovered)))
        val result = evaluate(
            ChangedLines(mapOf(path to setOf(4, 5, 16))),
            report,
            compiled,
            emptyMap(),
            mapOf(path to "fixtures"),
            SourceRoots(listOf("src/main/kotlin"), emptyList()),
            RuleSettings(),
        )
        assertEquals(listOf("$path:4-5 (inline)"), result.notMeasurable)
        assertEquals(listOf("$path:16"), result.uncoveredLines)
    }
}
