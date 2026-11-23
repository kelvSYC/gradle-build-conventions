package com.kelvsyc.internal.coverage.engine

import java.io.File
import kotlin.metadata.KmClass
import kotlin.metadata.KmConstructor
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.KmPropertyAccessorAttributes
import kotlin.metadata.Visibility
import kotlin.metadata.isInline
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.visibility
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * A method with line information in a compiled class.
 *
 * [className] is a JVM internal name and [sourceFile] is keyed as in [JacocoXmlReport.lines]. [lines] spans the
 * method's own source lines: line number table entries that the class's SMAP (`SourceDebugExtension`) maps from
 * inlined code are ignored, since inlining places those at synthetic lines that may overlap other declarations.
 * [publicApi] is true for public or protected members of publicly reachable classes
 * that are not compiler-synthesized; [inline] is true for Kotlin `inline` functions and property accessors.
 */
data class Declaration(
    val className: String,
    val name: String,
    val desc: String,
    val sourceFile: String,
    val lines: IntRange,
    val publicApi: Boolean,
    val inline: Boolean,
)

private const val METADATA_DESC = "Lkotlin/Metadata;"
private const val ASM_API = Opcodes.ASM9
private const val NOT_MEMBER = 0

private class MemberInfo(val publicApi: Boolean, val inline: Boolean)

private class MethodInfo(val name: String, val desc: String, val access: Int, val lines: IntRange)

private class ClassInfo(
    val name: String,
    val access: Int,
    val sourceFile: String?,
    val methods: List<MethodInfo>,
    val innerAccess: Int?,
    val outerName: String?,
    val metadata: Metadata?,
)

/**
 * Scans every `.class` file under [classDirs]: one [Declaration] per method that has a line number table.
 */
fun scanDeclarations(classDirs: List<File>): List<Declaration> {
    val classes = classDirs.flatMap { dir ->
        dir.walkTopDown().filter { it.isFile && it.extension == "class" && it.name != "module-info.class" }
            .map { readClass(it) }.toList()
    }.associateBy { it.name }

    val result = mutableListOf<Declaration>()
    for (cls in classes.values) {
        val sourceFile = cls.sourceFile ?: continue
        val key = if ('/' in cls.name) cls.name.substringBeforeLast('/') + "/" + sourceFile else sourceFile
        val parsed = parseMetadata(cls.metadata)
        val reachable = isReachable(cls, parsed, classes)
        val members = parsed?.members
        for (m in cls.methods) {
            val jvmVisible = m.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != NOT_MEMBER &&
                m.access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) == NOT_MEMBER
            val member = members?.get(m.name + m.desc)
            val publicApi = reachable && jvmVisible && (if (members == null) true else member?.publicApi == true)
            result.add(Declaration(cls.name, m.name, m.desc, key, m.lines, publicApi, member?.inline == true))
        }
    }
    return result
}

private class ParsedMetadata(val selfVisible: Boolean?, val members: Map<String, MemberInfo>?)

private fun parseMetadata(metadata: Metadata?): ParsedMetadata? {
    if (metadata == null) return null
    return when (val parsed = KotlinClassMetadata.readLenient(metadata)) {
        is KotlinClassMetadata.Class -> {
            val kmClass = parsed.kmClass
            ParsedMetadata(kmClass.visibility.isApi(), classMembers(kmClass))
        }
        is KotlinClassMetadata.FileFacade -> ParsedMetadata(null, packageMembers(parsed.kmPackage.functions, parsed.kmPackage.properties))
        is KotlinClassMetadata.MultiFileClassPart ->
            ParsedMetadata(null, packageMembers(parsed.kmPackage.functions, parsed.kmPackage.properties))
        is KotlinClassMetadata.SyntheticClass -> ParsedMetadata(false, emptyMap())
        else -> null
    }
}

private fun Visibility.isApi() = this == Visibility.PUBLIC || this == Visibility.PROTECTED

private fun classMembers(kmClass: KmClass): Map<String, MemberInfo> =
    packageMembers(kmClass.functions, kmClass.properties).toMutableMap().also { map ->
        kmClass.constructors.forEach { c -> addConstructor(map, c) }
    }

private fun addConstructor(map: MutableMap<String, MemberInfo>, constructor: KmConstructor) {
    val sig = constructor.signature ?: return
    map[sig.name + sig.descriptor] = MemberInfo(constructor.visibility.isApi(), false)
}

private fun packageMembers(functions: List<KmFunction>, properties: List<KmProperty>): Map<String, MemberInfo> {
    val map = linkedMapOf<String, MemberInfo>()
    for (fn in functions) {
        val sig = fn.signature ?: continue
        map[sig.name + sig.descriptor] = MemberInfo(fn.visibility.isApi(), fn.isInline)
    }
    for (prop in properties) {
        prop.getterSignature?.let { map[it.name + it.descriptor] = accessor(prop.getter) }
        prop.setterSignature?.let { sig -> prop.setter?.let { map[sig.name + sig.descriptor] = accessor(it) } }
    }
    return map
}

private fun accessor(attributes: KmPropertyAccessorAttributes) = MemberInfo(attributes.visibility.isApi(), attributes.isInline)

private fun isReachable(cls: ClassInfo, parsed: ParsedMetadata?, classes: Map<String, ClassInfo>): Boolean {
    val selfVisible = parsed?.selfVisible
        ?: ((cls.innerAccess ?: cls.access) and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != NOT_MEMBER)
    if (!selfVisible) return false
    val outer = cls.outerName?.let { classes[it] } ?: return cls.outerName == null
    return isReachable(outer, parseMetadata(outer.metadata), classes)
}

private fun readClass(file: File): ClassInfo {
    var name = ""
    var access = 0
    var sourceFile: String? = null
    var inlined: List<IntRange> = emptyList()
    var innerAccess: Int? = null
    var outerName: String? = null
    var metadata: Metadata? = null
    val methods = mutableListOf<MethodInfo>()

    val visitor = object : ClassVisitor(ASM_API) {
        override fun visit(version: Int, acc: Int, n: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
            name = n
            access = acc
        }

        override fun visitSource(source: String?, debug: String?) {
            sourceFile = source
            inlined = inlinedRanges(debug)
        }

        override fun visitInnerClass(n: String, outer: String?, inner: String?, acc: Int) {
            if (n == name) {
                innerAccess = acc
                outerName = outer
                if (outer == null) innerAccess = 0
            }
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
            if (descriptor == METADATA_DESC) MetadataCollector { metadata = it } else null

        override fun visitMethod(
            acc: Int, n: String, desc: String, signature: String?, exceptions: Array<out String>?,
        ): MethodVisitor {
            return object : MethodVisitor(ASM_API) {
                var min = Int.MAX_VALUE
                var max = Int.MIN_VALUE

                override fun visitLineNumber(line: Int, start: org.objectweb.asm.Label) {
                    if (inlined.any { line in it }) return
                    if (line < min) min = line
                    if (line > max) max = line
                }

                override fun visitEnd() {
                    if (min <= max) methods.add(MethodInfo(n, desc, acc, min..max))
                }
            }
        }
    }
    ClassReader(file.readBytes()).accept(visitor, ClassReader.SKIP_FRAMES)
    return ClassInfo(name, access, sourceFile, methods, innerAccess, outerName, metadata)
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
