package com.kelvsyc.internal.kotlin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

/**
 * Kotlin Multiplatform native target convention: applies the KSP and Kotest Gradle plugins (Kotest needs both to run
 * native tests) and [KotlinMultiplatformBasePlugin], and declares the one native target of the build host: `linuxX64`
 * on Linux, `macosArm64` on macOS and `mingwX64` on Windows. Every native compilation opts in to
 * `kotlinx.cinterop.ExperimentalForeignApi`, for platform interop.
 *
 * Each host builds and publishes only its own native target, so the other native targets are never published.
 */
class KotlinMultiplatformNativePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("com.google.devtools.ksp")
        project.pluginManager.apply("io.kotest")
        project.pluginManager.apply(KotlinMultiplatformBasePlugin::class.java)

        val hostOs = project.providers.systemProperty(OS_NAME_PROPERTY).get()
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        when {
            hostOs.startsWith(LINUX) -> kotlin.linuxX64()
            hostOs == MAC_OS -> kotlin.macosArm64()
            hostOs.startsWith(WINDOWS) -> kotlin.mingwX64()
        }

        kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach {
            compilations.configureEach {
                compileTaskProvider.configure {
                    compilerOptions.optIn.add(CINTEROP_OPT_IN)
                }
            }
        }
    }

    private companion object {
        const val OS_NAME_PROPERTY = "os.name"
        const val LINUX = "Linux"
        const val MAC_OS = "Mac OS X"
        const val WINDOWS = "Windows"
        const val CINTEROP_OPT_IN = "kotlinx.cinterop.ExperimentalForeignApi"
    }
}
