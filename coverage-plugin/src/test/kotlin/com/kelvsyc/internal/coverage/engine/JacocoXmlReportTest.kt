package com.kelvsyc.internal.coverage.engine

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class JacocoXmlReportTest {
    private val report = JacocoXmlReport.parse(File(checkNotNull(javaClass.getResource("/jacoco-sample.xml")).toURI()))

    @Test
    fun linesAreKeyedByPackagePath() {
        assertEquals(LineCoverage(1, 2, 1, 1), report.lines.getValue("com/x/Foo.kt").getValue(12))
    }

    @Test
    fun uncoveredMethodsAreReported() {
        assertTrue(MethodCoverage("com/x/Foo", "bar", "()V", false) in report.methods)
        assertTrue(MethodCoverage("com/x/Foo", "baz", "()I", true) in report.methods)
    }
}
