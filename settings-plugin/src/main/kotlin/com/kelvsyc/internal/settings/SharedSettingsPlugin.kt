package com.kelvsyc.internal.settings

import com.kelvsyc.internal.semver.SemverSettingsPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.initialization.Settings
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.maven

/**
 * The `kelvsyc` settings extension.
 */
abstract class KelvsycSettingsExtension {
    /**
     * The group applied to every project of the build that does not set its own. Defaults to
     * `"com.kelvsyc"`.
     */
    abstract val group: Property<String>
}

/**
 * Settings plugin wiring a build into the shared build conventions.
 *
 * It applies the foojay toolchain resolver and [SemverSettingsPlugin], declares the plugin and
 * dependency repositories (Gradle Plugin Portal, Maven Central and the GitHub Packages
 * repositories of `kelvSYC/gradle-build-conventions` and `kelvSYC/kotlin-tools`), registers the
 * shared catalog published at [SharedConventions.VERSION] as `libs`, and applies the `kelvsyc`
 * extension's group to every project. `libs` also gets the shared platform at that version, as
 * `libs.conventions.platform`, and the shared Kotlin and Dokka convention plugin artifacts, as
 * `libs.conventions.kotlin.plugin` and `libs.conventions.dokka.plugin`, for builds whose own convention plugins
 * depend on them.
 *
 * Plugin requests in exactly the `com.kelvsyc.internal` namespace that carry no version, such as
 * `id("com.kelvsyc.internal.github-publishing")` in a build script, resolve at
 * [SharedConventions.VERSION]. This keeps every shared convention plugin at the same version as
 * this one, which matters beyond convenience: these classes are loaded in the settings
 * classloader, ahead of any copy another plugin of a different version brings along. Plugins in
 * a longer namespace, such as a build's own `com.kelvsyc.internal.kotlin-tools.*` conventions,
 * are left alone.
 *
 * GitHub Packages credentials come from the `gpr.user`/`gpr.key` Gradle properties, falling back
 * to the `GITHUB_ACTOR`/`GITHUB_TOKEN` environment variables.
 */
class SharedSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        settings.plugins.apply("org.gradle.toolchains.foojay-resolver-convention")
        settings.plugins.apply(SemverSettingsPlugin::class.java)

        val extension = settings.extensions.create("kelvsyc", KelvsycSettingsExtension::class.java)
        extension.group.convention("com.kelvsyc")

        settings.pluginManagement.resolutionStrategy.eachPlugin {
            if (requested.version == null && requested.id.namespace == SHARED_NAMESPACE) {
                useVersion(SharedConventions.VERSION)
            }
        }

        settings.pluginManagement.repositories {
            gradlePluginPortal()
            gitHubPackages(settings, "gradle-build-conventions", "GitHubPackagesBuildConventions")
            gitHubPackages(settings, "kotlin-tools", "GitHubPackagesKotlinTools")
        }

        settings.dependencyResolutionManagement {
            repositories {
                mavenCentral()
                gitHubPackages(settings, "gradle-build-conventions", "GitHubPackagesBuildConventions")
                gitHubPackages(settings, "kotlin-tools", "GitHubPackagesKotlinTools")
                maven("https://repo.gradle.org/gradle/libs-releases") {
                    name = "GradleLibsReleases"
                    content { includeGroup("org.gradle.experimental") }
                }
            }
            versionCatalogs.register("libs") {
                from("com.kelvsyc.internal:catalog:${SharedConventions.VERSION}")
                library("conventions-platform", "com.kelvsyc.internal", "platform").version(SharedConventions.VERSION)
                // For builds whose own convention plugins build on the shared ones.
                library("conventions-kotlin-plugin", "com.kelvsyc.internal", "kotlin-plugin")
                    .version(SharedConventions.VERSION)
                library("conventions-dokka-plugin", "com.kelvsyc.internal", "dokka-plugin")
                    .version(SharedConventions.VERSION)
            }
        }

        settings.gradle.beforeProject {
            // By now the project's gradle.properties and any parent allprojects/subprojects block
            // have been applied, so only replace Gradle's own default; the build script runs
            // afterwards and can still set its own.
            if (group.toString() == gradleDefaultGroup(this)) {
                group = extension.group.get()
            }
        }
    }

    /**
     * The group Gradle assigns a project that never sets one: empty for the root project, else
     * the root project's name followed by the parent's path segments, joined with `.`.
     */
    private fun gradleDefaultGroup(project: Project): String {
        val parent = project.parent ?: return ""
        val parentSegments = parent.path.removePrefix(":").split(':').filter { it.isNotEmpty() }
        return (listOf(project.rootProject.name) + parentSegments).joinToString(".")
    }

    private fun RepositoryHandler.gitHubPackages(settings: Settings, repository: String, repositoryName: String) {
        val user = settings.providers.gradleProperty("gpr.user")
            .orElse(settings.providers.environmentVariable("GITHUB_ACTOR"))
        val key = settings.providers.gradleProperty("gpr.key")
            .orElse(settings.providers.environmentVariable("GITHUB_TOKEN"))
        maven("https://maven.pkg.github.com/kelvSYC/$repository") {
            name = repositoryName
            credentials {
                username = user.orNull
                password = key.orNull
            }
        }
    }

    private companion object {
        /** The plugin ID namespace of the shared build conventions. */
        const val SHARED_NAMESPACE = "com.kelvsyc.internal"
    }
}
