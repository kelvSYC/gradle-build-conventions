package com.kelvsyc.internal.coverage.engine

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/** Instruction and branch counts for one source line. */
data class LineCoverage(
    val missedInstructions: Int,
    val coveredInstructions: Int,
    val missedBranches: Int,
    val coveredBranches: Int,
)

/** Whether a method ([className] is a JVM internal name) was entered at all. */
data class MethodCoverage(val className: String, val name: String, val desc: String, val covered: Boolean)

/**
 * A JaCoCo XML report: per-line coverage keyed by `package/path/File.kt`, and per-method coverage.
 */
class JacocoXmlReport(
    val lines: Map<String, Map<Int, LineCoverage>>,
    val methods: List<MethodCoverage>,
) {
    companion object {
        private const val LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd"
        private const val EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities"
        private const val EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities"

        /** Parses [xml] without loading the report's external DTD or any external entity. */
        fun parse(xml: File): JacocoXmlReport {
            val factory = DocumentBuilderFactory.newInstance()
            factory.setFeature(LOAD_EXTERNAL_DTD, false)
            factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false)
            factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false)
            factory.isExpandEntityReferences = false
            factory.isValidating = false
            val root = factory.newDocumentBuilder().parse(xml).documentElement

            val lines = linkedMapOf<String, Map<Int, LineCoverage>>()
            val methods = mutableListOf<MethodCoverage>()
            for (pkg in root.children("package")) {
                val pkgName = pkg.getAttribute("name")
                for (cls in pkg.children("class")) {
                    val className = cls.getAttribute("name")
                    for (method in cls.children("method")) {
                        val covered = method.children("counter")
                            .firstOrNull { it.getAttribute("type") == "METHOD" }
                            ?.getAttribute("covered")?.toInt() ?: 0
                        methods.add(
                            MethodCoverage(className, method.getAttribute("name"), method.getAttribute("desc"), covered > 0),
                        )
                    }
                }
                for (source in pkg.children("sourcefile")) {
                    val path = if (pkgName.isEmpty()) source.getAttribute("name") else "$pkgName/${source.getAttribute("name")}"
                    lines[path] = source.children("line").associate {
                        it.getAttribute("nr").toInt() to LineCoverage(
                            it.getAttribute("mi").toInt(),
                            it.getAttribute("ci").toInt(),
                            it.getAttribute("mb").toInt(),
                            it.getAttribute("cb").toInt(),
                        )
                    }
                }
            }
            return JacocoXmlReport(lines, methods)
        }

        private fun Element.children(tag: String): List<Element> {
            val result = mutableListOf<Element>()
            var node: Node? = firstChild
            while (node != null) {
                if (node is Element && node.tagName == tag) result.add(node)
                node = node.nextSibling
            }
            return result
        }
    }
}
