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
    implementation(libs.kover.plugin)
}

gradlePlugin {
    plugins.create("kover") {
        id = "com.kelvsyc.internal.kover"
        implementationClass = "com.kelvsyc.internal.kover.KoverConventionPlugin"
    }
}

testing {
    suites.register<JvmTestSuite>("functionalTest") {
        useJUnitJupiter("5.14.1")

        dependencies {
            implementation(gradleTestKit())
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
                    ":kotlin-common:publishToMavenLocal",
                    ":kotlin-plugin:publishToMavenLocal",
                    ":kover-plugin:publishToMavenLocal",
                )
                systemProperty("conventions.version", project.version.toString())
                systemProperty("conventions.kover", libs.versions.kover.get())
                // The artifacts come from Maven Local, outside the test's classpath, so their
                // sources are declared as inputs: a catalog, platform or plugin change reruns the
                // tests instead of leaving them up to date.
                inputs.files(
                    listOf("catalog", "platform", "settings-plugin", "kotlin-common", "kotlin-plugin", "kover-plugin").map { module ->
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
