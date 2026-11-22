package com.kelvsyc.internal.publishing

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.URIish
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class GitHubPublishingPluginTest {

    @TempDir
    lateinit var dir: File

    private val conventionsVersion: String = System.getProperty("conventions.version")
        ?: error("System property conventions.version is not set")

    /**
     * Writes a `java-library` consumer build applying the publishing plugin at the version under
     * test with a `maven` publication of the `java` component, then commits it to a fresh git
     * repository (optionally with an `origin` remote).
     */
    private fun consumer(buildBody: String = "", origin: String? = null, extraPlugins: String = "") {
        File(dir, "settings.gradle.kts").writeText(
            """
            |pluginManagement {
            |    repositories {
            |        exclusiveContent {
            |            forRepository { mavenLocal() }
            |            filter {
            |                includeGroup("com.kelvsyc.internal")
            |                includeGroup("com.kelvsyc.internal.github-publishing")
            |            }
            |        }
            |        gradlePluginPortal()
            |    }
            |}
            |
            |dependencyResolutionManagement {
            |    repositories {
            |        exclusiveContent {
            |            forRepository { mavenLocal() }
            |            filter { includeGroup("com.kelvsyc.internal") }
            |        }
            |        mavenCentral()
            |    }
            |}
            |
            |rootProject.name = "consumer"
            """.trimMargin(),
        )
        File(dir, "build.gradle.kts").writeText(
            """
            |plugins {
            |    `java-library`
            |    $extraPlugins
            |    id("com.kelvsyc.internal.github-publishing") version "$conventionsVersion"
            |}
            |
            |group = "com.example"
            |version = "1.0"
            |
            |publishing {
            |    publications {
            |        create<MavenPublication>("maven") {
            |            from(components["java"])
            |        }
            |    }
            |}
            |
            |$buildBody
            """.trimMargin(),
        )
        File(dir, ".gitignore").writeText(".gradle/\n.kotlin/\nbuild/\n")

        Git.init().setDirectory(dir).setInitialBranch("main").call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("consumer").setSign(false).call()
            if (origin != null) git.remoteAdd().setName("origin").setUri(URIish(origin)).call()
        }
    }

    private fun runner(vararg args: String): GradleRunner = GradleRunner.create()
        .withProjectDir(dir)
        .withArguments(*args, "--configuration-cache", "--stacktrace")
        .forwardOutput()

    private fun build(vararg args: String): BuildResult = runner(*args).build()

    private fun buildAndFail(vararg args: String): BuildResult = runner(*args).buildAndFail()

    @Test
    fun repositoryUrlFromRemote() {
        consumer(
            buildBody = """
                |val gitHubPackages = publishing.repositories["GitHubPackages"] as MavenArtifactRepository
                |println("url=" + gitHubPackages.url)
            """.trimMargin(),
            origin = "https://github.com/kelvSYC/rifflet.git",
        )
        val result = build("help")
        assertTrue(result.output.contains("url=https://maven.pkg.github.com/kelvSYC/rifflet\n"), result.output)
    }

    @Test
    fun internalPlatformStripped() {
        consumer(
            buildBody = """
                |dependencies {
                |    implementation(platform("com.kelvsyc.internal:platform:$conventionsVersion"))
                |    implementation("org.apache.commons:commons-lang3")
                |}
            """.trimMargin(),
            origin = "https://github.com/kelvSYC/rifflet.git",
        )
        build("generatePomFileForMavenPublication", "generateMetadataFileForMavenPublication")

        val pom = File(dir, "build/publications/maven/pom-default.xml").readText()
        val module = File(dir, "build/publications/maven/module.json").readText()
        assertFalse(pom.contains("com.kelvsyc.internal"), pom)
        assertFalse(module.contains("com.kelvsyc.internal"), module)
        // The platform's version still reaches consumers through version mapping.
        assertTrue(pom.contains("<artifactId>commons-lang3</artifactId>"), pom)
        assertTrue(Regex("<artifactId>commons-lang3</artifactId>\\s*<version>").containsMatchIn(pom), pom)
    }

    @Test
    fun versionlessDependencyFails() {
        // A variant with its own usage has no resolution result for version mapping to use, like
        // the Kotlin Multiplatform target publications.
        consumer(
            buildBody = """
                |val customDependencies = configurations.dependencyScope("customDependencies")
                |val customElements = configurations.consumable("customElements") {
                |    extendsFrom(customDependencies.get())
                |    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("custom-usage")) }
                |}
                |(components["java"] as AdhocComponentWithVariants).addVariantsFromConfiguration(customElements.get()) {}
                |
                |dependencies {
                |    implementation("org.apache.commons:commons-lang3:3.17.0")
                |    "customDependencies"("org.example:versionless")
                |}
            """.trimMargin(),
            origin = "https://github.com/kelvSYC/rifflet.git",
        )
        val result = buildAndFail("generateMetadataFileForMavenPublication")
        assertTrue(result.output.contains("would publish dependencies without a version"), result.output)
        assertTrue(result.output.contains("org.example:versionless"), result.output)
    }

    /**
     * A publication without a component whose POM is written by hand, like the Kotlin Multiplatform
     * root publication, which does not honour version mapping: [dependencies] are
     * `group:artifact[:version]` strings appended to the POM's `dependencies`.
     */
    private fun handwrittenPublication(name: String, vararg dependencies: String): String {
        val appended = dependencies.joinToString("\n") { notation ->
            val parts = notation.split(':')
            val version = parts.getOrNull(2)?.let { """dependency.appendNode("version", "$it")""" }.orEmpty()
            """
            |        dependencies.appendNode("dependency").let { dependency ->
            |            dependency.appendNode("groupId", "${parts[0]}")
            |            dependency.appendNode("artifactId", "${parts[1]}")
            |            $version
            |        }
            """.trimMargin()
        }
        return """
            |publishing.publications.create<MavenPublication>("$name") {
            |    artifactId = "$name"
            |    pom.withXml {
            |        val dependencies = asNode().appendNode("dependencies")
            |$appended
            |    }
            |}
        """.trimMargin()
    }

    private val expensiveArtifacts = """
        |java {
        |    withJavadocJar()
        |    withSourcesJar()
        |}
    """.trimMargin()

    private fun assertConfigurationCacheReused(result: BuildResult) =
        assertTrue(result.output.contains("Reusing configuration cache"), result.output)

    @Test
    fun checkDoesNotBuildPublicationArtifacts() {
        consumer(buildBody = expensiveArtifacts, origin = "https://github.com/kelvSYC/rifflet.git")
        val result = build("check", "--dry-run")
        assertTrue(result.output.contains(":verifyPomDependencyVersions"), result.output)
        assertTrue(result.output.contains(":generatePomFileForMavenPublication"), result.output)
        for (artifactTask in listOf("jar", "javadocJar", "sourcesJar", "generateMetadataFileForMavenPublication")) {
            assertFalse(result.output.contains(":$artifactTask "), "$artifactTask in:\n${result.output}")
        }
    }

    @Test
    fun versionedDependenciesPassCheck() {
        consumer(
            buildBody = """
                |dependencies {
                |    implementation(platform("com.kelvsyc.internal:platform:$conventionsVersion"))
                |    implementation("org.apache.commons:commons-lang3")
                |}
                |
                |${handwrittenPublication("first", "org.example:one:1.0")}
                |${handwrittenPublication("second", "org.example:two:2.0")}
            """.trimMargin(),
            origin = "https://github.com/kelvSYC/rifflet.git",
        )
        val first = build("check")
        assertTrue(first.task(":verifyPomDependencyVersions")?.outcome?.name == "SUCCESS", first.output)
        assertConfigurationCacheReused(build("check", "--rerun-tasks"))
    }

    @Test
    fun versionlessDependencyFailsCheck() {
        consumer(
            buildBody = """
                |${handwrittenPublication("good", "org.example:one:1.0")}
                |${handwrittenPublication("bad", "org.example:versionless")}
            """.trimMargin(),
            origin = "https://github.com/kelvSYC/rifflet.git",
        )
        repeat(2) {
            val result = buildAndFail("check")
            assertTrue(result.output.contains("com.example:bad:1.0 would publish dependencies without a version"), result.output)
            assertTrue(result.output.contains("org.example:versionless"), result.output)
            assertFalse(result.output.contains("com.example:good"), result.output)
        }
    }

    @Test
    fun versionlessDependencyStillFailsModuleMetadataGeneration() {
        consumer(
            buildBody = """
                |val customDependencies = configurations.dependencyScope("customDependencies")
                |val customElements = configurations.consumable("customElements") {
                |    extendsFrom(customDependencies.get())
                |    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("custom-usage")) }
                |}
                |(components["java"] as AdhocComponentWithVariants).addVariantsFromConfiguration(customElements.get()) {}
                |
                |dependencies {
                |    implementation("org.apache.commons:commons-lang3:3.17.0")
                |    "customDependencies"("org.example:versionless")
                |}
            """.trimMargin(),
            origin = "https://github.com/kelvSYC/rifflet.git",
        )
        repeat(2) {
            val result = buildAndFail("generateMetadataFileForMavenPublication")
            assertTrue(result.output.contains("would publish dependencies without a version"), result.output)
            assertTrue(result.output.contains("org.example:versionless"), result.output)
        }
    }

    @Test
    fun gradlePluginMarkerPublicationsPassCheck() {
        File(dir, "src/main/java/example").mkdirs()
        File(dir, "src/main/java/example/ExamplePlugin.java").writeText(
            """
            |package example;
            |
            |public class ExamplePlugin implements org.gradle.api.Plugin<org.gradle.api.Project> {
            |    public void apply(org.gradle.api.Project project) {}
            |}
            """.trimMargin(),
        )
        consumer(
            buildBody = """
                |$expensiveArtifacts
                |
                |gradlePlugin {
                |    plugins.create("example") {
                |        id = "com.example.plugin"
                |        implementationClass = "example.ExamplePlugin"
                |    }
                |}
            """.trimMargin(),
            origin = "https://github.com/kelvSYC/rifflet.git",
            extraPlugins = "`java-gradle-plugin`",
        )
        val dry = build("check", "--dry-run")
        assertTrue(dry.output.contains(":generatePomFileForExamplePluginMarkerMavenPublication"), dry.output)
        for (artifactTask in listOf("jar", "javadocJar", "sourcesJar")) {
            assertFalse(dry.output.contains(":$artifactTask "), "$artifactTask in:\n${dry.output}")
        }
        repeat(2) {
            val result = build("check")
            assertTrue(result.task(":verifyPomDependencyVersions") != null, result.output)
        }
    }

    @Test
    fun platformSuppliedVersionStrippedFromGeneratedPomFailsCheck() {
        consumer(
            buildBody = """
                |val extraDependencies = configurations.dependencyScope("extraDependencies")
                |val extraElements = configurations.consumable("extraElements") {
                |    extendsFrom(extraDependencies.get())
                |    attributes {
                |        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
                |        attribute(Attribute.of("example.flavor", String::class.java), "extra")
                |    }
                |}
                |(components["java"] as AdhocComponentWithVariants).addVariantsFromConfiguration(extraElements.get()) {}
                |
                |dependencies {
                |    "extraDependencies"(platform("com.kelvsyc.internal:platform:$conventionsVersion"))
                |    "extraDependencies"("org.apache.commons:commons-lang3")
                |}
            """.trimMargin(),
            origin = "https://github.com/kelvSYC/rifflet.git",
        )
        // A variant that is not resolvable gets no version mapping (like the Kotlin Multiplatform
        // target publications), so its platform-supplied version is missing from the POM.
        build("generatePomFileForMavenPublication")
        val pom = File(dir, "build/publications/maven/pom-default.xml").readText()
        assertFalse(Regex("<artifactId>commons-lang3</artifactId>\\s*<version>").containsMatchIn(pom), pom)
        repeat(2) {
            val result = buildAndFail("check")
            assertTrue(result.output.contains("would publish dependencies without a version"), result.output)
            assertTrue(result.output.contains("org.apache.commons:commons-lang3"), result.output)
        }
    }

    @Test
    fun noRemoteFailsClearly() {
        consumer()
        // Builds that do not publish to GitHub Packages are unaffected.
        build("assemble", "generatePomFileForMavenPublication")

        val result = buildAndFail("publishMavenPublicationToGitHubPackagesRepository")
        assertTrue(result.output.contains("'origin'"), result.output)
    }
}
