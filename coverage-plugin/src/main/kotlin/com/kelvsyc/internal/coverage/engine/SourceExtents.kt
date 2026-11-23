package com.kelvsyc.internal.coverage.engine

/**
 * Source lines with comments and the contents of string literals replaced by spaces, so that only code remains.
 *
 * [code] keeps every line's length and the quotes that delimit string literals. [continued] is true for a line that
 * starts inside a multi-line raw string, which therefore continues the code of the line before it.
 */
internal class MaskedLines(val code: List<String>, val continued: List<Boolean>) {
    /** True when line [index] (0-based) holds code. */
    fun hasCode(index: Int): Boolean = continued[index] || code[index].isNotBlank()
}

private const val RAW_QUOTE = "\"\"\""

/** Masks [lines]; Kotlin block comments nest. */
internal fun maskLines(lines: List<String>): MaskedLines {
    val code = ArrayList<String>(lines.size)
    val continued = ArrayList<Boolean>(lines.size)
    var commentDepth = 0
    var inRawString = false
    for (line in lines) {
        continued.add(inRawString)
        val out = StringBuilder(line)
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            var step = 1
            when {
                commentDepth > 0 -> {
                    when {
                        line.startsWith("*/", i) -> { commentDepth--; step = 2 }
                        line.startsWith("/*", i) -> { commentDepth++; step = 2 }
                    }
                    repeat(minOf(step, line.length - i)) { out[i + it] = ' ' }
                }
                inRawString -> if (line.startsWith(RAW_QUOTE, i)) {
                    inRawString = false
                    step = RAW_QUOTE.length
                } else {
                    out[i] = ' '
                }
                quote != null -> when (c) {
                    '\\' -> {
                        step = minOf(2, line.length - i)
                        repeat(step) { out[i + it] = ' ' }
                    }
                    quote -> quote = null
                    else -> out[i] = ' '
                }
                line.startsWith("//", i) -> {
                    for (j in i until line.length) out[j] = ' '
                    step = line.length - i
                }
                line.startsWith("/*", i) -> {
                    commentDepth++
                    out[i] = ' '
                    out[i + 1] = ' '
                    step = 2
                }
                line.startsWith(RAW_QUOTE, i) -> { inRawString = true; step = RAW_QUOTE.length }
                c == '"' || c == '\'' -> quote = c
            }
            i += step
        }
        code.add(out.toString())
    }
    return MaskedLines(code, continued)
}

/** What a [SourceDeclaration] declares. */
internal enum class SourceKind { FUNCTION, PROPERTY, CONSTRUCTOR, CLASS }

/**
 * A declaration in a Kotlin source file, as its text lays it out.
 *
 * [owner] is the enclosing class path (`Outer$Inner`) or the file facade (`FooKt`); for a [SourceKind.CLASS] it is the
 * class's own path. [line] holds its keyword. [extent] runs from its first annotation (excluding any KDoc) to its
 * last line: the closing brace of a block body, or the last line of an expression body, initializer or accessor.
 * [header] is a class's keyword line through the line that opens its body, which holds the primary constructor.
 */
internal data class SourceDeclaration(
    val kind: SourceKind,
    val name: String,
    val owner: String,
    val line: Int,
    val extent: IntRange,
    val header: IntRange = IntRange.EMPTY,
)

/**
 * The member and top-level declarations of the Kotlin source [text] of [file]: classes, functions, properties and
 * secondary constructors that the compiler emits under their own names. Declarations inside functions are skipped.
 */
internal fun scanSourceDeclarations(file: String, text: String): List<SourceDeclaration> {
    val lines = text.lines()
    val masked = maskLines(lines)
    val facade = facadeName(file, text)
    val result = mutableListOf<SourceDeclaration>()
    var annotationStart: Int? = null
    var openParentheses = 0
    for (index in lines.indices) {
        if (masked.continued[index]) {
            annotationStart = null
            continue
        }
        var code = masked.code[index]
        if (openParentheses > 0) {
            // Inside a multi-line annotation's arguments: resume after its closing parenthesis.
            val (end, depth) = skipParentheses(code, 0, openParentheses)
            openParentheses = depth
            if (depth > 0) continue
            code = code.substring(end)
        } else if (code.isBlank()) {
            continue
        }
        val (rest, open) = stripAnnotations(code)
        openParentheses = open
        if (rest.isBlank()) {
            annotationStart = annotationStart ?: index
            continue
        }
        val first = annotationStart ?: index
        annotationStart = null
        val start = declarationStart(rest) ?: continue
        val name = start.name ?: continue
        val scope = enclosingClasses(lines, index)
        if (scope.local) continue
        val owner = scope.path.joinToString("$").ifEmpty { facade }
        val number = index + 1
        val extent = first + 1..declarationEnd(masked, index)
        when (start.keyword) {
            in CLASS_KEYWORDS -> {
                val path = (scope.path + name).joinToString("$")
                val header = number..headerEnd(masked.code, index, extent.last)
                result.add(SourceDeclaration(SourceKind.CLASS, name, path, number, extent, header))
            }
            "constructor" -> result.add(SourceDeclaration(SourceKind.CONSTRUCTOR, name, owner, number, extent))
            in FUNCTION_KEYWORDS -> result.add(SourceDeclaration(SourceKind.FUNCTION, name, owner, number, extent))
            in PROPERTY_KEYWORDS -> result.add(SourceDeclaration(SourceKind.PROPERTY, name, owner, number, extent))
        }
    }
    return result
}

/**
 * The 1-based last line of the declaration starting on line [index] (0-based): the declaration continues while
 * brackets it opened are unclosed or the next code line is indented deeper than it, so it ends before the next code
 * line at its own or a lower indentation. Trailing blank and comment lines are not part of it.
 */
private fun declarationEnd(masked: MaskedLines, index: Int): Int {
    val indent = indentOf(masked.code[index])
    var depth = 0
    var last = index
    for (i in index until masked.code.size) {
        if (!masked.hasCode(i)) continue
        val code = masked.code[i]
        if (i > index && depth <= 0 && !masked.continued[i] && indentOf(code) <= indent) break
        for (c in code) {
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
        }
        last = i
    }
    return last + 1
}

/** The 1-based line, from [index] (0-based) through [end], that opens a class's body, or [end] when it has none. */
private fun headerEnd(code: List<String>, index: Int, end: Int): Int {
    var depth = 0
    for (i in index until end) {
        for (c in code[i]) {
            when (c) {
                '(', '[' -> depth++
                ')', ']' -> depth--
                '{' -> if (depth <= 0) return i + 1
            }
        }
    }
    return end
}
