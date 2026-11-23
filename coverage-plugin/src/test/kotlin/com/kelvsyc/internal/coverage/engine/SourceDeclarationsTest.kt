package com.kelvsyc.internal.coverage.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceDeclarationsTest {
    private fun scan(text: String) = scanSourceDeclarations("src/com/a/Foo.kt", text.trimIndent())

    private fun List<SourceDeclaration>.named(name: String) = single { it.name == name }

    @Test
    fun blockBodyRunsFromAnnotationsToTheClosingBrace() {
        val found = scan(
            """
            /**
             * KDoc.
             */
            @Suppress(
                "x",
            )
            fun f(
                a: Int = 1,
            ): Int {
                // }
                return a
            }

            fun g() = 1
            """,
        )
        assertEquals(SourceDeclaration(SourceKind.FUNCTION, "f", "FooKt", 7, 4..12), found.named("f"))
        assertEquals(14..14, found.named("g").extent)
    }

    @Test
    fun expressionBodyAndAccessorsRunToTheirLastContinuationLine() {
        val found = scan(
            """
            fun f() =
                listOf(1)
                    .size

            // between
            val x: Int
                get() = 1

            val s = ""${'"'}
            fun not() {
            ""${'"'}
            """,
        )
        assertEquals(1..3, found.named("f").extent)
        assertEquals(6..7, found.named("x").extent)
        assertEquals(9..11, found.named("s").extent)
        assertTrue(found.none { it.name == "not" })
    }

    @Test
    fun membersAreOwnedByTheirClassAndLocalsAreSkipped() {
        val found = scan(
            """
            class A(
                val p: Int,
            ) : Base() {
                constructor() : this(0) {
                    println()
                }

                class B {
                    fun m() {
                        fun local() = Unit
                        val v = object {
                            fun inner() = Unit
                        }
                    }
                }
            }
            """,
        )
        val a = found.named("A")
        assertEquals(1..3, a.header)
        assertEquals(1..16, a.extent)
        assertEquals("A", found.named("p").owner)
        assertEquals(SourceDeclaration(SourceKind.CONSTRUCTOR, "<init>", "A", 4, 4..6), found.named("<init>"))
        assertEquals(SourceDeclaration(SourceKind.FUNCTION, "m", "A\$B", 9, 9..14), found.named("m"))
        assertTrue(found.none { it.name in setOf("local", "v", "inner") })
    }

    @Test
    fun maskingBlanksCommentsAndStringContents() {
        val masked = maskLines(listOf("val a = \"{\" // }", "/* { /* } */", "} */ x", "\"\"\"", "{", "\"\"\" + y"))
        assertEquals(listOf("val a = \" \"     ", "            ", "     x", "\"\"\"", " ", "\"\"\" + y"), masked.code)
        assertEquals(listOf(false, false, false, false, true, true), masked.continued)
    }
}
