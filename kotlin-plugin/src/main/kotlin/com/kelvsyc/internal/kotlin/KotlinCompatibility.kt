package com.kelvsyc.internal.kotlin

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion
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

/**
 * Fails if the applied Kotlin Gradle plugin differs from [KotlinConventions.KOTLIN], the compiler version these
 * conventions were released for.
 *
 * Conflict resolution on the build script classpath can silently raise the plugin to Gradle's embedded Kotlin version
 * (or the stdlib another build plugin requires).
 */
internal fun Project.checkKotlinPluginVersion() {
    val expected = KotlinConventions.KOTLIN
    val actual = getKotlinPluginVersion()
    if (actual != expected) {
        throw GradleException(
            "Kotlin Gradle plugin $actual is applied, but the shared build conventions declare kotlin = \"$expected\". " +
                "The plugin was likely changed by Gradle's embedded Kotlin, another build plugin's requirements or a " +
                "local constraint. The compiler version comes from the shared build conventions: release a matching " +
                "version there and bump the gradle-build-conventions pin.",
        )
    }
}

/**
 * Kotlin artifacts exempt from [checkKotlinToolchainAlignment]: a BOM, and stdlib shims that have been empty since their
 * contents were merged into kotlin-stdlib.
 */
private val ALIGNMENT_EXEMPT_MODULES = setOf("kotlin-bom", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8")

/**
 * Fails if any Kotlin artifact on the build script classpath of the project or its parents differs from the applied
 * Kotlin Gradle plugin version.
 *
 * Kotlin's version alignment does not cover every toolchain artifact, so a build plugin depending on a newer
 * `kotlin-bom` can raise one alone (e.g. `kotlin-daemon-client`). A daemon client newer than the compiler cannot connect
 * to the compile daemon, and the in-process fallback then fails with Metaspace exhaustion rather than a version error.
 */
internal fun Project.checkKotlinToolchainAlignment() {
    val expected = getKotlinPluginVersion()
    val mismatched = generateSequence(this) { it.parent }
        .flatMap { project ->
            project.buildscript.configurations.getByName("classpath").incoming.resolutionResult.allComponents
        }
        .mapNotNull { it.moduleVersion }
        .filter { it.group == "org.jetbrains.kotlin" && it.name !in ALIGNMENT_EXEMPT_MODULES && it.version != expected }
        .map { "${it.name}:${it.version}" }
        .distinct()
        .toList()
    if (mismatched.isNotEmpty()) {
        throw GradleException(
            "Kotlin artifacts on the build script classpath differ from Kotlin Gradle plugin $expected: " +
                mismatched.joinToString() +
                ". A build plugin dependency is likely raising them; align it with the shared build conventions' " +
                "kotlin version.",
        )
    }
}
