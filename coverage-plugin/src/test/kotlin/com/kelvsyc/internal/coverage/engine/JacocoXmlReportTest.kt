package com.kelvsyc.internal.coverage.engine

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.io.TempDir

class JacocoXmlReportTest {
    private val report = JacocoXmlReport.parse(File(checkNotNull(javaClass.getResource("/jacoco-sample.xml")).toURI()))

    @Test
    fun linesAreKeyedByPackagePath() {
        assertEquals(LineCoverage(1, 2, 1, 1), report.lines.getValue("com/x/Foo.kt").getValue(12))
    }

    @Test
    fun externalEntitiesAreNotLoaded(@TempDir dir: File) {
        // Loading the entity would fail: its file does not exist.
        val missing = File(dir, "missing.txt")
        val xml = File(dir, "report.xml").apply {
            writeText(
                """
                |<?xml version="1.0" encoding="UTF-8"?>
                |<!DOCTYPE report [<!ENTITY leak SYSTEM "${missing.toURI()}">]>
                |<report name="r"><package name="com/x"><sourcefile name="A.kt">&leak;</sourcefile></package></report>
                """.trimMargin(),
            )
        }
        assertEquals(setOf("com/x/A.kt"), JacocoXmlReport.parse(xml).lines.keys)
    }
}
