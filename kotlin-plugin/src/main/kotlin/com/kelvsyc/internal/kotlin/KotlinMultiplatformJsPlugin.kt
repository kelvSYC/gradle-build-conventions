package com.kelvsyc.internal.kotlin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin

/**
 * Kotlin Multiplatform JS target convention: applies the KSP and Kotest Gradle plugins (Kotest needs both to run JS
 * tests) and [KotlinMultiplatformBasePlugin], and adds a Node.js `js` library target.
 *
 * Kotlin/JS rejects a stdlib whose klib ABI is older than the compiler's, so `jsMain` raises the stdlib pinned by
 * `coreLibrariesVersion` (for JVM consumers) back to the compiler's version. Node.js is pinned to
 * [KelvsycKotlinExtension.nodeVersion], in this project and in the root project, which runs the build's npm tasks.
 */
class KotlinMultiplatformJsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("com.google.devtools.ksp")
        project.pluginManager.apply("io.kotest")
        project.pluginManager.apply(KotlinMultiplatformBasePlugin::class.java)

        val conventions = KelvsycKotlinExtension.of(project)
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        kotlin.js {
            nodejs()
            binaries.library()
        }

        val compilerVersion = project.getKotlinPluginVersion()
        kotlin.sourceSets.named("jsMain") {
            dependencies { implementation("org.jetbrains.kotlin:kotlin-stdlib:$compilerVersion") }
        }

        for (target in setOf(project, project.rootProject)) {
            target.plugins.withType(NodeJsPlugin::class.java) {
                target.extensions.getByType(NodeJsEnvSpec::class.java).version.set(conventions.nodeVersion)
            }
        }
    }
}
