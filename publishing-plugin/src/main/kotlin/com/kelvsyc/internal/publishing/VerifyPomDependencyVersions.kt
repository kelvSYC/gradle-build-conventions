package com.kelvsyc.internal.publishing

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Fails if any generated POM declares a dependency without a version.
 *
 * Reads the POMs of the Maven publications, which are generated without building any publication
 * artifact (module metadata, by contrast, lists the size and checksum of every artifact, so
 * generating it builds them all, including documentation jars). The POMs are read after
 * [StripInternalPlatform.fromPom], which runs during POM generation. Only `<dependencies>` of the
 * project are checked, not `<dependencyManagement>`.
 */
@DisableCachingByDefault(because = "Has no outputs")
abstract class VerifyPomDependencyVersions : DefaultTask() {
    /** The generated POM files, one per Maven publication. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pomFiles: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val failures = pomFiles.files.sortedBy { it.path }.mapNotNull(::versionlessDependenciesMessage)
        if (failures.isNotEmpty()) throw GradleException(failures.joinToString("\n"))
    }

    private fun versionlessDependenciesMessage(pom: File): String? {
        val project = parse(pom).documentElement
        val versionless = project.childElements("dependencies").flatMap { it.childElements("dependency") }
            .filter { it.childElements("version").none { version -> version.textContent.isNotBlank() } }
            .map { "${it.childText("groupId")}:${it.childText("artifactId")}" }
            .distinct()
        if (versionless.isEmpty()) return null

        val parent = project.childElements("parent").firstOrNull()
        val group = project.childText("groupId") ?: parent?.childText("groupId")
        val version = project.childText("version") ?: parent?.childText("version")
        return "$group:${project.childText("artifactId")}:$version would publish dependencies without a version: " +
            "${versionless.joinToString()}. " +
            "Declare an explicit version (for another module of the same build, use the project version: " +
            "\"<group>:<module>:\$version\")."
    }

    private fun parse(pom: File) = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(pom)

    private fun Element.childElements(name: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }
            .filter { it.nodeType == Node.ELEMENT_NODE && it.nodeName == name }
            .map { it as Element }

    private fun Element.childText(name: String): String? = childElements(name).firstOrNull()?.textContent?.trim()
}
