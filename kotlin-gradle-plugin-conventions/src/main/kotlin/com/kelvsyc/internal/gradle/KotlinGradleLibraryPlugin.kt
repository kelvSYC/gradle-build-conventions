package com.kelvsyc.internal.gradle

import com.kelvsyc.internal.kotlin.configureAbiValidation
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.gradleKotlinDsl

/**
 * Convention for Kotlin JVM libraries compiled against the Gradle API: applies `java-library` and the Kotlin JVM
 * plugin, then the tier and the rest of [configureGradleKind].
 *
 * The Kotlin standard library is not a dependency (`kotlin.stdlib.default.dependency=false`): consumers run inside
 * Gradle, which supplies it, and a published version would conflict with the Kotlin DSL plugins of older Gradle
 * releases, which pin it strictly to their embedded Kotlin. Unit tests compile and run against the Gradle running the
 * build. With `maven-publish` applied (before or after this plugin) it registers a `maven` publication of the `java`
 * component. With `com.kelvsyc.kotlin.abiValidation=true` it turns on ABI validation ([configureAbiValidation]).
 */
class KotlinGradleLibraryPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Read by the Kotlin Gradle plugin, so set before it is applied.
        project.extensions.extraProperties.set("kotlin.stdlib.default.dependency", "false")
        project.pluginManager.apply("java-library")
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")

        project.configureGradleKind()
        project.configureAbiValidation()

        val dependencies = project.dependencies
        dependencies.add("testCompileOnly", dependencies.gradleApi())
        dependencies.add("testCompileOnly", project.gradleKotlinDsl())
        dependencies.add("testImplementation", "org.jetbrains.kotlin:kotlin-stdlib")
        dependencies.add("testRuntimeOnly", dependencies.gradleApi())
        dependencies.add("testRuntimeOnly", project.gradleKotlinDsl())

        project.pluginManager.withPlugin("maven-publish") {
            project.extensions.getByType(PublishingExtension::class.java).publications
                .register("maven", MavenPublication::class.java) {
                    from(project.components.getByName("java"))
                }
        }
    }
}
