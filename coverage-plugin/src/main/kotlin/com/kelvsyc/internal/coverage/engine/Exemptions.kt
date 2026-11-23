package com.kelvsyc.internal.coverage.engine

/**
 * What a `// coverage-exempt:` marker exempts, in the marker's file.
 */
sealed interface MarkerTarget {
    /** The [line] only. */
    data class Line(val line: Int) : MarkerTarget

    /**
     * The function [name] declared on [line] in the class [owner] (its internal name after the package, such as
     * `Outer$Inner`, or the file facade such as `FooKt`): the owner's methods named [name] (ignoring mangling suffixes
     * after `$` or `-`) whose code starts from [line] and before [until], the next declaration of a function of that
     * name, and the classes generated inside the function (`Owner$name$1`).
     */
    data class Function(val line: Int, val name: String, val owner: String, val until: Int = Int.MAX_VALUE) :
        MarkerTarget

    /**
     * The secondary constructor of class [owner] declared over [lines]: the owner's constructors, other than the
     * primary one (which runs the class [header] line), that run those lines, and only those lines.
     */
    data class Constructor(val lines: IntRange, val owner: String, val header: Int) : MarkerTarget

    /**
     * The property [name] declared on [line] in the class [owner] (as for [Function]): that line, and the owner's
     * accessors and delegate code for it starting from [line] and before [until], the next declaration of a property
     * of that name.
     */
    data class Property(val line: Int, val name: String, val owner: String, val until: Int = Int.MAX_VALUE) :
        MarkerTarget

    /**
     * The class, interface or object declared on [line], with its members and nested classes. [path] is its name
     * nested in its enclosing classes, joined with `$` (`Outer$Companion`). A [local] class, declared inside a
     * function, has its simple name as [path]: it matches classes of [owner] (`Owner$function$Name`) with that name
     * whose code lies within the function's lines, [span].
     */
    data class Class(
        val line: Int,
        val path: String,
        val local: Boolean = false,
        val owner: String = "",
        val span: IntRange = IntRange.EMPTY,
    ) : MarkerTarget

    /** The [lines] of a block, such as an `init` block. */
    data class Block(val lines: IntRange) : MarkerTarget
}

/**
 * A `// coverage-exempt: <reason>` marker on [line]; [standalone] when only whitespace precedes the `//`.
 *
 * [target] is what the marker exempts: for a standalone marker, what the next line of code starts (skipping blank
 * lines, comments and annotations), for a trailing marker, what its own line starts. A class, interface or object, a
 * function or constructor, a property or an `init` block is exempted as a whole, matched within the class or file
 * facade that declares it; any other line, including a local `val`, `var` or `fun` declared inside a function, only
 * that line.
 */
data class Exemption(
    val file: String,
    val line: Int,
    val reason: String,
    val standalone: Boolean,
    val target: MarkerTarget = MarkerTarget.Line(if (standalone) line + 1 else line),
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
private const val ANNOTATION = """@[\w.:]+(?:\([^)]*\))?"""
private val DECLARATION = Regex(
    """^\s*(?:(?:$MODIFIERS|$ANNOTATION)\s+)*""" +
        """(fun\s+interface|fun|val|var|class|interface|object|constructor|init|typealias)\b(.*)$""",
)
private const val RECEIVER = """(?:[\w<>?,.]+\.)?"""
private val FUNCTION_NAME = Regex("""^\s*(?:<[^>]*>\s*)?$RECEIVER`?([A-Za-z_]\w*)`?\s*\(""")
private val PROPERTY_NAME = Regex("""^\s*(?:<[^>]*>\s*)?$RECEIVER`?([A-Za-z_]\w*)`?""")
private val CLASS_NAME = Regex("""^\s*`?([A-Za-z_]\w*)`?""")
internal val CLASS_KEYWORDS = setOf("class", "interface", "object", "fun interface")
internal val FUNCTION_KEYWORDS = setOf("fun")
internal val PROPERTY_KEYWORDS = setOf("val", "var")
private const val COMPANION_NAME = "Companion"
private const val CONSTRUCTOR_NAME = "<init>"

/** A declaration's [keyword] (`fun interface` normalised to one space) and [name] (null if none is found). */
internal data class DeclarationStart(val keyword: String, val name: String?)

internal fun declarationStart(code: String): DeclarationStart? {
    val match = DECLARATION.find(code) ?: return null
    val keyword = match.groupValues[1].replace(Regex("""\s+"""), " ")
    val rest = match.groupValues[2]
    val name = when (keyword) {
        in CLASS_KEYWORDS -> CLASS_NAME.find(rest)?.groupValues?.get(1)
            ?: COMPANION_NAME.takeIf { "companion" in code.substring(0, match.groups[1]!!.range.first) }
        "fun" -> FUNCTION_NAME.find(rest)?.groupValues?.get(1)
        "constructor" -> CONSTRUCTOR_NAME
        in PROPERTY_KEYWORDS -> PROPERTY_NAME.find(rest)?.groupValues?.get(1)
        else -> null
    }
    return DeclarationStart(keyword, name)
}

/** Scans [text] of [file] for exemption markers, returning the valid ones and the reasonless ones. */
fun scanExemptions(file: String, text: String): Pair<List<Exemption>, List<InvalidMarker>> {
    val lines = text.lines()
    val source = Source(lines, facadeName(file, text))
    val exemptions = mutableListOf<Exemption>()
    val invalid = mutableListOf<InvalidMarker>()
    lines.forEachIndexed { index, line ->
        val number = index + 1
        val valid = MARKER.find(line)
        if (valid != null) {
            val code = line.substring(0, valid.range.first)
            val standalone = code.isBlank()
            val reason = valid.groupValues[1].trim()
            val target = if (standalone) standaloneTarget(source, index) else targetOf(source, index, code)
            exemptions.add(Exemption(file, number, reason, standalone, target))
        } else if (EMPTY_MARKER.containsMatchIn(line)) {
            invalid.add(InvalidMarker(file, number))
        }
    }
    return exemptions to invalid
}

/**
 * Leading annotations removed from [code]: the rest of the line, and the depth of parentheses an annotation left open
 * (an annotation whose arguments continue on later lines).
 */
internal fun stripAnnotations(code: String): Pair<String, Int> {
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
internal fun skipParentheses(code: String, start: Int, depth: Int): Pair<Int, Int> {
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

/**
 * A scanned file: its [lines], their [masked] code (see [maskLines]) and the [facade] class its top-level functions and
 * properties compile into.
 */
private class Source(val lines: List<String>, val facade: String) {
    val masked = maskLines(lines)
}

private val JVM_NAME = Regex("""^\s*@file:JvmName\(\s*"([^"]+)"\s*\)""", RegexOption.MULTILINE)

/** The file facade class of [file]: its `@file:JvmName`, else the file name, capitalised, with `Kt`. */
internal fun facadeName(file: String, text: String): String {
    JVM_NAME.find(text)?.let { return it.groupValues[1] }
    val base = file.substringAfterLast('/').substringBeforeLast('.').replace(Regex("""[^A-Za-z0-9_]"""), "_")
    return base.replaceFirstChar { it.uppercaseChar() } + "Kt"
}

/** The target of a standalone marker at [markerIndex] (0-based) of the [source]'s lines. */
private fun standaloneTarget(source: Source, markerIndex: Int): MarkerTarget {
    val lines = source.lines
    var index = markerIndex + 1
    var inBlockComment = false
    var openParentheses = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()
        if (openParentheses > 0) {
            // Inside a multi-line annotation's arguments: skip to its closing parenthesis.
            val (end, depth) = skipParentheses(line, 0, openParentheses)
            openParentheses = depth
            if (depth == 0) {
                val (rest, open) = stripAnnotations(line.substring(end))
                openParentheses = open
                if (rest.isNotBlank()) return targetOf(source, index, rest)
            }
        } else {
            when {
                inBlockComment -> if ("*/" in trimmed) inBlockComment = false
                trimmed.startsWith("/*") -> if ("*/" !in trimmed) inBlockComment = true
                trimmed.isEmpty() || trimmed.startsWith("//") -> Unit
                else -> {
                    val (rest, open) = stripAnnotations(line)
                    openParentheses = open
                    if (rest.isNotBlank()) return targetOf(source, index, rest)
                }
            }
        }
        index++
    }
    return MarkerTarget.Line(markerIndex + 1)
}

/** What the [code] of line [index] (0-based) of the [source]'s lines starts. */
private fun targetOf(source: Source, index: Int, code: String): MarkerTarget {
    val lines = source.lines
    val number = index + 1
    val start = declarationStart(code) ?: return MarkerTarget.Line(number)
    val name = start.name
    val scope = enclosingClasses(lines, index)
    // A declaration inside a function is local: its code compiles into the function's class under other names.
    if (scope.local && start.keyword !in CLASS_KEYWORDS) return MarkerTarget.Line(number)
    val owner = scope.path.joinToString("$").ifEmpty { source.facade }
    return when {
        start.keyword == "init" -> MarkerTarget.Block(number..blockEnd(source.masked.code, index))
        name == null -> MarkerTarget.Line(number)
        start.keyword in CLASS_KEYWORDS && scope.functionLine != null -> {
            val function = scope.functionLine
            MarkerTarget.Class(number, name, true, owner, function + 1..blockEnd(source.masked.code, function))
        }
        start.keyword in CLASS_KEYWORDS -> MarkerTarget.Class(number, (scope.path + name).joinToString("$"))
        start.keyword == "constructor" && scope.classLine != null -> {
            val end = if ('{' in code) blockEnd(source.masked.code, index) else number
            MarkerTarget.Constructor(number..end, owner, scope.classLine + 1)
        }
        start.keyword == "fun" ->
            MarkerTarget.Function(number, name, owner, nextDeclaration(lines, index, FUNCTION_KEYWORDS, name))
        start.keyword in PROPERTY_KEYWORDS ->
            MarkerTarget.Property(number, name, owner, nextDeclaration(lines, index, PROPERTY_KEYWORDS, name))
        else -> MarkerTarget.Line(number)
    }
}

/** The 1-based line of the next declaration after [index] with one of [keywords] and [name], or [Int.MAX_VALUE]. */
private fun nextDeclaration(lines: List<String>, index: Int, keywords: Set<String>, name: String): Int {
    for (next in index + 1 until lines.size) {
        val start = declarationStart(stripAnnotations(lines[next]).first) ?: continue
        if (start.keyword in keywords && start.name == name) return next + 1
    }
    return Int.MAX_VALUE
}

internal fun indentOf(line: String): Int =
    line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) line.length else it }

/**
 * The classes enclosing a declaration, outermost first; [local] when a function encloses it. [functionLine] is the
 * innermost enclosing function's line and [classLine] the innermost enclosing class's line (0-based), when present.
 */
internal class Scope(val path: List<String>, val local: Boolean, val functionLine: Int?, val classLine: Int?)

/**
 * The classes enclosing line [index] (0-based): each enclosing declaration is the nearest preceding declaration
 * indented less than the one inside it.
 */
internal fun enclosingClasses(lines: List<String>, index: Int): Scope {
    val path = mutableListOf<String>()
    var local = false
    var functionLine: Int? = null
    var classLine: Int? = null
    var indent = indentOf(lines[index])
    var current = index
    while (indent > 0) {
        val parent = (current - 1 downTo 0).firstOrNull { candidate ->
            val line = lines[candidate]
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("*") &&
                !trimmed.startsWith("/*") && indentOf(line) < indent &&
                declarationStart(stripAnnotations(line).first) != null
        } ?: break
        val start = declarationStart(stripAnnotations(lines[parent]).first)!!
        if (start.keyword in CLASS_KEYWORDS && start.name != null) {
            path.add(0, start.name)
            if (classLine == null) classLine = parent
        } else {
            local = true
            if (functionLine == null) functionLine = parent
        }
        indent = indentOf(lines[parent])
        current = parent
    }
    return Scope(path, local, functionLine, classLine)
}

/** The 1-based last line of the brace block opened on or after [start] (0-based) of the masked [code]. */
internal fun blockEnd(code: List<String>, start: Int): Int {
    var depth = 0
    var opened = false
    for (index in start until code.size) {
        for (c in code[index]) {
            when (c) {
                '{' -> { depth++; opened = true }
                '}' -> depth--
            }
        }
        if (opened && depth <= 0) return index + 1
    }
    return code.size
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
