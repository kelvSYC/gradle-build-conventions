package com.kelvsyc.internal.coverage.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class ExemptionsTest {
    @Test
    fun trailingMarkerExemptsItsLine() {
        val (exemptions, invalid) = scanExemptions("A.kt", "a\nval x = y!! // coverage-exempt: unreachable\n")
        val expected = Exemption("A.kt", 2, "unreachable", false, MarkerTarget.Property(2, "x", "AKt"))
        assertEquals(listOf(expected), exemptions)
        assertEquals(emptyList<Any>(), invalid)
    }

    @Test
    fun standaloneMarkerIsStandalone() {
        val (exemptions, _) = scanExemptions("A.kt", "    // coverage-exempt: generated\nfoo()\n")
        assertEquals(listOf(Exemption("A.kt", 1, "generated", true, MarkerTarget.Line(2))), exemptions)
    }

    @Test
    fun markerWithoutReasonIsInvalid() {
        val (exemptions, invalid) = scanExemptions("A.kt", "// coverage-exempt:\nx() // coverage-exempt:   \n")
        assertEquals(emptyList<Any>(), exemptions)
        assertEquals(listOf(InvalidMarker("A.kt", 1), InvalidMarker("A.kt", 2)), invalid)
    }

    private fun target(text: String): MarkerTarget = scanExemptions("A.kt", text).first.single().target

    @Test
    fun markerAboveLocalValTargetsItsLine() {
        val text = "fun f(y: Int?): Int {\n    // coverage-exempt: r\n    val x = y ?: error(\"no\")\n    return x\n}\n"
        assertEquals(MarkerTarget.Line(3), target(text))
    }

    @Test
    fun markerAboveClassTargetsTheClass() {
        val text = "// coverage-exempt: r\n@Suppress(\"x\")\ninternal data class Foo(val a: Int)\n"
        assertEquals(MarkerTarget.Class(3, "Foo"), target(text))
    }

    @Test
    fun markerAboveCompanionObjectTargetsTheCompanion() {
        assertEquals(MarkerTarget.Class(2, "Companion"), target("// coverage-exempt: r\ncompanion object {\n}\n"))
    }

    @Test
    fun markerAboveFunctionSkipsBlankLinesAndComments() {
        val text = "// coverage-exempt: r\n\n/**\n * Doc.\n */\n// note\npublic suspend fun <T> f() = 1\n"
        assertEquals(MarkerTarget.Function(7, "f", "AKt"), target(text))
    }

    @Test
    fun markerBeforeLambdaTargetsItsLine() {
        assertEquals(MarkerTarget.Line(2), target("    // coverage-exempt: r\n    list.map { it + 1 }\n"))
    }

    @Test
    fun markerAboveInitTargetsTheBlock() {
        val text = "// coverage-exempt: r\ninit {\n    val s = \"}\"\n    check(s.isNotEmpty())\n}\nfun g() = 1\n"
        assertEquals(MarkerTarget.Block(2..5), target(text))
    }

    @Test
    fun reportKeyFollowsTheDeclaredPackage() {
        assertEquals("com/a/Foo.kt", reportKey("lib/src/main/kotlin/Foo.kt", "@file:JvmName(\"X\")\npackage com.a\n"))
        assertEquals("com/b/J.java", reportKey("src/main/java/J.java", "package com.b;\nclass J {}\n"))
    }

    @Test
    fun reportKeyWithoutPackageIsTheFileName() {
        assertEquals("Foo.kt", reportKey("lib/src/main/kotlin/a/Foo.kt", "fun f() = 1\n"))
    }
}
