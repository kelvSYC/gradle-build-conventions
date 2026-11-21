package com.kelvsyc.internal.kotlin

import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension

/**
 * Configures the project so the published JVM output only requires [KelvsycKotlinExtension.jvmRelease] from
 * consumers, while [KelvsycKotlinExtension.jvmToolchain] compiles it.
 *
 * Lowering `jvmTarget` alone is not enough: compiling against a newer JDK's class library lets newer members into the
 * bytecode (e.g. `MutableList.removeFirst()` resolves to the JDK 21 `java.util.List.removeFirst`). `-Xjdk-release`
 * compiles against the release's API instead. The Java release also determines the published `org.gradle.jvm.version`
 * of `java` components, and tests run on the release JDK to catch behaviour that differs from newer JDKs.
 */
internal fun Project.configureJvmCompatibility(
    conventions: KelvsycKotlinExtension,
    kotlin: KotlinProjectExtension,
    compilerOptions: KotlinJvmCompilerOptions,
) {
    compilerOptions.jvmTarget.set(conventions.jvmRelease.map { JvmTarget.fromTarget(it.toString()) })
    compilerOptions.freeCompilerArgs.add(conventions.jvmRelease.map { "-Xjdk-release=$it" })

    kotlin.jvmToolchain { languageVersion.set(conventions.jvmToolchain.map(JavaLanguageVersion::of)) }

    tasks.withType(JavaCompile::class.java).configureEach {
        options.release.set(conventions.jvmRelease)
    }

    val releaseLauncher = extensions.getByType(JavaToolchainService::class.java).launcherFor {
        languageVersion.set(conventions.jvmRelease.map(JavaLanguageVersion::of))
    }
    tasks.withType(Test::class.java).configureEach {
        javaLauncher.set(releaseLauncher)
    }

    requireJvmApiWithinCoreLibraries(kotlin)
}
