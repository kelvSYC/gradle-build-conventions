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
    // The applied Kotlin Gradle plugin is the compiler version, so it must be exactly the catalog's `kotlin` (Gradle's
    // embedded Kotlin): checkKotlinPluginVersion fails consumer builds otherwise.
    implementation("${libs.kotlin.plugin.get().module}:${libs.versions.kotlin.get()}")
    implementation(libs.dependency.analysis.plugin)
    implementation(libs.kotest.plugin)
    implementation(libs.ksp.plugin)
}

// Embeds this build's version and the compiler Kotlin version, so the plugins need nothing from the consumer's catalog.
val generateKotlinConventionsProperties = tasks.register<WriteProperties>("generateKotlinConventionsProperties") {
    destinationFile = layout.buildDirectory.file(
        "generated/kotlinConventions/com/kelvsyc/internal/kotlin/kotlin-conventions.properties",
    )
    property("version", project.version.toString())
    property("kotlin", libs.versions.kotlin.get())
}

sourceSets.named("main") {
    resources.srcDir(
        files(layout.buildDirectory.dir("generated/kotlinConventions")).builtBy(generateKotlinConventionsProperties),
    )
}

gradlePlugin {
    plugins.create("kotlinMultiplatformBase") {
        id = "com.kelvsyc.internal.kotlin-multiplatform-base"
        implementationClass = "com.kelvsyc.internal.kotlin.KotlinMultiplatformBasePlugin"
    }

    plugins.create("kotlinMultiplatformLibrary") {
        id = "com.kelvsyc.internal.kotlin-multiplatform-library"
        implementationClass = "com.kelvsyc.internal.kotlin.KotlinMultiplatformLibraryPlugin"
    }

    plugins.create("kotlinMultiplatformJvm") {
        id = "com.kelvsyc.internal.kotlin-multiplatform-jvm"
        implementationClass = "com.kelvsyc.internal.kotlin.KotlinMultiplatformJvmPlugin"
    }

    plugins.create("kotlinMultiplatformJs") {
        id = "com.kelvsyc.internal.kotlin-multiplatform-js"
        implementationClass = "com.kelvsyc.internal.kotlin.KotlinMultiplatformJsPlugin"
    }

    plugins.create("kotlinJvmLibrary") {
        id = "com.kelvsyc.internal.kotlin-jvm-library"
        implementationClass = "com.kelvsyc.internal.kotlin.KotlinJvmLibraryPlugin"
    }
}

testing {
    suites.register<JvmTestSuite>("functionalTest") {
        useJUnitJupiter("5.14.1")

        dependencies {
            implementation(gradleTestKit())
            implementation(libs.asm)
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
                    ":kotlin-plugin:publishToMavenLocal",
                )
                systemProperty("conventions.version", project.version.toString())
                systemProperty("conventions.kotlin", libs.versions.kotlin.get())
                // The artifacts come from Maven Local, outside the test's classpath, so their
                // sources are declared as inputs: a catalog, platform or plugin change reruns the
                // tests instead of leaving them up to date.
                inputs.files(
                    listOf("catalog", "platform", "settings-plugin", "kotlin-plugin").map { module ->
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
