package com.kelvsyc.internal.gradle

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

/**
 * TestKit tests of the Gradle-plugin convention plugins, resolved from Maven Local at the version under test.
 *
 * Fixtures apply the shared settings plugin, whose Gradle repository serves the Gradle API they compile against, and
 * run their Gradle daemon on JDK 21, the JDK consumers such as gradle-tools run theirs on. Each fixture build is shared
 * by the tests that assert on it and runs twice with the configuration cache; the second run must reuse it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GradleConventionPluginsTest {

    /** Shared by every test of the class, unlike a `@TempDir`, which JUnit creates per test. */
    private val root: File = Files.createTempDirectory("gradle-conventions").toFile()

    @AfterAll
    fun deleteFixtures() {
        root.deleteRecursively()
    }

    private val conventionsVersion: String = System.getProperty("conventions.version")
        ?: error("System property conventions.version is not set")

    private val fixtureDaemonJdk: String = System.getProperty("conventions.fixtureDaemonJdk")
        ?: error("System property conventions.fixtureDaemonJdk is not set")

    // Shared fixtures ------------------------------------------------------------------------------------------------

    /**
     * Every kind and tier: `lib814` (library, default tier, with a mockk spec), `plugin` (plugin kind) and `explicit`
     * (a library that sets its own `org.gradle.plugin.api-version`) are subprojects; `lib90` (library, tier 9.0, using
     * a Gradle 9.0 API) is an included build, because the tier is a Gradle property of the build, as in gradle-tools,
     * whose every core is its own build. Plugin IDs carry no version, so the shared settings plugin resolves them.
     */
    private val kinds: Fixture by lazy {
        val library = { extra: String ->
            """
            |import org.gradle.api.attributes.plugin.GradlePluginApiVersion
            |import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
            |
            |plugins {
            |    id("com.kelvsyc.internal.kotlin-gradle-library")
            |    `maven-publish`
            |}
            |
            |group = "com.example"
            |version = "1.0"
            |
            |$extra
            |
            |$REPORT
            """.trimMargin()
        }
        val dir = fixture(
            "kinds",
            settingsBody = """
                |include("lib814", "plugin", "explicit")
                |includeBuild("lib90")
            """.trimMargin(),
            buildScript = """
                |plugins {
                |    id("com.kelvsyc.internal.kotlin-gradle-library") apply false
                |    id("com.kelvsyc.internal.kotlin-gradle-plugin") apply false
                |}
                |
                |println("daemonJava=" + System.getProperty("java.specification.version"))
            """.trimMargin(),
            files = mapOf(
                "lib814/build.gradle.kts" to library(
                    """
                    |dependencies {
                    |    implementation("org.apache.commons:commons-lang3")
                    |    testImplementation("io.mockk:mockk:$MOCKK_VERSION")
                    |}
                    |
                    |// Registered after the convention; its classpath is assigned after the convention configured it.
                    |tasks.register<Test>("lateTest")
                    |tasks.named<Test>("lateTest") {
                    |    classpath = sourceSets.test.get().runtimeClasspath
                    |    testClassesDirs = sourceSets.test.get().output.classesDirs
                    |    val label = project.name + ".lateTestJvmArgs="
                    |    doFirst { println(label + (this as Test).allJvmArgs) }
                    |}
                    """.trimMargin(),
                ),
                "lib814/src/main/kotlin/com/example/Greeter.kt" to """
                    |package com.example
                    |
                    |import org.apache.commons.lang3.StringUtils
                    |import org.gradle.api.DefaultTask
                    |import org.gradle.api.tasks.TaskAction
                    |
                    |class Greeter {
                    |    fun greet(): String = StringUtils.capitalize("hello")
                    |}
                    |
                    |abstract class GreetTask : DefaultTask() {
                    |    @TaskAction
                    |    fun greet() = logger.lifecycle(Greeter().greet())
                    |}
                """.trimMargin(),
                "lib814/src/test/kotlin/com/example/GreeterSpec.kt" to """
                    |package com.example
                    |
                    |import io.kotest.core.spec.style.FunSpec
                    |import io.kotest.matchers.shouldBe
                    |import io.kotest.matchers.string.shouldStartWith
                    |import io.mockk.every
                    |import io.mockk.mockk
                    |
                    |class GreeterSpec : FunSpec({
                    |    test("mocks a final class") {
                    |        val greeter = mockk<Greeter>()
                    |        every { greeter.greet() } returns "mocked"
                    |        greeter.greet() shouldBe "mocked"
                    |        Greeter().greet() shouldStartWith "H"
                    |    }
                    |})
                """.trimMargin(),
                "lib90/settings.gradle.kts" to settingsScript("lib90"),
                "lib90/gradle.properties" to "${TIER_PROPERTY}=9.0\n",
                "lib90/build.gradle.kts" to library(""),
                // RootComponentIdentifier is new in Gradle 9.0, so this only compiles against the 9.0 API.
                "lib90/src/main/kotlin/com/example/Roots.kt" to """
                    |package com.example
                    |
                    |import org.gradle.api.artifacts.component.RootComponentIdentifier
                    |
                    |fun isRoot(id: Any): Boolean = id is RootComponentIdentifier
                """.trimMargin(),
                "explicit/build.gradle.kts" to library(
                    """
                    |val published = setOf("apiElements", "runtimeElements", "sourcesElements")
                    |configurations.named { it in published }.configureEach {
                    |    attributes {
                    |        attribute(GradlePluginApiVersion.GRADLE_PLUGIN_API_VERSION_ATTRIBUTE, objects.named("9.9"))
                    |    }
                    |}
                    """.trimMargin(),
                ),
                "plugin/build.gradle.kts" to """
                    |import org.gradle.api.attributes.plugin.GradlePluginApiVersion
                    |import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
                    |
                    |plugins {
                    |    id("com.kelvsyc.internal.kotlin-gradle-plugin")
                    |    `maven-publish`
                    |}
                    |
                    |group = "com.example"
                    |version = "1.0"
                    |
                    |dependencies {
                    |    implementation("org.apache.commons:commons-lang3")
                    |}
                    |
                    |gradlePlugin {
                    |    plugins.create("greet") {
                    |        id = "com.example.greet"
                    |        implementationClass = "com.example.GreetPlugin"
                    |    }
                    |}
                    |
                    |$REPORT
                """.trimMargin(),
                // The Action lambdas rely on the Kotlin DSL's SAM-with-receiver support.
                "plugin/src/main/kotlin/com/example/GreetPlugin.kt" to """
                    |package com.example
                    |
                    |import org.apache.commons.lang3.StringUtils
                    |import org.gradle.api.Plugin
                    |import org.gradle.api.Project
                    |
                    |class GreetPlugin : Plugin<Project> {
                    |    override fun apply(project: Project) {
                    |        project.tasks.register("greet") {
                    |            doLast { println(StringUtils.capitalize("hello")) }
                    |        }
                    |    }
                    |}
                """.trimMargin(),
                "plugin/src/test/kotlin/com/example/GreetPluginSpec.kt" to """
                    |package com.example
                    |
                    |import io.kotest.core.spec.style.FunSpec
                    |import io.kotest.matchers.nulls.shouldNotBeNull
                    |import org.gradle.testfixtures.ProjectBuilder
                    |
                    |class GreetPluginSpec : FunSpec({
                    |    test("registers greet") {
                    |        val project = ProjectBuilder.builder().build()
                    |        project.pluginManager.apply(GreetPlugin::class.java)
                    |        project.tasks.findByName("greet").shouldNotBeNull()
                    |    }
                    |})
                """.trimMargin(),
            ),
        )
        Fixture(
            dir,
            buildTwice(
                dir,
                "check",
                ":lib90:check",
                ":lib814:lateTest",
                ":lib814:generateMetadataFileForMavenPublication",
                ":lib814:generatePomFileForMavenPublication",
                ":lib90:generateMetadataFileForMavenPublication",
                ":explicit:generateMetadataFileForMavenPublication",
                ":plugin:generateMetadataFileForPluginMavenPublication",
                ":plugin:generatePomFileForPluginMavenPublication",
            ),
        )
    }

    /** A default-tier library that also compiles against the Gradle 9.0 API, and uses an API new in 9.0. */
    private val linkage: File by lazy {
        fixture(
            "linkage",
            buildScript = """
                |plugins {
                |    id("com.kelvsyc.internal.kotlin-gradle-library")
                |}
                |
                |dependencies {
                |    compileOnly("org.gradle.experimental:gradle-public-api:9.0.0") {
                |        capabilities { requireCapability("org.gradle.experimental:gradle-public-api-internal") }
                |        exclude(group = "org.jspecify")
                |    }
                |}
            """.trimMargin(),
            files = mapOf(
                "src/main/kotlin/com/example/Roots.kt" to """
                    |package com.example
                    |
                    |import org.gradle.api.artifacts.component.RootComponentIdentifier
                    |
                    |fun isRoot(id: Any): Boolean = id is RootComponentIdentifier
                """.trimMargin(),
            ),
        )
    }

    // Tiers -----------------------------------------------------------------------------------------------------------

    @Test
    fun defaultTierIs814() {
        val output = kinds.result.output
        assertTrue(output.contains("lib814.tier=GRADLE_8_14\n"), output)
        assertTrue(output.contains("plugin.tier=GRADLE_8_14\n"), output)
        assertTrue(output.contains("lib90.tier=GRADLE_9_0\n"), output)
    }

    @Test
    fun unknownTierFails() {
        val output = failTwice(linkage, "help", "-P$TIER_PROPERTY=8.0").output
        assertTrue(output.contains("8.0 is not a supported value for $TIER_PROPERTY; use one of 8.14, 9.0"), output)
    }

    @Test
    fun tier814CompilesAgainstGradle814Api() {
        val output = kinds.result.output
        for (project in listOf("lib814", "plugin")) {
            val classpath = line(output, "$project.compileClasspath=")
            assertTrue(classpath.contains("gradle-public-api-8.14.jar"), classpath)
            assertTrue(classpath.contains("kotlin-stdlib-2.0.21.jar"), classpath)
            assertTrue(classpath.contains("kotlin-reflect-2.0.21.jar"), classpath)
            assertFalse(classpath.contains("gradle-public-api-9"), classpath)
            // Neither the running Gradle's API nor the Kotlin DSL's embedded Kotlin (file dependencies).
            assertFalse(classpath.contains("gradle-api-"), classpath)
            assertFalse(classpath.contains("kotlin-stdlib-${System.getProperty("conventions.kotlin")}"), classpath)
            assertTrue(output.contains("$project.compileClasspathApiVersion=8.14\n"), output)
            assertEquals(TaskOutcome.SUCCESS, kinds.result.task(":$project:compileKotlin")?.outcome)
        }
    }

    @Test
    fun tier90CompilesAgainstGradle90Api() {
        val output = kinds.result.output
        val classpath = line(output, "lib90.compileClasspath=")
        assertTrue(classpath.contains("gradle-public-api-9.0.0.jar"), classpath)
        assertTrue(classpath.contains("kotlin-stdlib-2.2.0.jar"), classpath)
        assertTrue(classpath.contains("kotlin-reflect-2.2.0.jar"), classpath)
        assertTrue(output.contains("lib90.compileClasspathApiVersion=9.0\n"), output)
        // Roots.kt uses a Gradle 9.0 API.
        assertEquals(TaskOutcome.SUCCESS, kinds.result.task(":lib90:compileKotlin")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, kinds.result.task(":lib90:verifyGradleLinkage")?.outcome)
    }

    @Test
    fun publishedApiVersionAttributeMatchesTier() {
        for ((module, tier) in listOf(
            "lib814/build/publications/maven/module.json" to "8.14",
            "lib90/build/publications/maven/module.json" to "9.0",
            "plugin/build/publications/pluginMaven/module.json" to "8.14",
        )) {
            val json = File(kinds.dir, module).readText()
            assertEquals(PUBLISHED_VARIANTS, apiVersions(json).filter { it == tier }.size, "$module:\n$json")
            assertEquals(setOf(tier), apiVersions(json).toSet(), "$module:\n$json")
        }
    }

    @Test
    fun explicitApiVersionAttributeKept() {
        val json = File(kinds.dir, "explicit/build/publications/maven/module.json").readText()
        assertEquals(List(PUBLISHED_VARIANTS) { "9.9" }, apiVersions(json), json)
    }

    // Kotlin --------------------------------------------------------------------------------------------------------

    @Test
    fun mainUsesTierKotlinLanguageOtherCompilations23() {
        val output = kinds.result.output
        for ((project, main) in listOf("lib814" to "2.0", "plugin" to "2.0", "lib90" to "2.2")) {
            assertTrue(output.contains("$project.compileKotlin.languageVersion=$main\n"), output)
            assertTrue(output.contains("$project.compileKotlin.apiVersion=$main\n"), output)
            assertTrue(output.contains("$project.compileTestKotlin.languageVersion=2.3\n"), output)
            assertTrue(output.contains("$project.compileTestKotlin.apiVersion=2.3\n"), output)
        }
    }

    @Test
    fun tier90AddsJspecifyWarn() {
        val output = kinds.result.output
        assertTrue(output.contains("lib90.compileKotlin.jspecifyWarn=true\n"), output)
        assertTrue(output.contains("lib814.compileKotlin.jspecifyWarn=false\n"), output)
        assertTrue(output.contains("plugin.compileKotlin.jspecifyWarn=false\n"), output)
    }

    @Test
    fun stdlibAndReflectExcludedFromPublishedDependencies() {
        for ((project, publication) in listOf("lib814" to "maven", "plugin" to "pluginMaven")) {
            val json = File(kinds.dir, "$project/build/publications/$publication/module.json").readText()
            // Dependencies carry a version, excludes do not: no Kotlin standard library or reflect dependency is
            // published.
            assertFalse(
                Regex("\"module\": \"kotlin-(stdlib|reflect)[^\"]*\",\\s*\"version\"").containsMatchIn(json),
                "$project:\n$json",
            )
            // commons-lang3 in the API and runtime variants.
            for (module in KOTLIN_MODULES) {
                val excludes = Regex(
                    "\\{\\s*\"group\": \"org.jetbrains.kotlin\",\\s*\"module\": \"$module\"\\s*}",
                ).findAll(json).count()
                assertEquals(2, excludes, "$project excludes $module:\n$json")
            }

            val pom = File(kinds.dir, "$project/build/publications/$publication/pom-default.xml").readText()
            val dependencies = Regex("<dependency>(.*?)</dependency>", RegexOption.DOT_MATCHES_ALL).findAll(pom)
                .map { it.groupValues[1] }.toList()
            val commonsLang = dependencies.single { it.contains("<artifactId>commons-lang3</artifactId>") }
            for (module in KOTLIN_MODULES) {
                assertTrue(commonsLang.contains("<artifactId>$module</artifactId>"), "$project:\n$pom")
            }
            assertTrue(
                dependencies.none { Regex("^\\s*<groupId>org.jetbrains.kotlin</groupId>").containsMatchIn(it) },
                "$project:\n$pom",
            )
        }
    }

    // JVM ---------------------------------------------------------------------------------------------------------

    @Test
    fun bytecodeIs21() {
        assertTrue(kinds.result.output.contains("daemonJava=21\n"), kinds.result.output)
        for (classFile in listOf(
            "lib814/build/classes/kotlin/main/com/example/Greeter.class",
            "lib90/build/classes/kotlin/main/com/example/RootsKt.class",
            "plugin/build/classes/kotlin/main/com/example/GreetPlugin.class",
        )) {
            assertEquals(JAVA_21_CLASS_MAJOR_VERSION, classMajorVersion(File(kinds.dir, classFile)), classFile)
        }
        val json = File(kinds.dir, "lib814/build/publications/maven/module.json").readText()
        assertTrue(json.contains("\"org.gradle.jvm.version\": 21"), json)
        assertFalse(Regex("\"org.gradle.jvm.version\": (?!21\\b)").containsMatchIn(json), json)
    }

    // Gradle linkage ------------------------------------------------------------------------------------------------

    @Test
    fun verifyGradleLinkageFailsOn90ApiFromTier814() {
        val output = failTwice(linkage, "verifyGradleLinkage").output
        assertTrue(output.contains("references are missing from the oldest Gradle this project supports"), output)
        assertTrue(output.contains("org/gradle/api/artifacts/component/RootComponentIdentifier"), output)
    }

    @Test
    fun verifyGradleLinkageWiredIntoCheck() {
        for (project in listOf("lib814", "lib90", "plugin", "explicit")) {
            assertEquals(TaskOutcome.SUCCESS, kinds.result.task(":$project:verifyGradleLinkage")?.outcome, project)
            val report = File(kinds.dir, "$project/build/reports/gradle-linkage/report.txt")
            assertEquals("No missing references.\n", report.readText(), project)
        }
        val output = failTwice(linkage, "check").output
        assertTrue(output.contains("Task :verifyGradleLinkage FAILED"), output)
    }

    // Tests -----------------------------------------------------------------------------------------------------------

    @Test
    fun testJvmArgsForMockk() {
        val args = line(kinds.result.output, "lib814.testJvmArgs=")
        assertTrue(args.contains("-XX:+EnableDynamicAgentLoading"), args)
        assertTrue(args.contains("-Dnet.bytebuddy.experimental=true"), args)
        assertTrue(Regex("-javaagent:[^,\\]]*byte-buddy-agent-[^,\\]]*\\.jar").containsMatchIn(args), args)
        assertTrue(args.contains("--add-opens=java.base/java.lang=ALL-UNNAMED"), args)
        // GreeterSpec mocks a final class, which needs the agent.
        val reports = File(kinds.dir, "lib814/build/test-results/test").listFiles { file -> file.extension == "xml" }
            .orEmpty().map { it.readText() }
        assertTrue(reports.any { it.contains("mocks a final class") }, reports.toString())
        assertTrue(reports.none { it.contains("<failure") }, reports.toString())
    }

    @Test
    fun byteBuddyAgentFollowsClasspathAssignedLater() {
        val args = line(kinds.result.output, "lib814.lateTestJvmArgs=")
        assertTrue(Regex("-javaagent:[^,\\]]*byte-buddy-agent-[^,\\]]*\\.jar").containsMatchIn(args), args)
        assertEquals(TaskOutcome.SUCCESS, kinds.result.task(":lib814:lateTest")?.outcome)
    }

    @Test
    fun pluginKindAddsOpens() {
        val args = line(kinds.result.output, "plugin.testJvmArgs=")
        assertTrue(args.contains("--add-opens=java.base/java.lang=ALL-UNNAMED"), args)
        assertTrue(args.contains("-XX:+EnableDynamicAgentLoading"), args)
        assertTrue(args.contains("-Dnet.bytebuddy.experimental=true"), args)
        assertEquals(TaskOutcome.SUCCESS, kinds.result.task(":plugin:test")?.outcome)
    }

    // Dependency Analysis ---------------------------------------------------------------------------------------------

    @Test
    fun dependencyAnalysisExcludesMockk() {
        // GreeterSpec uses mockk's DSL (mockk-dsl) and core (mockk-core) through io.mockk:mockk, and Kotest's shared
        // assertions through kotest-assertions-core; none of it is advice, so projectHealth passes.
        val result = buildTwice(kinds.dir, ":lib814:projectHealth")
        assertEquals(TaskOutcome.SUCCESS, result.task(":lib814:projectHealth")?.outcome, result.output)
    }

    // Versionless plugin IDs ------------------------------------------------------------------------------------------

    @Test
    fun versionlessGradleKindIdsResolve() {
        // The kinds fixture requests both IDs without a version.
        val output = kinds.result.output
        assertTrue(output.contains("lib814.tier="), output)
        assertTrue(output.contains("plugin.tier="), output)
        assertEquals(TaskOutcome.SUCCESS, kinds.result.task(":plugin:validatePlugins")?.outcome)
    }

    // Helpers ---------------------------------------------------------------------------------------------------------

    private class Fixture(val dir: File, val result: BuildResult)

    /**
     * Writes a fixture build in its own directory, as a git repository (the shared settings plugin derives the version
     * from git), with a [settingsScript].
     */
    private fun fixture(
        name: String,
        buildScript: String,
        settingsBody: String = "",
        files: Map<String, String> = emptyMap(),
    ): File {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "settings.gradle.kts").writeText(settingsScript(name, settingsBody))
        File(dir, "build.gradle.kts").writeText(buildScript)
        File(dir, "gradle.properties").writeText(
            "org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=768m\norg.gradle.java.home=$fixtureDaemonJdk\n",
        )
        files.forEach { (path, content) -> File(dir, path).apply { parentFile.mkdirs() }.writeText(content) }
        File(dir, ".gitignore").writeText(".gradle/\n.kotlin/\nbuild/\n")
        Git.init().setDirectory(dir).setInitialBranch("main").call().use { repository ->
            repository.add().addFilepattern(".").call()
            repository.commit().setMessage("fixture").setSign(false).call()
        }
        return dir
    }

    /**
     * A settings script applying the shared settings plugin and resolving the artifacts under test from Maven Local
     * only.
     */
    private fun settingsScript(name: String, body: String = ""): String = """
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
        |        // The Gradle API comes from the shared settings plugin's Gradle repository only, so that resolution
        |        // never reaches the GitHub Packages repositories, which need credentials.
        |        val gradleLibsReleases = getByName("GradleLibsReleases")
        |        exclusiveContent {
        |            forRepositories(gradleLibsReleases)
        |            filter { includeGroup("org.gradle.experimental") }
        |        }
        |    }
        |}
        |
        |rootProject.name = "$name"
        |$body
    """.trimMargin()

    private fun runner(dir: File, vararg args: String): GradleRunner = GradleRunner.create()
        .withProjectDir(dir)
        .withArguments(*args, "--configuration-cache", "--stacktrace")
        .forwardOutput()

    /** Builds twice; the second build must reuse the configuration cache. Returns the first build's result. */
    private fun buildTwice(dir: File, vararg args: String): BuildResult {
        val first = runner(dir, *args).build()
        val second = runner(dir, *args).build()
        assertTrue(second.output.contains(CONFIGURATION_CACHE_REUSED), second.output)
        return first
    }

    /** Fails twice: a failure must not depend on whether the configuration cache was stored. */
    private fun failTwice(dir: File, vararg args: String): BuildResult {
        val first = runner(dir, *args).buildAndFail()
        runner(dir, *args).buildAndFail()
        return first
    }

    /** The rest of the output line starting with [prefix]. */
    private fun line(output: String, prefix: String): String =
        output.lineSequence().firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
            ?: error("No line starting with $prefix in:\n$output")

    /** Every `org.gradle.plugin.api-version` value in a Gradle module metadata file, in order. */
    private fun apiVersions(json: String): List<String> =
        Regex("\"org.gradle.plugin.api-version\": \"([^\"]*)\"").findAll(json).map { it.groupValues[1] }.toList()

    private fun classMajorVersion(classFile: File): Int {
        val bytes = classFile.readBytes()
        return ((bytes[MAJOR_VERSION_OFFSET].toInt() and BYTE_MASK) shl Byte.SIZE_BITS) or
            (bytes[MAJOR_VERSION_OFFSET + 1].toInt() and BYTE_MASK)
    }

    private companion object {
        const val TIER_PROPERTY = "com.kelvsyc.gradle.minimumGradle"

        const val MOCKK_VERSION = "1.14.12"

        const val CONFIGURATION_CACHE_REUSED = "Configuration cache entry reused."

        /** The published variants of a library: API, runtime and sources. */
        const val PUBLISHED_VARIANTS = 3

        /** The Kotlin modules excluded from published dependencies. */
        val KOTLIN_MODULES = listOf("kotlin-stdlib", "kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8", "kotlin-reflect")

        /** Class file major version of Java 21. */
        const val JAVA_21_CLASS_MAJOR_VERSION = 65

        /** Offset of the major version in a class file, after the magic number and the minor version. */
        const val MAJOR_VERSION_OFFSET = 6

        const val BYTE_MASK = 0xFF

        /** A repository declaration resolving every `com.kelvsyc.internal` artifact and plugin from Maven Local. */
        const val MAVEN_LOCAL_CONVENTIONS = """exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeGroupByRegex("com\\.kelvsyc\\.internal(\\..*)?") }
        }"""

        /**
         * Build script lines printing what the tier configured, prefixed with the project name: the tier, the Kotlin
         * language and API versions and the JSpecify flag of main and test compilation, main's compile classpath and
         * its `org.gradle.plugin.api-version`, and the test JVM arguments. Expects `GradlePluginApiVersion` and
         * `KotlinCompile` imports.
         */
        const val REPORT = """val prefix = project.name + "."
println(prefix + "tier=" + kelvsycGradle.tier)
afterEvaluate {
    for (name in listOf("compileKotlin", "compileTestKotlin")) {
        val options = tasks.named<KotlinCompile>(name).get().compilerOptions
        println(prefix + name + ".languageVersion=" + options.languageVersion.get().version)
        println(prefix + name + ".apiVersion=" + options.apiVersion.get().version)
        val jspecifyWarn = options.freeCompilerArgs.get().contains("-Xjspecify-annotations=warn")
        println(prefix + name + ".jspecifyWarn=" + jspecifyWarn)
    }
    val compileClasspath = configurations["compileClasspath"]
    println(prefix + "compileClasspath=" + compileClasspath.files.map { it.name }.sorted())
    println(
        prefix + "compileClasspathApiVersion=" +
            compileClasspath.attributes.getAttribute(GradlePluginApiVersion.GRADLE_PLUGIN_API_VERSION_ATTRIBUTE)
    )
}
tasks.named<Test>("test") {
    // A local, not the script's prefix: the action must not reference the script, for the configuration cache.
    val label = project.name + ".testJvmArgs="
    doFirst { println(label + (this as Test).allJvmArgs) }
}"""
    }
}
