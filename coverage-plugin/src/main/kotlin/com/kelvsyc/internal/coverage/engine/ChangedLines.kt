package com.kelvsyc.internal.coverage.engine

/**
 * The new-side line numbers changed by a diff, per repository-relative file path (separator `/`).
 */
data class ChangedLines(val byFile: Map<String, Set<Int>>) {
    companion object {
        private val HUNK_HEADER = Regex("""^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@""")
        private const val NEW_FILE_PREFIX = "+++ b/"
        private const val RENAME_TO_PREFIX = "rename to "
        private const val NO_NEWLINE_MARKER = "\\"

        /**
         * Parses a unified diff and adds each [untracked] file (path to line count) as fully changed. File headers
         * (`+++ b/`, `rename to `) are recognised only between a `diff --git` line and that file's first hunk, so added
         * lines that look like headers count as added lines.
         */
        fun parseUnifiedDiff(diff: String, untracked: Map<String, Int>): ChangedLines {
            val result = linkedMapOf<String, MutableSet<Int>>()
            var current: MutableSet<Int>? = null
            var nextLine = 0
            var inHeader = false
            for (line in diff.lines()) {
                val hunk = HUNK_HEADER.find(line)
                when {
                    line.startsWith("diff --git ") -> {
                        current = null
                        inHeader = true
                    }
                    inHeader && line.startsWith(RENAME_TO_PREFIX) ->
                        current = result.getOrPut(line.removePrefix(RENAME_TO_PREFIX)) { linkedSetOf() }
                    inHeader && line.startsWith(NEW_FILE_PREFIX) ->
                        current = result.getOrPut(line.removePrefix(NEW_FILE_PREFIX)) { linkedSetOf() }
                    hunk != null -> {
                        nextLine = hunk.groupValues[1].toInt()
                        inHeader = false
                    }
                    current == null || line.startsWith(NO_NEWLINE_MARKER) -> Unit
                    line.startsWith("+") -> current.add(nextLine++)
                    line.startsWith(" ") -> nextLine++
                }
            }
            for ((path, count) in untracked) {
                result.getOrPut(path) { linkedSetOf() }.addAll(1..count)
            }
            return ChangedLines(result)
        }
    }
}
