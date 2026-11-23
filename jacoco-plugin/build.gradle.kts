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

// Embeds the catalog's JaCoCo version, so the plugin needs nothing from the consumer's catalog.
val generateJacocoConventionsProperties = tasks.register<WriteProperties>("generateJacocoConventionsProperties") {
    destinationFile = layout.buildDirectory.file(
        "generated/jacocoConventions/com/kelvsyc/internal/jacoco/jacoco-conventions.properties",
    )
    property("jacoco", libs.versions.jacoco.get())
}

sourceSets.named("main") {
    resources.srcDir(
        files(layout.buildDirectory.dir("generated/jacocoConventions")).builtBy(generateJacocoConventionsProperties),
    )
}

gradlePlugin {
    plugins.create("jacoco") {
        id = "com.kelvsyc.internal.jacoco"
        implementationClass = "com.kelvsyc.internal.jacoco.JacocoConventionPlugin"
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
                // Test builds resolve the artifact under test from Maven Local, never from a
                // publishing repository: any repository in `publishing.repositories` would join
                // the `publish` lifecycle.
                dependsOn(":jacoco-plugin:publishToMavenLocal")
                systemProperty("conventions.version", project.version.toString())
                systemProperty("conventions.jacoco", libs.versions.jacoco.get())
                // The artifact comes from Maven Local, outside the test's classpath, so its sources
                // are declared as inputs: a plugin change reruns the tests instead of leaving them
                // up to date.
                inputs.files(
                    rootProject.layout.projectDirectory.dir("jacoco-plugin").asFileTree.matching {
                        exclude("build/", ".gradle/", ".kotlin/", "src/*Test/")
                    },
                )
                    .withPropertyName("publishedModuleSources")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                // The shared catalog sits at the root (gradle/libs.versions.toml), outside the module directory above.
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
