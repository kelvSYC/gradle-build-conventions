package com.kelvsyc.internal.publishing

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.Node
import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.publish.tasks.GenerateModuleMetadata

/**
 * Removes the internal platform `com.kelvsyc.internal:platform` from published metadata.
 *
 * Builds declare the platform for local version alignment only; consumers cannot resolve it, so it
 * must not appear in the published POM or Gradle module metadata.
 */
object StripInternalPlatform {
    private const val INTERNAL_GROUP = "com.kelvsyc.internal"
    private const val INTERNAL_PLATFORM = "platform"

    /**
     * Removes the platform from a POM's `dependencyManagement` and `dependencies`.
     */
    fun fromPom(root: Node) {
        val dependencyManagement = root.childNamed("dependencyManagement")
        dependencyManagement?.childNamed("dependencies")?.removeInternalPlatform()
        if (dependencyManagement?.childNamed("dependencies")?.children()?.isEmpty() == true) {
            root.remove(dependencyManagement)
        }
        root.childNamed("dependencies")?.removeInternalPlatform()
    }

    private fun Node.removeInternalPlatform() {
        children().removeAll { dep ->
            dep is Node && dep.childNamed("groupId")?.text() == INTERNAL_GROUP &&
                dep.childNamed("artifactId")?.text() == INTERNAL_PLATFORM
        }
    }

    private fun Node.childNamed(name: String): Node? =
        children().find { it is Node && it.name().toString().substringAfter('}') == name } as? Node

    /**
     * Removes the platform from the dependencies and dependency constraints of every variant in
     * the module metadata file written by a [GenerateModuleMetadata] task.
     */
    class ModuleMetadataAction : Action<Task> {
        override fun execute(task: Task) {
            val file = (task as GenerateModuleMetadata).outputFile.get().asFile
            if (!file.exists()) return

            @Suppress("UNCHECKED_CAST")
            val json = JsonSlurper().parseText(file.readText()) as MutableMap<String, Any?>
            @Suppress("UNCHECKED_CAST")
            (json["variants"] as? List<MutableMap<String, Any?>>)?.forEach { variant ->
                for (key in listOf("dependencies", "dependencyConstraints")) {
                    @Suppress("UNCHECKED_CAST")
                    (variant[key] as? MutableList<Map<String, Any?>>)?.removeAll { dep ->
                        dep["group"] == INTERNAL_GROUP && dep["module"] == INTERNAL_PLATFORM
                    }
                }
            }
            file.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(json)))
        }
    }
}
