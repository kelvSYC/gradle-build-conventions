package com.kelvsyc.internal.coverage.engine

/**
 * What a `// coverage-exempt:` marker exempts.
 */
sealed interface MarkerTarget {
    /** The [line] only. */
    data class Line(val line: Int) : MarkerTarget

    /** The [line] and every declaration whose code starts on it, such as a property's accessors. */
    data class Property(val line: Int) : MarkerTarget

    /** The function or constructor declared from [line]: the next declaration from it, and the code inside it. */
    data class Function(val line: Int) : MarkerTarget

    /** The class, interface or object [name] declared on [line], with its members and nested classes. */
    data class Class(val line: Int, val name: String) : MarkerTarget

    /** The [lines] of a block, such as an `init` block. */
    data class Block(val lines: IntRange) : MarkerTarget
}

/**
 * A `// coverage-exempt: <reason>` marker on [line]; [standalone] when only whitespace precedes the `//`.
 *
 * A trailing marker exempts its own line ([MarkerTarget.Property]). A standalone marker exempts what the next line of
 * code (skipping blank lines, comments and annotations) starts: a class, interface or object, a function or
 * constructor, or an `init` block; for anything else, including `val` and `var`, that line alone.
 */
data class Exemption(
    val file: String,
    val line: Int,
    val reason: String,
    val standalone: Boolean,
    val target: MarkerTarget = if (standalone) MarkerTarget.Function(line + 1) else MarkerTarget.Property(line),
)

/** A `// coverage-exempt:` marker on [line] that gives no reason. */
data class InvalidMarker(val file: String, val line: Int)

private val MARKER = Regex("""//\s*coverage-exempt:\s*(\S.*)""")
private val EMPTY_MARKER = Regex("""//\s*coverage-exempt:\s*$""")
private val MODIFIERS = listOf(
    "public", "private", "protected", "internal", "open", "abstract", "final", "override", "suspend", "inline",
    "noinline", "crossinline", "operator", "infix", "tailrec", "external", "const", "lateinit", "data", "sealed",
    "enum", "annotation", "inner", "value", "companion", "expect", "actual", "static", "synchronized", "default",
).joinToString("|")
private val DECLARATION = Regex(
    """^\s*(?:(?:$MODIFIERS)\s+)*(fun\s+interface|fun|val|var|class|interface|object|constructor|init|typealias)\b""" +
        """(?:\s+(?:<[^>]*>\s*)?`?([A-Za-z_][A-Za-z0-9_]*)`?)?""",
)
private val CLASS_KEYWORDS = setOf("class", "interface", "object", "fun interface")
/**
 * Keywords whose marker exempts just the line: a `val` or `var` may be local, and a function's code can start on its
 * first statement's line, so exempting declarations starting there could exempt the enclosing function.
 */
private val LINE_KEYWORDS = setOf("val", "var", "typealias")
private const val COMPANION_NAME = "Companion"

/** Scans [text] of [file] for exemption markers, returning the valid ones and the reasonless ones. */
fun scanExemptions(file: String, text: String): Pair<List<Exemption>, List<InvalidMarker>> {
    val lines = text.lines()
    val exemptions = mutableListOf<Exemption>()
    val invalid = mutableListOf<InvalidMarker>()
    lines.forEachIndexed { index, line ->
        val number = index + 1
        val valid = MARKER.find(line)
        if (valid != null) {
            val standalone = line.substring(0, valid.range.first).isBlank()
            val reason = valid.groupValues[1].trim()
            val target = if (standalone) standaloneTarget(lines, index) else MarkerTarget.Property(number)
            exemptions.add(Exemption(file, number, reason, standalone, target))
        } else if (EMPTY_MARKER.containsMatchIn(line)) {
            invalid.add(InvalidMarker(file, number))
        }
    }
    return exemptions to invalid
}

/** The target of a standalone marker at [markerIndex] (0-based) of [lines]. */
private fun standaloneTarget(lines: List<String>, markerIndex: Int): MarkerTarget {
    var index = markerIndex + 1
    var inBlockComment = false
    while (index < lines.size) {
        val trimmed = lines[index].trim()
        when {
            inBlockComment -> if ("*/" in trimmed) inBlockComment = false
            trimmed.startsWith("/*") -> if ("*/" !in trimmed) inBlockComment = true
            trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("@") -> Unit
            else -> return targetOf(lines, index)
        }
        index++
    }
    return MarkerTarget.Line(markerIndex + 1)
}

private fun targetOf(lines: List<String>, index: Int): MarkerTarget {
    val number = index + 1
    val match = DECLARATION.find(lines[index]) ?: return MarkerTarget.Line(number)
    val keyword = match.groupValues[1].replace(Regex("""\s+"""), " ")
    val name = match.groupValues[2]
    return when {
        keyword in CLASS_KEYWORDS -> MarkerTarget.Class(number, name.ifEmpty { COMPANION_NAME })
        keyword in LINE_KEYWORDS -> MarkerTarget.Line(number)
        keyword == "init" -> MarkerTarget.Block(number..blockEnd(lines, index))
        else -> MarkerTarget.Function(number)
    }
}

/**
 * The 1-based last line of the brace block opened on or after [start] (0-based); braces in string and character
 * literals and line comments are skipped.
 */
private fun blockEnd(lines: List<String>, start: Int): Int {
    var depth = 0
    var opened = false
    for (index in start until lines.size) {
        var quote: Char? = null
        var i = 0
        val line = lines[index]
        while (i < line.length) {
            val c = line[i]
            when {
                quote != null -> if (c == '\\') i++ else if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '/' && line.getOrNull(i + 1) == '/' -> break
                c == '{' -> { depth++; opened = true }
                c == '}' -> depth--
            }
            i++
        }
        if (opened && depth <= 0) return index + 1
    }
    return lines.size
}

private val PACKAGE = Regex("""^\s*package\s+([A-Za-z0-9_.`]+)""", RegexOption.MULTILINE)

/**
 * The key [JacocoXmlReport.lines] and [Declaration.sourceFile] use for the source file at [path] with [text]: its
 * declared package as a path, then its file name. A file without a package declaration is in the default package.
 */
fun reportKey(path: String, text: String): String {
    val fileName = path.substringAfterLast('/')
    val packageName = PACKAGE.find(text)?.groupValues?.get(1)?.replace("`", "")
    return if (packageName.isNullOrEmpty()) fileName else packageName.replace('.', '/') + "/" + fileName
}
