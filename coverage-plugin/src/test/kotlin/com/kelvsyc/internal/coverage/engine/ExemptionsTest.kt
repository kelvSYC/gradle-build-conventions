package com.kelvsyc.internal.coverage.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExemptionsTest {
    @Test
    fun trailingMarkerExemptsItsLine() {
        val scan = scanExemptions("A.kt", "a\nval x = y!! // coverage-exempt: unreachable\nb\n")
        assertEquals(setOf(2), scan.exemptLines)
        assertEquals(listOf(Exemption("A.kt", 2, "unreachable", 2)), scan.exemptions)
        assertEquals(emptyList<InvalidMarker>(), scan.invalid)
    }

    @Test
    fun standaloneMarkerExemptsNextCodeLine() {
        val text = """
            |fun f(y: Int?): Int {
            |    // coverage-exempt: always set
            |
            |    // a comment
            |    /* a block
            |       comment */
            |    @Suppress(
            |        "x",
            |    )
            |    @Deprecated("y") // note
            |    val x = y ?: error("no")
            |    return x
            |}
        """.trimMargin()
        val scan = scanExemptions("A.kt", text)
        assertEquals(setOf(11), scan.exemptLines)
        assertEquals(listOf(Exemption("A.kt", 2, "always set", 11)), scan.exemptions)
    }

    @Test
    fun markerWithoutReasonIsInvalid() {
        val scan = scanExemptions("A.kt", "// coverage-exempt:\nx() // coverage-exempt:   \n")
        assertEquals(emptyList<Exemption>(), scan.exemptions)
        assertEquals(listOf(InvalidMarker("A.kt", 1), InvalidMarker("A.kt", 2)), scan.invalid)
    }

    @Test
    fun packageNameIsTheDeclaredPackage() {
        assertEquals("com.a", packageName("@file:JvmName(\"X\")\npackage com.a\n"))
        assertEquals("com.b", packageName("package com.b;\nclass J {}\n"))
        assertEquals("", packageName("fun f() = 1\n"))
    }
}
