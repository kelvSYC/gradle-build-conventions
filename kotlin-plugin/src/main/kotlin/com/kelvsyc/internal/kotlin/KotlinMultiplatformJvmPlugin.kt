package com.kelvsyc.internal.kotlin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Kotlin Multiplatform JVM target convention: applies [KotlinMultiplatformBasePlugin] and adds a `jvm` target
 * compiled with [KelvsycKotlinExtension.jvmToolchain] for [KelvsycKotlinExtension.jvmRelease] (see
 * [configureJvmCompatibility]), whose variants declare that release as `org.gradle.jvm.version`. `jvmTest` runs Kotest
 * on the JUnit Platform.
 *
 * With the Dokka plugin applied, the `jvm` publication also gets a javadoc jar holding the Dokka HTML documentation:
 * Dokka's Javadoc format does not support Kotlin Multiplatform.
 */
class KotlinMultiplatformJvmPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(KotlinMultiplatformBasePlugin::class.java)

        val conventions = KelvsycKotlinExtension.of(project)
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        kotlin.jvm {
            project.configureJvmCompatibility(conventions, kotlin, compilerOptions)
            // The Kotlin Gradle plugin never sets org.gradle.jvm.version on JVM target variants. Without it, Gradle
            // consumers on an older JVM resolve the library without complaint and fail at runtime, and the project's
            // own JVM classpaths accept dependencies that require a newer JVM.
            attributes.attributeProvider(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, conventions.jvmRelease)
        }

        kotlin.sourceSets.named("jvmTest") {
            dependencies { implementation("io.kotest:kotest-runner-junit5") }
        }

        project.tasks.withType(Test::class.java).configureEach {
            useJUnitPlatform()
        }

        project.pluginManager.withPlugin("org.jetbrains.dokka") {
            val javadocJar = project.tasks.register("javadocJar", Jar::class.java) {
                archiveClassifier.set("javadoc")
                from(project.tasks.named("dokkaGeneratePublicationHtml"))
            }
            project.pluginManager.withPlugin("maven-publish") {
                project.extensions.getByType(PublishingExtension::class.java).publications
                    .withType(MavenPublication::class.java)
                    .matching { it.name == "jvm" }
                    .configureEach { artifact(javadocJar) }
            }
        }
    }
}
