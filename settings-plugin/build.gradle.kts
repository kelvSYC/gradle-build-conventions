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
    implementation(libs.jgit)
    implementation(libs.foojay.resolver.plugin)
}

// Embeds this build's version so the plugin can request the catalog published alongside it.
val generateSharedConventionsVersion = tasks.register<WriteProperties>("generateSharedConventionsVersion") {
    destinationFile = layout.buildDirectory.file(
        "generated/sharedConventionsVersion/com/kelvsyc/internal/settings/version.properties",
    )
    property("version", project.version.toString())
}

sourceSets.named("main") {
    resources.srcDir(
        files(layout.buildDirectory.dir("generated/sharedConventionsVersion"))
            .builtBy(generateSharedConventionsVersion),
    )
}

gradlePlugin {
    plugins.create("semver") {
        id = "com.kelvsyc.internal.semver"
        implementationClass = "com.kelvsyc.internal.semver.SemverSettingsPlugin"
    }

    plugins.create("settings") {
        id = "com.kelvsyc.internal.settings"
        implementationClass = "com.kelvsyc.internal.settings.SharedSettingsPlugin"
    }
}

testing {
    suites.named<JvmTestSuite>("test") {
        useJUnitJupiter("5.14.1")
    }

    suites.register<JvmTestSuite>("functionalTest") {
        useJUnitJupiter("5.14.1")

        dependencies {
            implementation(project())
            implementation(gradleTestKit())
            implementation(libs.jgit)
        }

        targets.configureEach {
            testTask.configure {
                // Test builds resolve the artifacts under test from Maven Local, never from a
                // publishing repository: any repository in `publishing.repositories` would join
                // the `publish` lifecycle.
                dependsOn(
                    ":catalog:publishToMavenLocal",
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
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("functionalTest"))
}
