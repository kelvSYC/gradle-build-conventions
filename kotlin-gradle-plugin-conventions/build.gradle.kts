import org.gradle.kotlin.dsl.support.expectedKotlinDslPluginsVersion

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
    // The `kotlin-dsl` plugin the plugin kind applies, at the version the Gradle building this repository expects. The
    // catalog's `gradle` equals that Gradle (checkToolchainVersions), and consumers' wrappers follow it.
    implementation("org.gradle.kotlin:gradle-kotlin-dsl-plugins:$expectedKotlinDslPluginsVersion")
    // Dependency Analysis rules, the Kotlin toolchain checks and KotlinConventions. It brings the Dependency Analysis
    // plugin.
    implementation(project(":kotlin-common"))
    implementation(libs.asm)
}

// Embeds the versions the plugins add to consumers' builds, so they need nothing from the consumer's catalog.
val generateGradleConventionsProperties = tasks.register<WriteProperties>("generateGradleConventionsProperties") {
    destinationFile = layout.buildDirectory.file(
        "generated/gradleConventions/com/kelvsyc/internal/gradle/gradle-conventions.properties",
    )
    property("jspecify", libs.jspecify.get().version.toString())
}

sourceSets.named("main") {
    resources.srcDir(
        files(layout.buildDirectory.dir("generated/gradleConventions")).builtBy(generateGradleConventionsProperties),
    )
}

gradlePlugin {
    plugins.create("kotlinGradleLibrary") {
        id = "com.kelvsyc.internal.kotlin-gradle-library"
        implementationClass = "com.kelvsyc.internal.gradle.KotlinGradleLibraryPlugin"
    }

    plugins.create("kotlinGradlePlugin") {
        id = "com.kelvsyc.internal.kotlin-gradle-plugin"
        implementationClass = "com.kelvsyc.internal.gradle.KotlinGradlePluginPlugin"
    }
}

// The JDK the fixture builds' Gradle daemons run on: consumers such as gradle-tools run their daemon on JDK 21, so the
// plugins must load and work there.
val fixtureDaemonJdk = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(21)
}.map { it.metadata.installationPath.asFile.absolutePath }

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
                    ":kotlin-common:publishToMavenLocal",
                    ":kotlin-gradle-plugin-conventions:publishToMavenLocal",
                )
                systemProperty("conventions.version", project.version.toString())
                systemProperty("conventions.kotlin", libs.versions.kotlin.get())
                // The path is machine-specific, so it is passed as an argument rather than a tracked input.
                val daemonJdk = fixtureDaemonJdk
                jvmArgumentProviders.add(CommandLineArgumentProvider {
                    listOf("-Dconventions.fixtureDaemonJdk=${daemonJdk.get()}")
                })
                // The artifacts come from Maven Local, outside the test's classpath, so their
                // sources are declared as inputs: a catalog, platform or plugin change reruns the
                // tests instead of leaving them up to date.
                inputs.files(
                    listOf(
                        "catalog",
                        "platform",
                        "settings-plugin",
                        "kotlin-common",
                        "kotlin-gradle-plugin-conventions",
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
}

tasks.named("check") {
    dependsOn(testing.suites.named("functionalTest"))
}
