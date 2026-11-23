package com.kelvsyc.internal.coverage.engine

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Instruction and branch counts for one source line. */
data class LineCoverage(
    val missedInstructions: Int,
    val coveredInstructions: Int,
    val missedBranches: Int,
    val coveredBranches: Int,
)

/**
 * A JaCoCo XML report: per-line coverage keyed by `package/path/File.kt`.
 */
class JacocoXmlReport(val lines: Map<String, Map<Int, LineCoverage>>) {
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
            for (pkg in root.descendants("package")) {
                val pkgName = pkg.getAttribute("name")
                for (source in pkg.descendants("sourcefile")) {
                    val name = source.getAttribute("name")
                    val path = if (pkgName.isEmpty()) name else "$pkgName/$name"
                    lines[path] = source.descendants("line").associate {
                        it.getAttribute("nr").toInt() to LineCoverage(
                            it.getAttribute("mi").toInt(),
                            it.getAttribute("ci").toInt(),
                            it.getAttribute("mb").toInt(),
                            it.getAttribute("cb").toInt(),
                        )
                    }
                }
            }
            return JacocoXmlReport(lines)
        }

        /** The descendant elements named [tag]. */
        private fun Element.descendants(tag: String): List<Element> =
            getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
    }
}
