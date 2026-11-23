package com.kelvsyc.internal.coverage.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What markers exempt, end to end: source text through [scanExemptions], and declarations shaped as the compiler emits
 * them, through [evaluate]. Every line of the file is changed and no method is covered, so the uncovered public API
 * lists exactly the declarations no marker exempts.
 */
class MarkerTargetingTest {
    private val file = "src/A.kt"
    private val key = "com/a/A.kt"

    private fun d(className: String, name: String, lines: IntRange, publicApi: Boolean = true) =
        Declaration("com/a/$className", name, "()V", key, lines, publicApi, false)

    /** The uncovered public API, as `Class.name`, for [body] (after a `package com.a` line and a blank line). */
    private fun uncovered(body: String, vararg declarations: Declaration): List<String> =
        result(body, *declarations).uncoveredPublicApi.map { it.className.removePrefix("com/a/") + "." + it.name }

    private fun result(
        body: String,
        vararg declarations: Declaration,
        lines: Map<Int, LineCoverage> = emptyMap(),
    ): CoverageResult {
        val text = "package com.a\n\n" + body.trimIndent() + "\n"
        val (exemptions, invalid) = scanExemptions(file, text)
        val report = JacocoXmlReport(
            mapOf(key to lines),
            declarations.map { MethodCoverage(it.className, it.name, it.desc, false) },
        )
        return evaluate(
            ChangedLines(mapOf(file to (1..text.lines().size).toSet())),
            report, declarations.toList(), exemptions, invalid,
            SourceRoots(listOf("src"), emptyList()), RuleSettings(), mapOf(file to key),
        )
    }

    // Annotations ----------------------------------------------------------------------------------------------------

    @Test
    fun sameLineAnnotationFunctionIsTheTarget() {
        val body = """
            // coverage-exempt: platform glue
            @JvmStatic fun foo() = 1
            fun bar() = 2
        """
        assertEquals(listOf("AKt.bar"), uncovered(body, d("AKt", "foo", 4..4), d("AKt", "bar", 5..5)))
    }

    @Test
    fun sameLineAnnotationClassIsTheTarget() {
        val body = """
            // coverage-exempt: platform glue
            @Suppress("x") class Foo {
                fun a() = 1
            }
            class Bar {
                fun b() = 2
            }
        """
        val declarations = arrayOf(
            d("Foo", "<init>", 4..4), d("Foo", "a", 5..5), d("Bar", "<init>", 7..7), d("Bar", "b", 8..8),
        )
        assertEquals(listOf("Bar.<init>", "Bar.b"), uncovered(body, *declarations))
    }

    @Test
    fun annotationOnlyLinesAreSkipped() {
        val body = """
            // coverage-exempt: platform glue
            @JvmStatic
            @Deprecated("x")
            fun foo() = 1
            fun bar() = 2
        """
        assertEquals(listOf("AKt.bar"), uncovered(body, d("AKt", "foo", 6..6), d("AKt", "bar", 7..7)))
    }

    @Test
    fun multiLineAnnotationIsSkippedToTheDeclaration() {
        val body = """
            // coverage-exempt: platform glue
            @Suppress(
                "UNUSED",
            )
            fun foo() = 1
            fun bar() = 2
        """
        assertEquals(listOf("AKt.bar"), uncovered(body, d("AKt", "foo", 7..7), d("AKt", "bar", 8..8)))
    }

    // Functions ------------------------------------------------------------------------------------------------------

    @Test
    fun markerAboveAbstractFunctionDoesNotDriftToTheNextFunction() {
        val body = """
            abstract class Base {
                // coverage-exempt: no body
                abstract fun x(): Int

                fun y(): Int = 2
            }
        """
        assertEquals(listOf("Base.<init>", "Base.y"), uncovered(body, d("Base", "<init>", 3..3), d("Base", "y", 7..7)))
    }

    @Test
    fun markerAboveInternalFunctionExemptsItsMangledMethod() {
        val body = """
            // coverage-exempt: platform glue
            internal fun helper(flag: Boolean): Int {
                return if (flag) 1 else 2
            }
        """
        val result = result(
            body,
            d("AKt", "helper\$main", 5..5, publicApi = false),
            lines = mapOf(5 to LineCoverage(0, 1, 2, 0)),
        )
        assertEquals(0, result.branchesTotal)
    }

    @Test
    fun markerAboveFunctionExemptsValueClassMangledAndDefaultBridges() {
        val body = """
            // coverage-exempt: platform glue
            fun wrap(id: Id, label: String = "x"): Int {
                return id.value
            }
            fun other(): Int = 3
        """
        val declarations = arrayOf(
            d("AKt", "wrap-3Xk2Ab", 5..5), d("AKt", "wrap-3Xk2Ab\$default", 4..4), d("AKt", "other", 7..7),
        )
        assertEquals(listOf("AKt.other"), uncovered(body, *declarations))
    }

    @Test
    fun markerAboveOverloadExemptsOnlyThatOverload() {
        val body = """
            // coverage-exempt: platform glue
            fun f(a: Int): Int = a
            fun f(b: String): Int = b.length
        """
        assertEquals(listOf("AKt.f"), uncovered(body, d("AKt", "f", 4..4), d("AKt", "f", 5..5)).distinct())
        assertEquals(5, result(body, d("AKt", "f", 4..4), d("AKt", "f", 5..5)).uncoveredPublicApi.single().lines.first)
    }

    @Test
    fun trailingMarkerOnFunctionLineExemptsTheFunction() {
        val body = """
            fun g(): Int = 1 // coverage-exempt: platform glue
            fun h(): Int = 2
        """
        assertEquals(listOf("AKt.h"), uncovered(body, d("AKt", "g", 3..3), d("AKt", "h", 4..4)))
    }

    // Properties -----------------------------------------------------------------------------------------------------

    @Test
    fun markerAbovePropertyExemptsItsGetterOnALaterLine() {
        val body = """
            class C {
                // coverage-exempt: platform
                val name: String
                    get() = System.getenv("X")
            }
        """
        assertEquals(listOf("C.<init>"), uncovered(body, d("C", "<init>", 3..3), d("C", "getName", 6..6)))
    }

    @Test
    fun markerAboveLazyPropertyExemptsItsGetter() {
        val body = """
            class C {
                // coverage-exempt: platform
                val x by lazy {
                    compute()
                }
            }
        """
        val declarations = arrayOf(
            d("C", "<init>", 3..5), d("C", "getX", 5..5), d("C", "x_delegate\$lambda\$0", 6..6, publicApi = false),
        )
        assertEquals(listOf("C.<init>"), uncovered(body, *declarations))
    }

    @Test
    fun markerAboveBooleanIsPropertyExemptsItsGetter() {
        val body = """
            class C {
                // coverage-exempt: platform
                var isReady: Boolean = false
            }
        """
        val declarations = arrayOf(d("C", "<init>", 3..5), d("C", "isReady", 5..5), d("C", "setReady", 5..5))
        assertEquals(listOf("C.<init>"), uncovered(body, *declarations))
    }

    @Test
    fun trailingMarkerOnFirstStatementDoesNotExemptTheEnclosingFunction() {
        val body = """
            fun f(y: Int?): Int {
                val x = y ?: error("") // coverage-exempt: unreachable
                return x
            }
        """
        assertEquals(listOf("AKt.f"), uncovered(body, d("AKt", "f", 4..5)))
    }

    @Test
    fun localValMarkerDoesNotExemptALaterPropertyOfTheSameName() {
        val body = """
            fun f(): Int {
                // coverage-exempt: unreachable
                val name = compute()
                return name.length
            }
            class C {
                val name: String = "x"
            }
        """
        val declarations = arrayOf(d("AKt", "f", 5..6), d("C", "<init>", 9..9), d("C", "getName", 9..9))
        assertEquals(listOf("AKt.f", "C.<init>", "C.getName"), uncovered(body, *declarations))
    }

    // Classes --------------------------------------------------------------------------------------------------------

    @Test
    fun companionMarkerExemptsOnlyItsOwnCompanion() {
        val body = """
            class A1 {
                // coverage-exempt: factory
                companion object {
                    fun make() = 1
                }
            }
            class B1 {
                companion object {
                    fun make() = 2
                }
            }
        """
        val declarations = arrayOf(d("A1\$Companion", "make", 6..6), d("B1\$Companion", "make", 11..11))
        assertEquals(listOf("B1\$Companion.make"), uncovered(body, *declarations))
    }

    @Test
    fun nestedClassMarkerExemptsOnlyThatNestedClass() {
        val body = """
            class P(
                val a: Int,
            ) {
                // coverage-exempt: glue
                class Inner {
                    fun a() = 1
                }
            }
            class Q {
                class Inner {
                    fun b() = 2
                }
            }
        """
        val declarations = arrayOf(d("P\$Inner", "a", 8..8), d("Q\$Inner", "b", 13..13))
        assertEquals(listOf("Q\$Inner.b"), uncovered(body, *declarations))
    }
}
