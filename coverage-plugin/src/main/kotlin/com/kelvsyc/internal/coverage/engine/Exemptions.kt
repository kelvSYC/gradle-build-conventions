package com.kelvsyc.internal.coverage.engine

/** A `// coverage-exempt: <reason>` marker on [line]; [standalone] when only whitespace precedes the `//`. */
data class Exemption(val file: String, val line: Int, val reason: String, val standalone: Boolean)

/** A `// coverage-exempt:` marker on [line] that gives no reason. */
data class InvalidMarker(val file: String, val line: Int)

private val MARKER = Regex("""//\s*coverage-exempt:\s*(\S.*)""")
private val EMPTY_MARKER = Regex("""//\s*coverage-exempt:\s*$""")

/** Scans [text] of [file] for exemption markers, returning the valid ones and the reasonless ones. */
fun scanExemptions(file: String, text: String): Pair<List<Exemption>, List<InvalidMarker>> {
    val exemptions = mutableListOf<Exemption>()
    val invalid = mutableListOf<InvalidMarker>()
    text.lines().forEachIndexed { index, line ->
        val number = index + 1
        val valid = MARKER.find(line)
        if (valid != null) {
            val standalone = line.substring(0, valid.range.first).isBlank()
            exemptions.add(Exemption(file, number, valid.groupValues[1].trim(), standalone))
        } else if (EMPTY_MARKER.containsMatchIn(line)) {
            invalid.add(InvalidMarker(file, number))
        }
    }
    return exemptions to invalid
}
