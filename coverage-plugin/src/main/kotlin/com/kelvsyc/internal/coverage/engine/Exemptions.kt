package com.kelvsyc.internal.coverage.engine

/** A `// coverage-exempt: <reason>` marker on [line] of [file], exempting line [target]. */
data class Exemption(val file: String, val line: Int, val reason: String, val target: Int)

/** A `// coverage-exempt:` marker on [line] of [file] that gives no reason. */
data class InvalidMarker(val file: String, val line: Int)

/** The markers of one file: the valid [exemptions], the [exemptLines] they exempt, and the [invalid] markers. */
data class ExemptionScan(val exemptLines: Set<Int>, val exemptions: List<Exemption>, val invalid: List<InvalidMarker>)

private val MARKER = Regex("""//\s*coverage-exempt:(.*)""")
private val PACKAGE = Regex("""^\s*package\s+([A-Za-z0-9_.`]+)""", RegexOption.MULTILINE)

/** The package [text] declares (Kotlin or Java), empty for the default package. */
fun packageName(text: String): String = PACKAGE.find(text)?.groupValues?.get(1)?.replace("`", "").orEmpty()

/**
 * Scans [text] of [file] for exemption markers. A marker after code exempts its own line; a marker alone on its line
 * exempts the next line of code, skipping blank lines, comments and lines holding only annotations.
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
    return ExemptionScan(exemptions.map { it.target }.toSet(), exemptions, invalid)
}

/** The 1-based number of the first line of code from [start] (0-based) of [lines], or null if none follows. */
private fun nextCodeLine(lines: List<String>, start: Int): Int? {
    var inBlockComment = false
    var openParentheses = 0
    for (index in start until lines.size) {
        var code = lines[index]
        if (openParentheses > 0) {
            // Inside a multi-line annotation's arguments: skip to its closing parenthesis.
            val (end, depth) = skipParentheses(code, 0, openParentheses)
            openParentheses = depth
            if (depth > 0) continue
            code = code.substring(end)
        } else {
            val trimmed = code.trim()
            when {
                inBlockComment -> {
                    if ("*/" in trimmed) inBlockComment = false
                    continue
                }
                trimmed.startsWith("/*") -> {
                    if ("*/" !in trimmed) inBlockComment = true
                    continue
                }
                trimmed.isEmpty() || trimmed.startsWith("//") -> continue
            }
        }
        val (rest, open) = stripAnnotations(code)
        openParentheses = open
        val remaining = rest.trim()
        if (remaining.isNotEmpty() && !remaining.startsWith("//")) return index + 1
    }
    return null
}

/**
 * Leading annotations removed from [code]: the rest of the line, and the depth of parentheses an annotation left open
 * (an annotation whose arguments continue on later lines).
 */
private fun stripAnnotations(code: String): Pair<String, Int> {
    var i = code.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) code.length else it }
    while (i < code.length && code[i] == '@') {
        i++
        while (i < code.length && (code[i].isLetterOrDigit() || code[i] in "_.:")) i++
        if (i < code.length && code[i] == '(') {
            val (end, depth) = skipParentheses(code, i, 0)
            if (depth > 0) return "" to depth
            i = end
        }
        while (i < code.length && code[i].isWhitespace()) i++
    }
    return code.substring(i) to 0
}

/**
 * Scans [code] from [start] with [depth] parentheses already open, skipping string literals, until the depth returns
 * to zero: the index after the closing parenthesis and zero, or the line's length and the depth still open.
 */
private fun skipParentheses(code: String, start: Int, depth: Int): Pair<Int, Int> {
    var open = depth
    var quote: Char? = null
    var i = start
    while (i < code.length) {
        val c = code[i]
        when {
            quote != null -> if (c == '\\') i++ else if (c == quote) quote = null
            c == '"' || c == '\'' -> quote = c
            c == '(' -> open++
            c == ')' -> {
                open--
                if (open == 0) return i + 1 to 0
            }
        }
        i++
    }
    return code.length to open
}
