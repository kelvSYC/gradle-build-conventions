package com.kelvsyc.internal.kotlin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Base of the Kotlin Multiplatform conventions, with no target: the targets come from the target plugins.
 *
 * It applies the Kotlin Multiplatform and Dependency Analysis plugins and creates the `kelvsycKotlin` extension
 * ([KelvsycKotlinExtension]), whose floors it applies to the compiler options and `coreLibrariesVersion`. It fails the
 * build if the applied Kotlin Gradle plugin differs from the compiler version these conventions were released for, or
 * if another Kotlin artifact on the build script classpath differs from the plugin.
 *
 * `commonMain` gets the shared platform, `commonTest` the Kotest assertions and engine (versioned by the platform), and
 * `check` the `checkCommonMainPlatformImports` task ([CheckCommonMainPlatformImports]).
 */
class KotlinMultiplatformBasePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        project.applyDependencyAnalysis()

        project.checkKotlinPluginVersion()
        project.checkKotlinToolchainAlignment()

        val conventions = KelvsycKotlinExtension.of(project)
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        project.configureKotlinCompatibility(conventions, kotlin, kotlin.compilerOptions)

        val platform = project.dependencies.platform(KotlinConventions.PLATFORM)
        kotlin.sourceSets.named("commonMain") {
            dependencies { implementation(platform) }
        }
        kotlin.sourceSets.named("commonTest") {
            dependencies {
                implementation("io.kotest:kotest-assertions-core")
                implementation("io.kotest:kotest-assertions-shared")
                implementation("io.kotest:kotest-framework-engine")
            }
        }

        val checkCommonMainPlatformImports = project.tasks.register(
            "checkCommonMainPlatformImports",
            CheckCommonMainPlatformImports::class.java,
        ) {
            description = "Fails if any commonMain Kotlin source contains platform-specific imports."
            group = "verification"
            sources.from(kotlin.sourceSets.named("commonMain").map { it.kotlin.matching { include("**/*.kt") } })
            projectDirectory.set(project.layout.projectDirectory)
        }
        project.tasks.named("check") {
            dependsOn(checkCommonMainPlatformImports)
        }
    }
}
