plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
    `jvm-test-suite`
}

group = "com.kelvsyc.internal"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Git remote parsing (GitHubRepository, parseGitHubRemote, readOriginUrl).
    implementation(project(":settings-plugin"))
}

gradlePlugin {
    plugins {
        create("githubPublishing") {
            id = "com.kelvsyc.internal.github-publishing"
            implementationClass = "com.kelvsyc.internal.publishing.GitHubPublishingPlugin"
        }
    }
}

testing {
    suites {
        register<JvmTestSuite>("functionalTest") {
            useJUnitJupiter("5.14.1")

            dependencies {
                implementation(gradleTestKit())
                implementation(libs.jgit)
            }

            targets.configureEach {
                testTask.configure {
                    // Test builds resolve the artifacts under test from Maven Local, never from a
                    // publishing repository: any repository in `publishing.repositories` would join
                    // the `publish` lifecycle.
                    dependsOn(
                        ":platform:publishToMavenLocal",
                        ":settings-plugin:publishToMavenLocal",
                        ":publishing-plugin:publishToMavenLocal",
                    )
                    systemProperty("conventions.version", project.version.toString())
                }
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("functionalTest"))
}
