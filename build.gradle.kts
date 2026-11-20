// Root build: component configuration lives in the subprojects.

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
