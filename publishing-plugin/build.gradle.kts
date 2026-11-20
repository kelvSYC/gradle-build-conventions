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

dependencies {
    // Git remote parsing (GitHubRepository, parseGitHubRemote, readOriginUrl).
    implementation(project(":settings-plugin"))
}

gradlePlugin {
    plugins.create("githubPublishing") {
        id = "com.kelvsyc.internal.github-publishing"
        implementationClass = "com.kelvsyc.internal.publishing.GitHubPublishingPlugin"
    }
}

testing {
    suites.register<JvmTestSuite>("functionalTest") {
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
                // The artifacts come from Maven Local, outside the test's classpath, so their
                // sources are declared as inputs: a catalog, platform or plugin change reruns the
                // tests instead of leaving them up to date.
                inputs.files(
                    listOf("catalog", "platform", "settings-plugin", "publishing-plugin").map { module ->
                        rootProject.layout.projectDirectory.dir(module).asFileTree.matching {
                            exclude("build/", ".gradle/", ".kotlin/", "src/*Test/")
                        }
                    },
                )
                    .withPropertyName("publishedModuleSources")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                // The shared catalog sits at the root (gradle/libs.versions.toml), outside the module directories above.
                inputs.file(rootProject.layout.projectDirectory.file("gradle/libs.versions.toml"))
                    .withPropertyName("sharedCatalog")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("functionalTest"))
}
