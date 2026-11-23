package com.kelvsyc.internal.coverage.engine

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeclarationsTest {
    private val declarations: List<Declaration> by lazy {
        scanDeclarations(listOf(File(System.getProperty("fixtures.classes"))))
    }

    private fun find(className: String, name: String): Declaration =
        declarations.singleOrNull { it.className == className && it.name == name }
            ?: error("No declaration $className.$name")

    @Test
    fun publicFunctionIsPublicApi() {
        val f = find("fixtures/TopLevelKt", "f")
        assertTrue(f.publicApi)
        assertEquals("fixtures/TopLevel.kt", f.sourceFile)
    }

    @Test
    fun internalFunctionIsNotPublicApi() {
        assertFalse(find("fixtures/TopLevelKt", "g").publicApi)
    }

    @Test
    fun defaultArgumentBridgeIsExcluded() {
        assertTrue(find("fixtures/TopLevelKt", "h").publicApi)
        assertFalse(find("fixtures/TopLevelKt", "h\$default").publicApi)
    }

    @Test
    fun dataClassEmitsOnlyDeclaredMembers() {
        // The compiler gives copy/componentN/equals/hashCode/toString no LineNumberTable, so they are never scanned.
        val emitted = declarations.filter { it.className == "fixtures/D" }
        assertEquals(setOf("<init>", "getA"), emitted.map { it.name }.toSet())
        assertTrue(emitted.all { it.publicApi })
    }

    @Test
    fun inlineFunctionFlagged() {
        assertTrue(find("fixtures/TopLevelKt", "i").inline)
        assertFalse(find("fixtures/TopLevelKt", "f").inline)
    }

    @Test
    fun inlinePropertyAccessorFlagged() {
        assertTrue(find("fixtures/TopLevelKt", "getDoubled").inline)
        assertFalse(find("fixtures/TopLevelKt", "getAnswer").inline)
        assertTrue(find("fixtures/TopLevelKt", "getAnswer").publicApi)
    }

    @Test
    fun privateClassMembersAreNotPublicApi() {
        assertFalse(find("fixtures/Hidden", "m").publicApi)
        assertFalse(find("fixtures/Outer\$Inner", "x").publicApi)
        assertTrue(find("fixtures/Outer\$Nested", "y").publicApi)
    }

    @Test
    fun protectedMemberOfOpenClassIsPublicApi() {
        assertTrue(find("fixtures/Base", "p").publicApi)
        assertTrue(find("fixtures/Base", "s").publicApi)
        assertFalse(find("fixtures/Base", "r").publicApi)
        assertFalse(declarations.single { it.className == "fixtures/Base" && it.name.startsWith("q") }.publicApi)
    }

    @Test
    fun linesSpanTheMethodBody() {
        val d = find("fixtures/TopLevelKt", "spans")
        assertEquals(2, d.lines.last - d.lines.first)
    }

    @Test
    fun inlinedCodeDoesNotInflateLines() {
        val d = find("fixtures/InliningKt", "callsInline")
        assertEquals(9..12, d.lines)
    }
}
