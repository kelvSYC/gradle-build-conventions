package com.kelvsyc.internal.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.kotlin.dsl.gradleKotlinDsl

/**
 * Convention for Gradle plugins written in Kotlin: applies `java-library` and `kotlin-dsl` (which brings
 * `java-gradle-plugin`), then the tier and the rest of [configureGradleKind].
 *
 * `kotlin-dsl` and `java-gradle-plugin` add the running Gradle's API as file dependencies, and `kotlin-dsl` adds its
 * embedded Kotlin; `main`'s compile classpath drops both, so that it compiles against the tier's Gradle API and Kotlin
 * only. Unit tests compile and run against the Gradle running the build. `java-gradle-plugin` publishes the plugin
 * (`pluginMaven` and a marker per plugin).
 */
class KotlinGradlePluginPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("java-library")
        project.pluginManager.apply("org.gradle.kotlin.kotlin-dsl")

        project.configurations.matching { it.name == "compileOnly" || it.name == "compileOnlyApi" }.configureEach {
            withDependencies { removeIf { it is FileCollectionDependency } }
        }
        project.configurations.named("compileOnly") {
            setExtendsFrom(extendsFrom.filterNot { it.name == "embeddedKotlin" })
        }

        project.configureGradleKind()

        val dependencies = project.dependencies
        dependencies.add("testImplementation", dependencies.gradleApi())
        dependencies.add("testImplementation", project.gradleKotlinDsl())
        dependencies.add("testImplementation", "org.jetbrains.kotlin:kotlin-stdlib")

        // Kept from the plugin convention this one replaces. Gradle's own check is suppressed; with the shared
        // publishing convention, RequireDependencyVersions still fails a build publishing a versionless dependency.
        project.tasks.withType(GenerateModuleMetadata::class.java).configureEach {
            suppressedValidationErrors.add("dependencies-without-versions")
        }
    }
}
