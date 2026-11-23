package com.kelvsyc.internal.jacoco

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.testing.jacoco.plugins.JacocoPlugin
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.util.Properties

/**
 * JaCoCo convention: applies `jacoco` at the shared catalog's JaCoCo version, and requires the XML and HTML reports
 * of `jacocoTestReport` in a project with the `java` plugin.
 *
 * Nothing is added to `check`, and no thresholds are set.
 */
class JacocoConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(JacocoPlugin::class.java)
        project.extensions.getByType(JacocoPluginExtension::class.java).toolVersion = JACOCO_VERSION

        project.pluginManager.withPlugin("java") {
            project.tasks.named("jacocoTestReport", JacocoReport::class.java) {
                dependsOn(project.tasks.named(JavaPlugin.TEST_TASK_NAME))
                reports.xml.required.set(true)
                reports.html.required.set(true)
            }
        }
    }
}

private val JACOCO_VERSION: String by lazy {
    val stream = JacocoConventionPlugin::class.java.getResourceAsStream("jacoco-conventions.properties")
        ?: error("jacoco-conventions.properties is missing from the shared JaCoCo plugin")
    val properties = stream.use { Properties().apply { load(it) } }
    properties.getProperty("jacoco") ?: error("jacoco-conventions.properties does not define 'jacoco'")
}
