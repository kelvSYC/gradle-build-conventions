package com.kelvsyc.internal.kotlin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Kotlin Multiplatform convention for published libraries: applies [KotlinMultiplatformBasePlugin] and publishes a
 * sources jar with every target.
 */
class KotlinMultiplatformLibraryPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(KotlinMultiplatformBasePlugin::class.java)
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).withSourcesJar(publish = true)
    }
}
