// Root build: component configuration lives in the subprojects.

plugins {
    base
}

// The only publishing repository in this build: `publish` is the GitHub Packages release path.
// Tests resolve artifacts through `publishToMavenLocal`, so no other repository may be added.
subprojects {
    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/kelvSYC/gradle-build-conventions")
                    credentials {
                        username = providers.gradleProperty("gpr.user")
                            .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                        password = providers.gradleProperty("gpr.key")
                            .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                    }
                }
            }
        }
    }
}

// Keeps the shared catalog's toolchain versions honest: consumers' Gradle wrappers follow the catalog's `gradle`
// version, and the compiler Kotlin (catalog `kotlin`) must follow Gradle's embedded Kotlin.
val checkToolchainVersions = tasks.register("checkToolchainVersions") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks the catalog's gradle and kotlin versions against the wrapper and Gradle's embedded Kotlin."

    val libs = versionCatalogs.named("libs")
    val catalogGradle = libs.findVersion("gradle").map { it.requiredVersion }.orElse("<missing>")
    val catalogKotlin = libs.findVersion("kotlin").map { it.requiredVersion }.orElse("<missing>")
    val wrapperProperties = layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties")
    val wrapperGradle = providers.fileContents(wrapperProperties).asText.map { text ->
        Regex("""/gradle-([^/]+)-(?:bin|all)\.zip""").find(text)?.groupValues?.get(1) ?: "<unparseable>"
    }
    val runningGradle = gradle.gradleVersion
    val embeddedKotlin = embeddedKotlinVersion
    inputs.property("catalogGradle", catalogGradle)
    inputs.property("catalogKotlin", catalogKotlin)
    inputs.property("wrapperGradle", wrapperGradle)
    inputs.property("embeddedKotlin", embeddedKotlin)

    doLast {
        val wrapper = wrapperGradle.get()
        // Consumers' wrappers follow the catalog's `gradle` version, so it must be a stable release.
        check(Regex("""[0-9]+(\.[0-9]+)+""").matches(wrapper)) {
            "The wrapper uses Gradle $wrapper, which is not a stable release; only stable Gradle releases are allowed."
        }
        check(catalogGradle == wrapper) {
            "Catalog gradle = $catalogGradle but the wrapper uses Gradle $wrapper; update both together."
        }
        check(catalogKotlin == embeddedKotlin) {
            "Catalog kotlin = $catalogKotlin but Gradle $runningGradle embeds Kotlin $embeddedKotlin; " +
                "the compiler Kotlin must follow Gradle's embedded Kotlin."
        }
    }
}

tasks.named("check") {
    dependsOn(checkToolchainVersions)
}
