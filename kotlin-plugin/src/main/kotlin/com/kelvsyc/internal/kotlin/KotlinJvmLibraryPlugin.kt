package com.kelvsyc.internal.kotlin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Kotlin JVM library convention: applies `java-library`, the Kotlin JVM and Dependency Analysis plugins and the floors
 * of the `kelvsycKotlin` extension ([KelvsycKotlinExtension]); see [configureKotlinCompatibility] and
 * [configureJvmCompatibility]. Its toolchain checks are those of [KotlinMultiplatformBasePlugin].
 *
 * The published `org.gradle.jvm.version` follows the Java compile tasks' release. It publishes a sources jar,
 * `implementation` gets the shared platform, tests get Kotest on the JUnit Platform, and with `maven-publish` applied
 * (before or after this plugin) it registers a `maven` publication of the `java` component.
 */
class KotlinJvmLibraryPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("java-library")
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")
        project.applyDependencyAnalysis()

        project.checkKotlinPluginVersion()
        project.checkKotlinToolchainAlignment()

        val conventions = KelvsycKotlinExtension.of(project)
        val kotlin = project.extensions.getByType(KotlinJvmProjectExtension::class.java)
        project.configureKotlinCompatibility(conventions, kotlin, kotlin.compilerOptions)
        project.configureJvmCompatibility(conventions, kotlin, kotlin.compilerOptions)

        project.extensions.getByType(JavaPluginExtension::class.java).withSourcesJar()

        project.dependencies.add("implementation", project.dependencies.platform(KotlinConventions.PLATFORM))
        for (module in KOTEST_MODULES) {
            project.dependencies.add("testImplementation", module)
        }

        project.tasks.withType(Test::class.java).configureEach {
            useJUnitPlatform()
        }

        project.pluginManager.withPlugin("maven-publish") {
            project.extensions.getByType(PublishingExtension::class.java).publications
                .register("maven", MavenPublication::class.java) {
                    from(project.components.getByName("java"))
                }
        }
    }

    private companion object {
        val KOTEST_MODULES = listOf(
            "io.kotest:kotest-assertions-core",
            "io.kotest:kotest-assertions-shared",
            "io.kotest:kotest-framework-engine",
            "io.kotest:kotest-runner-junit5",
        )
    }
}
