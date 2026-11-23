package com.kelvsyc.internal.coverage.engine

/** A `// coverage-exempt: <reason>` marker on [line] of [file], exempting line [target]. */
data class Exemption(val file: String, val line: Int, val reason: String, val target: Int)

/** A `// coverage-exempt:` marker on [line] of [file] that gives no reason. */
data class InvalidMarker(val file: String, val line: Int)

/**
 * What one source file holds for the check: its valid [exemptions] and the [exemptLines] they exempt, its [invalid]
 * markers, and its declared [packageName] (Kotlin or Java; empty for the default package).
 */
data class ExemptionScan(val exemptions: List<Exemption>, val invalid: List<InvalidMarker>, val packageName: String) {
    val exemptLines: Set<Int> get() = exemptions.mapTo(mutableSetOf()) { it.target }
}

private val MARKER = Regex("""//\s*coverage-exempt:(.*)""")
private val PACKAGE = Regex("""^\s*package\s+([A-Za-z0-9_.`]+)""", RegexOption.MULTILINE)
private val ANNOTATIONS_ONLY = Regex("""^\s*(@[\w.:]+(\([^()]*\))?\s*)+(//.*)?$""")

/**
 * Scans [text] of [file] for exemption markers and its package. A marker after code exempts its own line; a marker
 * alone on its line exempts the next line of code, skipping blank lines, comments and lines holding only annotations.
 */
fun scanExemptions(file: String, text: String): ExemptionScan {
    val lines = text.lines()
    val exemptions = mutableListOf<Exemption>()
    val invalid = mutableListOf<InvalidMarker>()
    lines.forEachIndexed { index, line ->
        val marker = MARKER.find(line) ?: return@forEachIndexed
        val reason = marker.groupValues[1].trim()
        if (reason.isEmpty()) {
            invalid.add(InvalidMarker(file, index + 1))
        } else {
            val standalone = line.substring(0, marker.range.first).isBlank()
            val target = if (standalone) nextCodeLine(lines, index + 1) else index + 1
            exemptions.add(Exemption(file, index + 1, reason, target ?: index + 1))
        }
    }
    val packageName = PACKAGE.find(text)?.groupValues?.get(1)?.replace("`", "").orEmpty()
    return ExemptionScan(exemptions, invalid, packageName)
}

/** The 1-based number of the first line of code from [start] (0-based) of [lines], or null if none follows. */
private fun nextCodeLine(lines: List<String>, start: Int): Int? {
    var inBlockComment = false
    for (index in start until lines.size) {
        val trimmed = lines[index].trim()
        when {
            inBlockComment -> if ("*/" in trimmed) inBlockComment = false
            trimmed.startsWith("/*") -> if ("*/" !in trimmed) inBlockComment = true
            trimmed.isEmpty() || trimmed.startsWith("//") || ANNOTATIONS_ONLY.matches(trimmed) -> Unit
            else -> return index + 1
        }
    }
    return null
}
