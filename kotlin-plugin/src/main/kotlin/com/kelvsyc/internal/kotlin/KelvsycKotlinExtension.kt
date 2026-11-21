package com.kelvsyc.internal.kotlin

import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

/**
 * The `kelvsycKotlin` project extension: the compatibility floors of the published output, which every Kotlin
 * convention plugin applies.
 *
 * The defaults are shared by every build using the conventions, and a project can raise or lower them. Compiler
 * options a build script sets directly (for example `kotlin { compilerOptions { languageVersion.set(...) } }`) win over
 * these properties, since the build script runs after the plugin.
 */
abstract class KelvsycKotlinExtension {
    /**
     * The Kotlin language version, which is also the API version. Defaults to [DEFAULT_LANGUAGE_VERSION].
     */
    abstract val languageVersion: Property<KotlinVersion>

    /**
     * The version of `kotlin-stdlib` and the other Kotlin core libraries declared in published metadata. Defaults to
     * [DEFAULT_CORE_LIBRARIES_VERSION].
     *
     * On JVM targets the API version must not be above this version's `major.minor`, or the published output would
     * call standard library members its declared `kotlin-stdlib` lacks; compilation fails if it is.
     */
    abstract val coreLibrariesVersion: Property<String>

    /**
     * The Java release the JVM output targets, and the JDK tests run on. Defaults to [DEFAULT_JVM_RELEASE].
     */
    abstract val jvmRelease: Property<Int>

    /** The JDK that compiles the JVM output. Defaults to [DEFAULT_JVM_TOOLCHAIN]. */
    abstract val jvmToolchain: Property<Int>

    /** The Node.js version that runs JS tests. Defaults to [DEFAULT_NODE_VERSION]. */
    abstract val nodeVersion: Property<String>

    companion object {
        /** The extension's name. */
        const val NAME = "kelvsycKotlin"

        /** The lowest Kotlin version the current Kotlin Gradle plugin does not deprecate as a language version. */
        val DEFAULT_LANGUAGE_VERSION = KotlinVersion.KOTLIN_2_2

        /** A `kotlin-stdlib` release matching [DEFAULT_LANGUAGE_VERSION]. */
        const val DEFAULT_CORE_LIBRARIES_VERSION = "2.2.21"

        /** The oldest JDK consumers of the published JVM output are expected to run on. */
        const val DEFAULT_JVM_RELEASE = 17

        /** The newest LTS JDK. */
        const val DEFAULT_JVM_TOOLCHAIN = 25

        /**
         * Node.js 22 LTS. Node.js 24 bundles OpenSSL headers with architecture-specific `fipskey.h` symlinks that do not
         * resolve on Linux, which crashes `kotlinNodeJsSetup`.
         */
        const val DEFAULT_NODE_VERSION = "22.12.0"

        /** The project's extension, created with the defaults by the first convention plugin applied. */
        internal fun of(project: Project): KelvsycKotlinExtension =
            project.extensions.findByType(KelvsycKotlinExtension::class.java)
                ?: project.extensions.create(NAME, KelvsycKotlinExtension::class.java).apply {
                    languageVersion.convention(DEFAULT_LANGUAGE_VERSION)
                    coreLibrariesVersion.convention(DEFAULT_CORE_LIBRARIES_VERSION)
                    jvmRelease.convention(DEFAULT_JVM_RELEASE)
                    jvmToolchain.convention(DEFAULT_JVM_TOOLCHAIN)
                    nodeVersion.convention(DEFAULT_NODE_VERSION)
                }
    }
}
