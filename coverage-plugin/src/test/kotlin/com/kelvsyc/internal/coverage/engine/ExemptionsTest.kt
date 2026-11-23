package com.kelvsyc.internal.coverage.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class ExemptionsTest {
    @Test
    fun trailingMarkerExemptsItsLine() {
        val (exemptions, invalid) = scanExemptions("A.kt", "a\nval x = y!! // coverage-exempt: unreachable\n")
        assertEquals(listOf(Exemption("A.kt", 2, "unreachable", false)), exemptions)
        assertEquals(emptyList<Any>(), invalid)
    }

    @Test
    fun standaloneMarkerIsStandalone() {
        val (exemptions, _) = scanExemptions("A.kt", "    // coverage-exempt: generated\nfoo()\n")
        assertEquals(listOf(Exemption("A.kt", 1, "generated", true)), exemptions)
    }

    @Test
    fun markerWithoutReasonIsInvalid() {
        val (exemptions, invalid) = scanExemptions("A.kt", "// coverage-exempt:\nx() // coverage-exempt:   \n")
        assertEquals(emptyList<Any>(), exemptions)
        assertEquals(listOf(InvalidMarker("A.kt", 1), InvalidMarker("A.kt", 2)), invalid)
    }
}
