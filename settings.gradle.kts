pluginManagement {
    includeBuild("build-logic")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.kelvsyc.internal.semver")
}

dependencyResolutionManagement {
    // Declared here, and only here, so Dependabot can see them: it reads repositories from settings'
    // dependencyResolutionManagement but not from plugins, and projects must not declare their own.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "gradle-build-conventions"

include("catalog", "platform", "settings-plugin", "publishing-plugin", "kotlin-plugin")
