pluginManagement {
    includeBuild("build-logic")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.kelvsyc.internal.semver")
}

dependencyResolutionManagement {
    versionCatalogs {
        register("libs") {
            from(files("catalog/libs.versions.toml"))
        }
    }
}

rootProject.name = "gradle-build-conventions"

include("catalog", "platform", "settings-plugin", "publishing-plugin")
