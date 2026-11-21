package com.kelvsyc.internal.kotlin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

/**
 * Kotlin Multiplatform JVM target convention: applies [KotlinMultiplatformBasePlugin] and adds a `jvm` target
 * compiled with [KelvsycKotlinExtension.jvmToolchain] for [KelvsycKotlinExtension.jvmRelease] (see
 * [configureJvmCompatibility]), whose variants declare that release as `org.gradle.jvm.version`. `jvmTest` runs Kotest
 * on the JUnit Platform.
 *
 * With the Dokka plugin applied, the `jvm` publication also gets a javadoc jar. Its format depends on the project's
 * targets once the project is evaluated:
 *
 * - Only JVM targets (besides the common metadata): Dokka's Javadoc format. `org.jetbrains.dokka-javadoc` is applied,
 *   and the jar holds `dokkaGeneratePublicationJavadoc`'s output.
 * - Any other target (JS, native, Wasm, Android): Dokka's HTML format, from `dokkaGeneratePublicationHtml`. Dokka's
 *   Javadoc format rejects source sets whose platform is neither JVM nor common, so `org.jetbrains.dokka-javadoc` is
 *   not applied.
 *
 * Adding a non-JVM target to a JVM-only project therefore switches its javadoc jar from Javadoc to HTML.
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
            }
            // Targets can still be added by the build script or by plugins applied after this one.
            project.afterEvaluate {
                val jvmOnly = kotlin.targets.none { it.platformType !in JAVADOC_PLATFORMS }
                if (jvmOnly) {
                    project.pluginManager.apply("org.jetbrains.dokka-javadoc")
                }
                val generate = if (jvmOnly) "dokkaGeneratePublicationJavadoc" else "dokkaGeneratePublicationHtml"
                javadocJar.configure { from(project.tasks.named(generate)) }
            }
            project.pluginManager.withPlugin("maven-publish") {
                project.extensions.getByType(PublishingExtension::class.java).publications
                    .withType(MavenPublication::class.java)
                    .matching { it.name == "jvm" }
                    .configureEach { artifact(javadocJar) }
            }
        }
    }

    private companion object {
        /** The platforms Dokka's Javadoc format documents. */
        val JAVADOC_PLATFORMS = setOf(KotlinPlatformType.common, KotlinPlatformType.jvm)
    }
}
