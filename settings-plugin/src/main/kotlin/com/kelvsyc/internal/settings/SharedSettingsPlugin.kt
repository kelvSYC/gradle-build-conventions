package com.kelvsyc.internal.settings

import com.kelvsyc.internal.semver.SemverSettingsPlugin
import org.gradle.api.Plugin
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
 * extension's group to every project.
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
            }
        }

        settings.gradle.beforeProject {
            // Runs before the build script, so a project setting its own group still wins.
            group = extension.group.get()
        }
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
}
