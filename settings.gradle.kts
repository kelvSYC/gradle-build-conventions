plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "gradle-build-conventions"

include("catalog", "platform", "settings-plugin", "publishing-plugin")
