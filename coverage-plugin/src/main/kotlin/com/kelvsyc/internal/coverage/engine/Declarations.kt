package com.kelvsyc.internal.coverage.engine

import java.io.File
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.isInline
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * What the compiled classes tell about their sources, keyed as in [JacocoXmlReport.lines].
 *
 * [inlineLines] are the lines of `inline` functions and property accessors, which JaCoCo attributes to their callers,
 * so their own lines never report as executed, and of the lambdas and anonymous objects inside them, which the compiler
 * regenerates at each call site. [compiledSourceFiles] are the source files with code in some class.
 */
data class CompiledFacts(val inlineLines: Map<String, Set<Int>>, val compiledSourceFiles: Set<String>)

private const val METADATA_DESC = "Lkotlin/Metadata;"
private const val ASM_API = Opcodes.ASM9

/** A method's name and descriptor, and its own source lines. */
private class MethodLines(val signature: String, val lines: Set<Int>)

private class ClassInfo(
    val name: String,
    val sourceFile: String?,
    val methods: List<MethodLines>,
    val metadata: Metadata?,
)

/**
 * Scans every `.class` file under [classDirs]. A method's lines come from its line number table, without the entries
 * that the class's SMAP (`SourceDebugExtension`) maps from inlined code; the inline flag comes from Kotlin metadata.
 */
fun scanCompiled(classDirs: List<File>): CompiledFacts {
    val inlineLines = mutableMapOf<String, MutableSet<Int>>()
    val compiledSourceFiles = mutableSetOf<String>()
    val classFiles = classDirs.flatMap { dir ->
        dir.walkTopDown().filter { it.isFile && it.extension == "class" && it.name != "module-info.class" }.toList()
    }
    val classes = classFiles.map { readClass(it) }.filter { it.methods.isNotEmpty() }
    val inline = classes.associate { it.name to inlineSignatures(it.metadata) }
    // Classes generated inside an inline function are named after it: `Owner$function$1`.
    val regeneratedPrefixes = inline.flatMap { (owner, signatures) -> signatures.map { "$owner\$${it.name}\$" } }
    for (cls in classes) {
        val sourceFile = cls.sourceFile ?: continue
        val key = if ('/' in cls.name) cls.name.substringBeforeLast('/') + "/" + sourceFile else sourceFile
        compiledSourceFiles.add(key)
        val regenerated = regeneratedPrefixes.any { cls.name.startsWith(it) }
        val signatures = inline.getValue(cls.name).map { it.name + it.descriptor }
        for (method in cls.methods.filter { regenerated || it.signature in signatures }) {
            inlineLines.getOrPut(key) { mutableSetOf() }.addAll(method.lines)
        }
    }
    return CompiledFacts(inlineLines, compiledSourceFiles)
}

/** The signatures of the `inline` functions and property accessors [metadata] declares. */
private fun inlineSignatures(metadata: Metadata?): List<JvmMethodSignature> {
    val (functions, properties) = when (val parsed = metadata?.let { KotlinClassMetadata.readLenient(it) }) {
        is KotlinClassMetadata.Class -> parsed.kmClass.functions to parsed.kmClass.properties
        is KotlinClassMetadata.FileFacade -> parsed.kmPackage.functions to parsed.kmPackage.properties
        is KotlinClassMetadata.MultiFileClassPart -> parsed.kmPackage.functions to parsed.kmPackage.properties
        else -> return emptyList()
    }
    return inlineSignatures(functions, properties)
}

private fun inlineSignatures(functions: List<KmFunction>, properties: List<KmProperty>): List<JvmMethodSignature> =
    functions.filter { it.isInline }.mapNotNull { it.signature } +
        properties.flatMap { property ->
            listOfNotNull(
                property.getterSignature.takeIf { property.getter.isInline },
                property.setterSignature.takeIf { property.setter?.isInline == true },
            )
        }

private fun readClass(file: File): ClassInfo {
    var name = ""
    var sourceFile: String? = null
    var inlined: List<IntRange> = emptyList()
    var metadata: Metadata? = null
    val methods = mutableListOf<MethodLines>()

    val visitor = object : ClassVisitor(ASM_API) {
        override fun visit(version: Int, acc: Int, n: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
            name = n
        }

        override fun visitSource(source: String?, debug: String?) {
            sourceFile = source
            inlined = inlinedRanges(debug)
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
            if (descriptor == METADATA_DESC) MetadataCollector { metadata = it } else null

        override fun visitMethod(
            acc: Int, n: String, desc: String, signature: String?, exceptions: Array<out String>?,
        ): MethodVisitor {
            return object : MethodVisitor(ASM_API) {
                val lines = sortedSetOf<Int>()

                override fun visitLineNumber(line: Int, start: Label) {
                    if (inlined.none { line in it }) lines.add(line)
                }

                override fun visitEnd() {
                    if (lines.isNotEmpty()) methods.add(MethodLines(n + desc, lines))
                }
            }
        }
    }
    ClassReader(file.readBytes()).accept(visitor, ClassReader.SKIP_FRAMES)
    return ClassInfo(name, sourceFile, methods, metadata)
}

private const val OWN_FILE_ID = 1
private val SMAP_LINE = Regex("""(\d+)(?:#(\d+))?(?:,(\d+))?:(\d+)(?:,(\d+))?""")

/**
 * Output line ranges of the `Kotlin` stratum in the SMAP [debug] that are not the class's own source.
 *
 * Own lines are the entries mapping the class's own file (id 1) onto the same line numbers; every other entry,
 * including inline functions from the same file, which are mapped to synthetic line numbers, is inlined code.
 */
private fun inlinedRanges(debug: String?): List<IntRange> {
    if (debug == null) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var stratum = ""
    var inLines = false
    var fileId = OWN_FILE_ID
    for (line in debug.lineSequence()) {
        when {
            line.startsWith("*S ") -> { stratum = line.removePrefix("*S ").trim(); inLines = false }
            line == "*L" -> inLines = true
            line.startsWith("*") -> inLines = false
            inLines && stratum == "Kotlin" -> {
                val m = SMAP_LINE.matchEntire(line.trim()) ?: continue
                val (input, file, repeat, output, increment) = m.destructured
                if (file.isNotEmpty()) fileId = file.toInt()
                val count = (if (repeat.isEmpty()) 1 else repeat.toInt()) * (if (increment.isEmpty()) 1 else increment.toInt())
                val start = output.toInt()
                if (fileId != OWN_FILE_ID || input.toInt() != start) ranges.add(start until start + count)
            }
        }
    }
    return ranges
}

private class MetadataCollector(private val onDone: (Metadata) -> Unit) : AnnotationVisitor(ASM_API) {
    private var kind = 1
    private var metadataVersion = intArrayOf()
    private var data1 = emptyArray<String>()
    private var data2 = emptyArray<String>()
    private var extraString = ""
    private var packageName = ""
    private var extraInt = 0

    override fun visit(name: String, value: Any?) {
        when (name) {
            "k" -> kind = value as Int
            "mv" -> metadataVersion = value as IntArray
            "xs" -> extraString = value as String
            "pn" -> packageName = value as String
            "xi" -> extraInt = value as Int
        }
    }

    override fun visitArray(name: String): AnnotationVisitor? {
        if (name != "d1" && name != "d2") return null
        val values = mutableListOf<String>()
        return object : AnnotationVisitor(ASM_API) {
            override fun visit(n: String?, value: Any?) {
                values.add(value as String)
            }

            override fun visitEnd() {
                if (name == "d1") data1 = values.toTypedArray() else data2 = values.toTypedArray()
            }
        }
    }

    override fun visitEnd() {
        onDone(
            Metadata(
                kind = kind, metadataVersion = metadataVersion, data1 = data1, data2 = data2,
                extraString = extraString, packageName = packageName, extraInt = extraInt,
            ),
        )
    }
}
