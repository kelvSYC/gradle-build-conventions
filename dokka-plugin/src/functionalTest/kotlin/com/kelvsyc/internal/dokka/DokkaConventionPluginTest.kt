package com.kelvsyc.internal.dokka

import org.eclipse.jgit.api.Git
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * TestKit tests of the Dokka convention plugin, resolved from Maven Local at the version under test.
 *
 * Each fixture build is shared by the tests that assert on it: fixtures are built lazily, once per class. Every
 * fixture build runs twice with the configuration cache, and the second run must reuse it. Only [kmpJvm] generates
 * documentation (HTML and Javadoc), in Dokka's offline mode; the other fixtures inspect configuration.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DokkaConventionPluginTest {

    /** Shared by every test of the class, unlike a `@TempDir`, which JUnit creates per test. */
    private val root: File = Files.createTempDirectory("dokka-conventions").toFile()

    @AfterAll
    fun deleteFixtures() {
        root.deleteRecursively()
    }

    private val conventionsVersion: String = systemProperty("conventions.version")
    private val jackson: String = systemProperty("conventions.jackson")
    private val jsoup: String = systemProperty("conventions.jsoup")
    private val freemarker: String = systemProperty("conventions.freemarker")

    // Shared fixtures ------------------------------------------------------------------------------------------------

    /**
     * A git repository with a GitHub `origin` holding a composite build: the root is a documentation aggregator
     * without Kotlin or Java, applying the plugin without a version through the shared settings plugin, and the
     * included build `components/lib` is a `kotlin-jvm-library`.
     */
    private val composite: Fixture by lazy {
        val dir = File(root, "composite").apply { mkdirs() }
        write(
            dir,
            "settings.gradle.kts",
            """
            |pluginManagement {
            |    repositories {
            |        $MAVEN_LOCAL_CONVENTIONS
            |        gradlePluginPortal()
            |    }
            |}
            |
            |plugins {
            |    id("com.kelvsyc.internal.settings") version "$conventionsVersion"
            |}
            |
            |dependencyResolutionManagement {
            |    repositories {
            |        $MAVEN_LOCAL_CONVENTIONS
            |    }
            |}
            |
            |rootProject.name = "composite"
            |includeBuild("components/lib")
            """.trimMargin(),
        )
        write(
            dir,
            "build.gradle.kts",
            """
            |plugins {
            |    id("com.kelvsyc.internal.dokka")
            |}
            |
            |println("aggregator.kotlin=" + plugins.hasPlugin("org.jetbrains.kotlin.jvm"))
            |println("aggregator.java=" + plugins.hasPlugin("java"))
            |println("aggregator.dokka=" + plugins.hasPlugin("org.jetbrains.dokka"))
            """.trimMargin(),
        )
        writeSettings(File(dir, "components/lib"), "lib")
        write(
            dir,
            "components/lib/build.gradle.kts",
            """
            |plugins {
            |    id("com.kelvsyc.internal.kotlin-jvm-library") version "$conventionsVersion"
            |    id("com.kelvsyc.internal.dokka") version "$conventionsVersion"
            |    `maven-publish`
            |}
            |
            |group = "com.example"
            |version = "1.0"
            |
            |$PRINT_DOKKA
            |afterEvaluate {
            |    println("lib.dokkaJavadoc=" + plugins.hasPlugin("org.jetbrains.dokka-javadoc"))
            |}
            """.trimMargin(),
        )
        write(dir, "components/lib/src/main/kotlin/com/example/Answer.kt", "package com.example\n\nfun answer(): Int = 1\n")
        val sha = commit(dir, origin = "git@github.com:example-owner/example-repo.git")
        Fixture(dir, buildTwice(dir, ":lib:javadocJar", "assemble", "--dry-run"), sha)
    }

    /**
     * A Kotlin Multiplatform JVM library applying the Kotlin conventions, in a git repository with a GitHub `origin`.
     * Its documentation is generated (offline), and its Javadoc output packaged in the javadoc jar.
     */
    private val kmpJvm: Fixture by lazy {
        val dir = File(root, "kmp-jvm").apply { mkdirs() }
        writeSettings(dir, "kmp-jvm")
        write(
            dir,
            "build.gradle.kts",
            """
            |plugins {
            |    id("com.kelvsyc.internal.kotlin-multiplatform-library") version "$conventionsVersion"
            |    id("com.kelvsyc.internal.kotlin-multiplatform-jvm") version "$conventionsVersion"
            |    id("com.kelvsyc.internal.dokka") version "$conventionsVersion"
            |    `maven-publish`
            |}
            |
            |group = "com.example"
            |version = "1.0"
            |
            |dokka {
            |    dokkaPublications.configureEach { offlineMode.set(true) }
            |}
            |
            |$PRINT_DOKKA
            |afterEvaluate {
            |    println("kmp.java=" + plugins.hasPlugin("java"))
            |    println("kmp.dokkaJavadoc=" + plugins.hasPlugin("org.jetbrains.dokka-javadoc"))
            |    println("kmp.jvmPublicationClassifiers=" +
            |        publishing.publications.getByName<MavenPublication>("jvm").artifacts.map { it.classifier })
            |}
            """.trimMargin(),
        )
        write(
            dir,
            "src/commonMain/kotlin/com/example/Greeting.kt",
            "package com.example\n\n/** Greets. */\nfun greeting(): String = \"hello\"\n",
        )
        val sha = commit(dir, origin = "https://github.com/example-owner/kmp-repo.git")
        Fixture(dir, buildTwice(dir, "assemble", "javadocJar"), sha)
    }

    /**
     * A Kotlin Multiplatform library with JVM and JS targets. The JS convention is applied by the build script after
     * the Dokka and JVM conventions, so its target appears only once they are applied.
     */
    private val kmpJs: Fixture by lazy {
        val dir = File(root, "kmp-js").apply { mkdirs() }
        writeSettings(dir, "kmp-js")
        write(
            dir,
            "build.gradle.kts",
            """
            |plugins {
            |    id("com.kelvsyc.internal.kotlin-multiplatform-jvm") version "$conventionsVersion"
            |    id("com.kelvsyc.internal.dokka") version "$conventionsVersion"
            |    id("com.kelvsyc.internal.kotlin-multiplatform-js") version "$conventionsVersion" apply false
            |    `maven-publish`
            |}
            |
            |apply(plugin = "com.kelvsyc.internal.kotlin-multiplatform-js")
            |
            |group = "com.example"
            |version = "1.0"
            |
            |$PRINT_DOKKA
            |afterEvaluate {
            |    println("kmpJs.dokkaJavadoc=" + plugins.hasPlugin("org.jetbrains.dokka-javadoc"))
            |    println("kmpJs.jvmPublicationClassifiers=" +
            |        publishing.publications.getByName<MavenPublication>("jvm").artifacts.map { it.classifier })
            |}
            """.trimMargin(),
        )
        write(dir, "src/commonMain/kotlin/com/example/Greeting.kt", "package com.example\n\nfun greeting(): String = \"hello\"\n")
        Fixture(dir, buildTwice(dir, "javadocJar", "--dry-run"), sha = null)
    }

    /**
     * A `java-library` without Kotlin, released for Java 21, whose dependencies are older than the forced versions,
     * in a git repository whose `origin` is not on GitHub.
     */
    private val javaLibrary: Fixture by lazy {
        val dir = File(root, "java-library").apply { mkdirs() }
        writeSettings(dir, "java-library")
        write(
            dir,
            "build.gradle.kts",
            """
            |plugins {
            |    `java-library`
            |    id("com.kelvsyc.internal.dokka") version "$conventionsVersion"
            |}
            |
            |dependencies {
            |    implementation("com.fasterxml.jackson.core:jackson-databind:$OLD_JACKSON")
            |    implementation("org.jsoup:jsoup:$OLD_JSOUP")
            |    implementation("org.freemarker:freemarker:$OLD_FREEMARKER")
            |}
            |
            |tasks.compileJava { options.release.set($JAVA_LIBRARY_RELEASE) }
            |
            |dokka {
            |    dokkaSourceSets.configureEach { if (name == "javaTest") jdkVersion.set($OVERRIDDEN_JDK_VERSION) }
            |}
            |
            |$PRINT_DOKKA
            |
            |fun Configuration.resolvedRoot() = incoming.resolutionResult.rootComponent
            |val resolvedRoots = mapOf(
            |    "compile" to configurations.compileClasspath.flatMap { it.resolvedRoot() },
            |    "dokka" to configurations.named("dokkaHtmlGeneratorRuntimeResolver~internal").flatMap { it.resolvedRoot() },
            |)
            |
            |tasks.register("printResolved") {
            |    val roots = resolvedRoots
            |    doLast {
            |        roots.forEach { (name, root) ->
            |            val seen = mutableSetOf<org.gradle.api.artifacts.result.ResolvedComponentResult>()
            |            val queue = ArrayDeque(listOf(root.get()))
            |            while (queue.isNotEmpty()) {
            |                val component = queue.removeFirst()
            |                if (!seen.add(component)) continue
            |                val id = component.moduleVersion
            |                if (id != null) println("resolved." + name + "=" + id.group + ":" + id.name + ":" + id.version)
            |                component.dependencies
            |                    .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
            |                    .forEach { queue.add(it.selected) }
            |            }
            |        }
            |    }
            |}
            """.trimMargin(),
        )
        write(dir, "src/main/java/com/example/Answer.java", "package com.example;\n\npublic class Answer {}\n")
        commit(dir, origin = "https://gitlab.com/example-owner/example-repo.git")
        Fixture(dir, buildTwice(dir, "printResolved", "buildEnvironment"), sha = null)
    }

    // Source links ---------------------------------------------------------------------------------------------------

    @Test
    fun sourceLinkFromOrigin() {
        val output = composite.result.output
        assertTrue(
            output.contains("lib.main.sourceLinks=[https://github.com/example-owner/example-repo/blob/${composite.sha}/"),
            output,
        )
    }

    @Test
    fun sourceLinkPathFromOutermostCompositeRoot() {
        val output = composite.result.output
        assertTrue(
            output.contains(
                "lib.main.sourceLinks=[https://github.com/example-owner/example-repo/blob/${composite.sha}/components/lib]\n",
            ),
            output,
        )
    }

    @Test
    fun noSourceLinkWithoutGitHubOrigin() {
        val output = javaLibrary.result.output
        assertTrue(output.contains("java-library.dokkaSourceSets=[javaMain, javaTest]\n"), output)
        assertTrue(output.contains("java-library.javaMain.sourceLinks=[]\n"), output)
        assertTrue(output.contains("java-library.javaMain.jdkLink=true\n"), output)
    }

    // Lifecycle -------------------------------------------------------------------------------------------------------

    @Test
    fun assembleGeneratesHtml() {
        assertEquals(TaskOutcome.SUCCESS, kmpJvm.result.task(":dokkaGeneratePublicationHtml")?.outcome)
        val index = File(kmpJvm.dir, "build/dokka/html/index.html")
        assertTrue(index.isFile, "$index is missing")
        // Dokka links each declaration to its source through the configured source link.
        val greeting = File(kmpJvm.dir, "build/dokka/html").walk().filter { it.extension == "html" }
            .map { it.readText() }
            .any {
                it.contains(
                    "https://github.com/example-owner/kmp-repo/blob/${kmpJvm.sha}/src/commonMain/kotlin/com/example/Greeting.kt#L",
                )
            }
        assertTrue(greeting, "No generated page links Greeting.kt on GitHub")
    }

    @Test
    fun appliesToAggregatorWithoutKotlin() {
        val output = composite.result.output
        assertTrue(output.contains("aggregator.dokka=true\n"), output)
        assertTrue(output.contains("aggregator.kotlin=false\n"), output)
        assertTrue(output.contains("aggregator.java=false\n"), output)
        assertTrue(Regex("(?m)^:dokkaGeneratePublicationHtml SKIPPED$").containsMatchIn(output), output)
        assertTrue(Regex("(?m)^:assemble SKIPPED$").containsMatchIn(output), output)
    }

    // Javadoc jars ----------------------------------------------------------------------------------------------------

    @Test
    fun jvmLibraryJavadocFromDokkaJavadocWithJdk17() {
        val output = composite.result.output
        assertTrue(output.contains("lib.dokkaJavadoc=true\n"), output)
        assertTrue(output.contains("lib.main.jdkVersion=17\n"), output)
        assertTrue(output.contains("lib.main.jdkLink=true\n"), output)
        assertTrue(output.contains("lib.main.stdlibLink=true\n"), output)
        assertTrue(output.contains(":lib:dokkaGeneratePublicationJavadoc SKIPPED"), output)
        assertTrue(output.contains(":lib:javadocJar SKIPPED"), output)
        // Without the Kotlin conventions, the JDK link follows the Java compilation's release.
        val javaOutput = javaLibrary.result.output
        assertTrue(javaOutput.contains("java-library.javaMain.jdkVersion=$JAVA_LIBRARY_RELEASE\n"), javaOutput)
    }

    @Test
    fun buildScriptJdkVersionWins() {
        val output = javaLibrary.result.output
        assertTrue(output.contains("java-library.javaTest.jdkVersion=$OVERRIDDEN_JDK_VERSION\n"), output)
    }

    @Test
    fun kmpJvmOnlyJavadocJarFromDokkaJavadoc() {
        val output = kmpJvm.result.output
        assertTrue(output.contains("kmp.java=false\n"), output)
        assertTrue(output.contains("kmp.dokkaJavadoc=true\n"), output)
        assertTrue(output.contains("kmp-jvm.jvmMain.jdkVersion=17\n"), output)
        assertTrue(Regex("kmp\\.jvmPublicationClassifiers=\\[.*\\bjavadoc\\b.*]").containsMatchIn(output), output)
        assertEquals(TaskOutcome.SUCCESS, kmpJvm.result.task(":dokkaGeneratePublicationJavadoc")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, kmpJvm.result.task(":javadocJar")?.outcome)

        val entries = ZipFile(File(kmpJvm.dir, "build/libs/kmp-jvm-1.0-javadoc.jar")).use { jar ->
            jar.entries().asSequence().map { it.name }.toList()
        }
        // Javadoc output lists its packages in element-list and has no HTML-format navigation page.
        assertTrue("element-list" in entries, entries.toString())
        assertTrue("com/example/package-summary.html" in entries, entries.toString())
        assertFalse("navigation.html" in entries, entries.toString())
    }

    @Test
    fun kmpWithJsJavadocJarFromHtml() {
        val output = kmpJs.result.output
        assertTrue(output.contains("kmpJs.dokkaJavadoc=false\n"), output)
        assertTrue(output.contains("kmp-js.jvmMain.jdkVersion=17\n"), output)
        assertTrue(Regex("kmpJs\\.jvmPublicationClassifiers=\\[.*\\bjavadoc\\b.*]").containsMatchIn(output), output)
        assertTrue(Regex("(?m)^:dokkaGeneratePublicationHtml SKIPPED$").containsMatchIn(output), output)
        assertTrue(Regex("(?m)^:javadocJar SKIPPED$").containsMatchIn(output), output)
        assertFalse(output.contains("dokkaGeneratePublicationJavadoc"), output)
    }

    // Forced versions ------------------------------------------------------------------------------------------------

    @Test
    fun forcedVersionsOnlyOnDokkaConfigurations() {
        val output = javaLibrary.result.output
        for (module in JACKSON_MODULES) {
            assertTrue(output.contains("resolved.dokka=$module:$jackson\n"), "$module\n$output")
        }
        // jackson-annotations is versioned major.minor, from the forced jackson-bom.
        assertTrue(
            output.contains("resolved.dokka=com.fasterxml.jackson.core:jackson-annotations:${jackson.substringBeforeLast('.')}\n"),
            output,
        )
        assertTrue(output.contains("resolved.dokka=org.jsoup:jsoup:$jsoup\n"), output)
        assertTrue(output.contains("resolved.dokka=org.freemarker:freemarker:$freemarker\n"), output)

        assertTrue(output.contains("resolved.compile=com.fasterxml.jackson.core:jackson-databind:$OLD_JACKSON\n"), output)
        assertTrue(output.contains("resolved.compile=org.jsoup:jsoup:$OLD_JSOUP\n"), output)
        assertTrue(output.contains("resolved.compile=org.freemarker:freemarker:$OLD_FREEMARKER\n"), output)
    }

    @Test
    fun buildScriptClasspathGetsPatchedJackson() {
        val output = javaLibrary.result.output
        assertTrue(output.contains("com.fasterxml.jackson.core:jackson-databind:"), output)
        val versions = Regex("com\\.fasterxml\\.jackson\\.core:jackson-databind:[^ \\n]* -> ([^ \\n]+)").findAll(output)
            .map { it.groupValues[1] }.toSet()
        assertEquals(setOf(jackson), versions, output)
    }

    // Helpers ---------------------------------------------------------------------------------------------------------

    private class Fixture(val dir: File, val result: BuildResult, val sha: String?)

    private fun systemProperty(name: String): String =
        System.getProperty(name) ?: error("System property $name is not set")

    private fun write(dir: File, path: String, content: String) {
        File(dir, path).apply { parentFile.mkdirs() }.writeText(content)
    }

    /**
     * Writes a settings script resolving the artifacts under test from Maven Local only and provisioning JDKs through
     * the foojay resolver, and the build's Gradle properties.
     */
    private fun writeSettings(dir: File, name: String) {
        write(
            dir,
            "settings.gradle.kts",
            """
            |pluginManagement {
            |    repositories {
            |        $MAVEN_LOCAL_CONVENTIONS
            |        gradlePluginPortal()
            |    }
            |}
            |
            |plugins {
            |    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
            |}
            |
            |dependencyResolutionManagement {
            |    repositories {
            |        $MAVEN_LOCAL_CONVENTIONS
            |        mavenCentral()
            |    }
            |}
            |
            |rootProject.name = "$name"
            """.trimMargin(),
        )
        write(dir, "gradle.properties", "org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=768m\n")
    }

    /** Commits [dir] to a new git repository with the remote `origin` at [origin]. Returns the commit's SHA. */
    private fun commit(dir: File, origin: String): String {
        write(dir, ".gitignore", ".gradle/\n.kotlin/\nbuild/\n")
        return Git.init().setDirectory(dir).setInitialBranch("main").call().use { repository ->
            repository.remoteAdd().setName("origin").setUri(org.eclipse.jgit.transport.URIish(origin)).call()
            repository.add().addFilepattern(".").call()
            repository.commit().setMessage("fixture").setSign(false).call().name
        }
    }

    private fun runner(dir: File, vararg args: String): GradleRunner = GradleRunner.create()
        .withProjectDir(dir)
        .withArguments(*args, "--configuration-cache", "--stacktrace")
        .forwardOutput()

    /** Builds twice; the second build must reuse the configuration cache. Returns the first build's result. */
    private fun buildTwice(dir: File, vararg args: String): BuildResult {
        val first = runner(dir, *args).build()
        val second = runner(dir, *args).build()
        assertTrue(second.output.contains(CONFIGURATION_CACHE_REUSED), second.output)
        assertFalse(second.output.contains("FAILED"), second.output)
        return first
    }

    private companion object {
        const val CONFIGURATION_CACHE_REUSED = "Configuration cache entry reused."

        /** Releases older than the forced versions; the first two are the versions Dokka 2.3.0 itself resolves. */
        const val OLD_JACKSON = "2.22.2"
        const val OLD_JSOUP = "1.23.2"
        const val OLD_FREEMARKER = "2.3.34"

        /** A Java release other than the Kotlin conventions' default, to tell the two sources of `jdkVersion` apart. */
        const val JAVA_LIBRARY_RELEASE = 21

        /** The JDK version a build script sets on one source set, over the plugin's. */
        const val OVERRIDDEN_JDK_VERSION = 11

        /** The Jackson modules Dokka's generator runtime resolves, versioned like the BOM. */
        val JACKSON_MODULES = listOf(
            "com.fasterxml.jackson:jackson-bom",
            "com.fasterxml.jackson.core:jackson-core",
            "com.fasterxml.jackson.core:jackson-databind",
            "com.fasterxml.jackson.dataformat:jackson-dataformat-xml",
            "com.fasterxml.jackson.module:jackson-module-kotlin",
        )

        /** A repository declaration resolving every `com.kelvsyc.internal` artifact and plugin from Maven Local. */
        const val MAVEN_LOCAL_CONVENTIONS = """exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeGroupByRegex("com\\.kelvsyc\\.internal(\\..*)?") }
        }"""

        /**
         * Build script lines printing the Dokka source sets and their settings, prefixed with the project name.
         */
        const val PRINT_DOKKA = """afterEvaluate {
    val sourceSets = extensions.getByType<org.jetbrains.dokka.gradle.DokkaExtension>().dokkaSourceSets
    println(project.name + ".dokkaSourceSets=" + sourceSets.names.sorted())
    sourceSets.forEach {
        val prefix = project.name + "." + it.name + "."
        println(prefix + "jdkVersion=" + it.jdkVersion.orNull)
        println(prefix + "jdkLink=" + it.enableJdkDocumentationLink.orNull)
        println(prefix + "stdlibLink=" + it.enableKotlinStdLibDocumentationLink.orNull)
        println(prefix + "sourceLinks=" + it.sourceLinks.map { link -> link.remoteUrl.orNull })
    }
}"""
    }
}
