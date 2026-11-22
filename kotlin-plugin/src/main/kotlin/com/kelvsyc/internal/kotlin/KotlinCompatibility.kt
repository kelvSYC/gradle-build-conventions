package com.kelvsyc.internal.kotlin

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * Configures [kotlin] and its [compilerOptions] so the compiled output and published metadata only require the floors
 * of [conventions] from consumers.
 *
 * The Kotlin Gradle plugin version only selects the compiler. What consumers need is the language and API version, and
 * the `kotlin-stdlib` version written into published metadata, which defaults to the plugin version unless
 * `coreLibrariesVersion` pins it.
 */
internal fun Project.configureKotlinCompatibility(
    conventions: KelvsycKotlinExtension,
    kotlin: KotlinProjectExtension,
    compilerOptions: KotlinCommonCompilerOptions,
) {
    compilerOptions.apiVersion.set(conventions.languageVersion)
    compilerOptions.languageVersion.set(conventions.languageVersion)

    // coreLibrariesVersion is a plain property that the Kotlin Gradle plugin reads when dependencies are resolved. The
    // extension's value is copied after evaluation, unless the build script set coreLibrariesVersion itself.
    val conventionValue = conventions.coreLibrariesVersion.get()
    kotlin.coreLibrariesVersion = conventionValue
    afterEvaluate {
        if (kotlin.coreLibrariesVersion == conventionValue) {
            kotlin.coreLibrariesVersion = conventions.coreLibrariesVersion.get()
        }
    }
}

/**
 * Fails JVM compilation when the API version is above the `major.minor` of `coreLibrariesVersion`: the output could then
 * call standard library members missing from the `kotlin-stdlib` its published metadata declares.
 */
internal fun Project.requireJvmApiWithinCoreLibraries(kotlin: KotlinProjectExtension) {
    val coreLibrariesVersion = provider { kotlin.coreLibrariesVersion }
    tasks.withType(KotlinJvmCompile::class.java).configureEach {
        doFirst(RequireApiWithinCoreLibraries(coreLibrariesVersion))
    }
}

private class RequireApiWithinCoreLibraries(private val coreLibrariesVersion: Provider<String>) : Action<Task> {
    override fun execute(task: Task) {
        val api = (task as KotlinJvmCompile).compilerOptions.apiVersion.orNull ?: KotlinVersion.DEFAULT
        val stdlib = coreLibrariesVersion.get()
        val stdlibVersion = majorMinor(stdlib) ?: return
        val apiVersion = majorMinor(api.version) ?: return
        if (apiVersion > stdlibVersion) {
            throw GradleException(
                "${task.path} compiles against Kotlin API version ${api.version}, above coreLibrariesVersion " +
                    "$stdlib: the published metadata would declare a kotlin-stdlib lacking API the output may use. " +
                    "Raise coreLibrariesVersion (kelvsycKotlin.coreLibrariesVersion) to ${api.version}.x or lower " +
                    "the API version.",
            )
        }
    }
}

/** The `major.minor` of [version], or null if it does not start with two numeric parts. */
private fun majorMinor(version: String): kotlin.KotlinVersion? {
    val parts = version.split('.').take(MAJOR_MINOR_PARTS).map { it.toIntOrNull() ?: return null }
    if (parts.size < MAJOR_MINOR_PARTS) return null
    return kotlin.KotlinVersion(parts[0], parts[1])
}

private const val MAJOR_MINOR_PARTS = 2
