plugins {
    `kotlin-dsl`
    `maven-publish`
    `jvm-test-suite`
}

group = "com.kelvsyc.internal"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.asm)
    implementation("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
    // The source sets of Kotlin JVM and Kotlin Multiplatform projects. The applied Kotlin Gradle plugin is the compiler
    // version, so it must be exactly the catalog's `kotlin` (Gradle's embedded Kotlin), as in the Kotlin conventions.
    implementation("${libs.kotlin.plugin.get().module}:${libs.versions.kotlin.get()}")
    // Records TestKit builds' coverage in projects with `java-gradle-plugin`.
    implementation(
        libs.plugins.gradle.testkit.jacoco.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" },
    )
}

// Embeds the JaCoCo version, so the plugin needs nothing from the consumer's catalog.
val generateCoverageProperties = tasks.register<WriteProperties>("generateCoverageProperties") {
    destinationFile = layout.buildDirectory.file(
        "generated/coverage/com/kelvsyc/internal/coverage/coverage.properties",
    )
    property("jacoco", libs.versions.jacoco.get())
}

sourceSets.named("main") {
    resources.srcDir(files(layout.buildDirectory.dir("generated/coverage")).builtBy(generateCoverageProperties))
}

gradlePlugin {
    plugins.create("coverage") {
        id = "com.kelvsyc.internal.coverage"
        implementationClass = "com.kelvsyc.internal.coverage.CoveragePlugin"
    }
}

testing.suites.named<JvmTestSuite>("test") {
    useJUnitJupiter("5.14.1")
}

val fixtures = sourceSets.register("fixtures")

tasks.named<Test>("test") {
    val compileFixtures = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileFixturesKotlin")
    dependsOn(compileFixtures)
    systemProperty("fixtures.classes", compileFixtures.get().destinationDirectory.get().asFile.path)
}

testing.suites.register<JvmTestSuite>("functionalTest") {
    useJUnitJupiter("5.14.1")

    dependencies {
        // The engine's JaCoCo XML parser, to assert on the fixtures' reports.
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
                ":kotlin-common:publishToMavenLocal",
                ":kotlin-plugin:publishToMavenLocal",
                ":kotlin-gradle-plugin-conventions:publishToMavenLocal",
                ":coverage-plugin:publishToMavenLocal",
            )
            systemProperty("conventions.version", project.version.toString())
            systemProperty("conventions.jacoco", libs.versions.jacoco.get())
            // The artifacts come from Maven Local, outside the test's classpath, so their
            // sources are declared as inputs: a catalog, platform or plugin change reruns the
            // tests instead of leaving them up to date.
            inputs.files(
                listOf(
                    "catalog",
                    "platform",
                    "settings-plugin",
                    "kotlin-common",
                    "kotlin-plugin",
                    "kotlin-gradle-plugin-conventions",
                    "coverage-plugin",
                ).map { module ->
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

tasks.named("check") {
    dependsOn(testing.suites.named("functionalTest"))
}
