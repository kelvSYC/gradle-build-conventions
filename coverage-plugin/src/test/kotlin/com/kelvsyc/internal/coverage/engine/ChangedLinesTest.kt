package com.kelvsyc.internal.coverage.engine

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class ChangedLinesTest {
    @Test
    fun addedAndModifiedLinesAreCounted() {
        val diff = """
            diff --git a/src/A.kt b/src/A.kt
            index 1..2 100644
            --- a/src/A.kt
            +++ b/src/A.kt
            @@ -10,2 +10,3 @@
             ctx
            -old
            +new1
            +new2
        """.trimIndent()
        assertEquals(setOf(11, 12), ChangedLines.parseUnifiedDiff(diff, emptyMap()).byFile.getValue("src/A.kt"))
    }

    @Test
    fun deletionsOnlyYieldNoLines() {
        val diff = """
            diff --git a/src/A.kt b/src/A.kt
            --- a/src/A.kt
            +++ b/src/A.kt
            @@ -5,2 +4,1 @@
             ctx
            -gone
        """.trimIndent()
        assertEquals(emptySet<Int>(), ChangedLines.parseUnifiedDiff(diff, emptyMap()).byFile["src/A.kt"].orEmpty())
    }

    @Test
    fun renamedFileUsesNewPath() {
        val diff = """
            diff --git a/a/X.kt b/b/Y.kt
            similarity index 90%
            rename from a/X.kt
            rename to b/Y.kt
            --- a/a/X.kt
            +++ b/b/Y.kt
            @@ -1,1 +1,1 @@
            -old
            +new
        """.trimIndent()
        assertEquals(mapOf("b/Y.kt" to setOf(1)), ChangedLines.parseUnifiedDiff(diff, emptyMap()).byFile)
    }

    @Test
    fun untrackedFilesAreFullyChanged() {
        val changed = ChangedLines.parseUnifiedDiff("", mapOf("src/N.kt" to 3))
        assertEquals(setOf(1, 2, 3), changed.byFile.getValue("src/N.kt"))
    }

    @Test
    fun noNewlineMarkerIgnored() {
        val diff = """
            diff --git a/src/A.kt b/src/A.kt
            --- a/src/A.kt
            +++ b/src/A.kt
            @@ -1,1 +1,2 @@
             a
            +b
            \ No newline at end of file
            +c
        """.trimIndent()
        assertEquals(setOf(2, 3), ChangedLines.parseUnifiedDiff(diff, emptyMap()).byFile.getValue("src/A.kt"))
    }
}
