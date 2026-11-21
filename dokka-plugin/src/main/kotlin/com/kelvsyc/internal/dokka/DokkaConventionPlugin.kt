package com.kelvsyc.internal.dokka

import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.dokka.gradle.DokkaExtension

/**
 * Dokka convention: applies `org.jetbrains.dokka`, and makes `assemble` generate the HTML documentation.
 *
 * - Every source set links the JDK and Kotlin standard library documentation, and links its declarations to their
 *   sources on GitHub (see [gitHubSourceLink]) when the `origin` git remote is a GitHub repository.
 * - With the shared Kotlin Multiplatform JVM convention, the JDK link is for the `kelvsycKotlin` extension's
 *   `jvmRelease`. That convention packages the HTML documentation as the `jvm` publication's javadoc jar.
 * - A project with the `java` plugin and without Kotlin Multiplatform also gets `org.jetbrains.dokka-javadoc`, and
 *   a javadoc jar of the Dokka Javadoc output. Its JDK link is for the release `compileJava` targets, or else the
 *   version of its Java toolchain, or else the version of the JDK running Gradle.
 * - Dokka's own configurations resolve the patched versions of the vulnerable libraries Dokka bundles (see
 *   [forceDokkaDependencyVersions]).
 *
 * The plugin also applies to projects without Kotlin or Java, such as documentation aggregators.
 *
 * The JDK version is set rather than given as a convention: Dokka itself sets it on every source set of a project
 * with Java (including Kotlin Multiplatform JVM) to the Java toolchain's version, which would hide a convention. This
 * plugin's setting comes after Dokka's, and a build script's own `dokkaSourceSets.configureEach { jdkVersion.set(...) }`
 * comes after this plugin's, so it still wins.
 */
class DokkaConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(LifecycleBasePlugin::class.java)
        project.pluginManager.apply(DOKKA_PLUGIN_ID)
        project.forceDokkaDependencyVersions()

        val dokka = project.extensions.getByType(DokkaExtension::class.java)
        val gitHubUrl = project.gitHubSourceLink()
        dokka.dokkaSourceSets.configureEach {
            enableJdkDocumentationLink.set(true)
            enableKotlinStdLibDocumentationLink.set(true)
            if (gitHubUrl != null) {
                sourceLink { remoteUrl.set(gitHubUrl) }
            }
        }

        project.pluginManager.withPlugin(KOTLIN_MULTIPLATFORM_JVM_PLUGIN_ID) {
            val release = kelvsycKotlinJvmRelease(project)
            dokka.dokkaSourceSets.configureEach { jdkVersion.set(release) }
        }

        project.pluginManager.withPlugin("java") {
            // Dokka's Javadoc format does not support Kotlin Multiplatform (https://github.com/Kotlin/dokka/issues/1753).
            if (!project.pluginManager.hasPlugin(KOTLIN_MULTIPLATFORM_PLUGIN_ID)) {
                project.configureDokkaJavadoc(dokka)
            }
        }

        project.tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME) {
            dependsOn(project.tasks.named("dokkaGeneratePublicationHtml"))
        }
    }

    private companion object {
        const val DOKKA_PLUGIN_ID = "org.jetbrains.dokka"
        const val KOTLIN_MULTIPLATFORM_PLUGIN_ID = "org.jetbrains.kotlin.multiplatform"
        const val KOTLIN_MULTIPLATFORM_JVM_PLUGIN_ID = "com.kelvsyc.internal.kotlin-multiplatform-jvm"
    }
}

private fun Project.configureDokkaJavadoc(dokka: DokkaExtension) {
    pluginManager.apply("org.jetbrains.dokka-javadoc")
    val java = extensions.getByType(JavaPluginExtension::class.java)
    // Link the API documentation of the JDK the output is released for, not the one it is compiled with.
    val release: Provider<Int> = tasks.named("compileJava", JavaCompile::class.java)
        .flatMap { it.options.release }
        .orElse(java.toolchain.languageVersion.map { it.asInt() })
        // Without a toolchain, the JDK running Gradle compiles.
        .orElse(JavaVersion.current().majorVersion.toInt())
    dokka.dokkaSourceSets.configureEach { jdkVersion.set(release) }

    java.withJavadocJar()
    tasks.named("javadocJar", Jar::class.java) {
        from(tasks.named("dokkaGeneratePublicationJavadoc"))
    }
}

/**
 * The `jvmRelease` of the shared Kotlin conventions' `kelvsycKotlin` extension.
 *
 * The property is read through its getter, not the extension's type: the Kotlin conventions may be loaded by a
 * different class loader than this plugin (from another project's build script classpath), so the type is not
 * necessarily visible here.
 */
private fun kelvsycKotlinJvmRelease(project: Project): Provider<Int> {
    val extension = project.extensions.getByName(KELVSYC_KOTLIN_EXTENSION)
    val getter = extension.javaClass.methods.singleOrNull { it.name == "getJvmRelease" && it.parameterCount == 0 }
    val property = getter?.invoke(extension) as? Provider<*>
        ?: throw GradleException(mismatchedConventions(project, "has no jvmRelease property"))
    return property.map { it as? Int ?: throw GradleException(mismatchedConventions(project, "jvmRelease is not an Int")) }
}

private fun mismatchedConventions(project: Project, problem: String): String =
    "The $KELVSYC_KOTLIN_EXTENSION extension of ${project.path} $problem, so com.kelvsyc.internal.dokka cannot read " +
        "the JVM release from com.kelvsyc.internal.kotlin-multiplatform-jvm. Both plugins must come from the same " +
        "gradle-build-conventions version."

private const val KELVSYC_KOTLIN_EXTENSION = "kelvsycKotlin"
