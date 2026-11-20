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

sourceSets.main {
    resources.srcDir(
        files(layout.buildDirectory.dir("generated/sharedConventionsVersion"))
            .builtBy(generateSharedConventionsVersion),
    )
}

gradlePlugin {
    plugins {
        create("semver") {
            id = "com.kelvsyc.internal.semver"
            implementationClass = "com.kelvsyc.internal.semver.SemverSettingsPlugin"
        }
        create("settings") {
            id = "com.kelvsyc.internal.settings"
            implementationClass = "com.kelvsyc.internal.settings.SharedSettingsPlugin"
        }
    }
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter("5.14.1")
        }

        register<JvmTestSuite>("functionalTest") {
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
