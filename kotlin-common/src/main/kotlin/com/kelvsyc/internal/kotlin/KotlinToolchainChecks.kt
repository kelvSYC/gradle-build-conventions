package com.kelvsyc.internal.kotlin

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

/**
 * Fails if the applied Kotlin Gradle plugin differs from [KotlinConventions.KOTLIN], the compiler version these
 * conventions were released for.
 *
 * Conflict resolution on the build script classpath can silently raise the plugin to Gradle's embedded Kotlin version
 * (or the stdlib another build plugin requires).
 */
fun Project.checkKotlinPluginVersion() {
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
fun Project.checkKotlinToolchainAlignment() {
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
