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
    implementation(libs.dokka.plugin)
    // dokka-core puts Jackson on every consumer's build script classpath at its own, vulnerable version. The BOM is
    // published with this plugin, so consumers resolve the patched version.
    implementation(platform(libs.jackson.bom))
    // Git remote parsing (GitHubRepository, parseGitHubRemote, readOriginUrl).
    implementation(project(":settings-plugin"))
    implementation(libs.jgit)
}

// Embeds the patched versions Dokka's own configurations are forced to, so the plugin needs nothing from the
// consumer's catalog.
val generateDokkaConventionsProperties = tasks.register<WriteProperties>("generateDokkaConventionsProperties") {
    destinationFile = layout.buildDirectory.file(
        "generated/dokkaConventions/com/kelvsyc/internal/dokka/dokka-conventions.properties",
    )
    property("jackson", libs.versions.jackson.get())
    property("jsoup", libs.versions.jsoup.get())
    property("freemarker", libs.versions.freemarker.get())
}

sourceSets.named("main") {
    resources.srcDir(
        files(layout.buildDirectory.dir("generated/dokkaConventions")).builtBy(generateDokkaConventionsProperties),
    )
}

gradlePlugin {
    plugins.create("dokka") {
        id = "com.kelvsyc.internal.dokka"
        implementationClass = "com.kelvsyc.internal.dokka.DokkaConventionPlugin"
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
                    ":catalog:publishToMavenLocal",
                    ":platform:publishToMavenLocal",
                    ":settings-plugin:publishToMavenLocal",
                    ":kotlin-plugin:publishToMavenLocal",
                    ":dokka-plugin:publishToMavenLocal",
                )
                systemProperty("conventions.version", project.version.toString())
                systemProperty("conventions.jackson", libs.versions.jackson.get())
                systemProperty("conventions.jsoup", libs.versions.jsoup.get())
                systemProperty("conventions.freemarker", libs.versions.freemarker.get())
                // The artifacts come from Maven Local, outside the test's classpath, so their
                // sources are declared as inputs: a catalog, platform or plugin change reruns the
                // tests instead of leaving them up to date.
                inputs.files(
                    listOf("catalog", "platform", "settings-plugin", "kotlin-plugin", "dokka-plugin").map { module ->
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
