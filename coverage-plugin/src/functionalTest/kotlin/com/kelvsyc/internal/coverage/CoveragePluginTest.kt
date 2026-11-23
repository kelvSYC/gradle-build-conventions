package com.kelvsyc.internal.coverage

import com.kelvsyc.internal.coverage.engine.JacocoXmlReport
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files

/**
 * TestKit tests of the coverage convention, resolved from Maven Local at the version under test.
 *
 * Kotlin builds are slow, so each fixture is shared by the tests that use it. The JVM fixture is a project in a
 * subdirectory of its git repository, as consumers' projects are; every test of the changed-code check first resets
 * the repository to its base commit. Every build runs twice with the configuration cache, and the second run must
 * reuse it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoveragePluginTest {

    /** Shared by every test of the class, unlike a `@TempDir`, which JUnit creates per test. */
    private val root: File = Files.createTempDirectory("coverage-conventions").toFile()

    @AfterAll
    fun deleteFixtures() {
        root.deleteRecursively()
    }

    private val conventionsVersion: String = System.getProperty("conventions.version")
        ?: error("System property conventions.version is not set")

    private val jacocoVersion: String = System.getProperty("conventions.jacoco")
        ?: error("System property conventions.jacoco is not set")

    // Shared fixtures ------------------------------------------------------------------------------------------------

    /** A `kotlin-jvm-library` project in `lib/` of its repository: one tested and one untested public function. */
    private val jvm: Fixture by lazy {
        val repo = fixture(
            "jvm",
            project = "lib",
            buildScript = """
                |plugins {
                |    id("com.kelvsyc.internal.kotlin-jvm-library")
                |    id("com.kelvsyc.internal.coverage")
                |}
                |
                |val agentFiles: FileCollection = configurations["jacocoAgent"]
                |tasks.register("printJacocoAgent") {
                |    val files = agentFiles
                |    doLast { println("jacocoAgent=" + files.files.map { it.name }.sorted()) }
                |}
            """.trimMargin(),
            files = mapOf(
                "src/main/kotlin/com/example/Greeter.kt" to """
                    |package com.example
                    |
                    |fun greet(): String = "hello"
                    |
                    |fun untested(): Int = 1
                """.trimMargin(),
                "src/test/kotlin/com/example/GreeterSpec.kt" to """
                    |package com.example
                    |
                    |import io.kotest.core.spec.style.FunSpec
                    |import io.kotest.matchers.shouldBe
                    |
                    |class GreeterSpec : FunSpec({
                    |    test("greets") { greet() shouldBe "hello" }
                    |})
                """.trimMargin(),
            ),
        )
        val base = Git.open(repo).use { it.repository.resolve("HEAD").name }
        Fixture(repo, File(repo, "lib"), base)
    }

    /** A `kotlin-multiplatform-jvm` project whose `jvmTest` calls a `commonMain` function. */
    private val kmp: Fixture by lazy {
        val repo = fixture(
            "kmp",
            buildScript = """
                |plugins {
                |    id("com.kelvsyc.internal.kotlin-multiplatform-jvm")
                |    id("com.kelvsyc.internal.coverage")
                |}
            """.trimMargin(),
            files = mapOf(
                "src/commonMain/kotlin/com/example/Greeting.kt" to
                    "package com.example\n\nfun greeting(): String = \"hello\"\n",
                "src/jvmTest/kotlin/com/example/GreetingSpec.kt" to """
                    |package com.example
                    |
                    |import io.kotest.core.spec.style.FunSpec
                    |import io.kotest.matchers.shouldBe
                    |
                    |class GreetingSpec : FunSpec({
                    |    test("greets") { greeting() shouldBe "hello" }
                    |})
                """.trimMargin(),
            ),
        )
        Fixture(repo, repo, Git.open(repo).use { it.repository.resolve("HEAD").name })
    }

    /** A `kotlin-gradle-plugin` project whose only test runs the plugin in a TestKit build that dumps its coverage. */
    private val plugin: Fixture by lazy {
        val repo = fixture(
            "plugin",
            buildScript = """
                |plugins {
                |    id("com.kelvsyc.internal.kotlin-gradle-plugin")
                |    id("com.kelvsyc.internal.coverage")
                |}
                |
                |dependencies {
                |    testImplementation(gradleTestKit())
                |}
                |
                |gradlePlugin {
                |    plugins.create("greet") {
                |        id = "com.example.greet"
                |        implementationClass = "com.example.GreetPlugin"
                |    }
                |}
            """.trimMargin(),
            files = mapOf(
                "src/main/kotlin/com/example/GreetPlugin.kt" to """
                    |package com.example
                    |
                    |import org.gradle.api.Plugin
                    |import org.gradle.api.Project
                    |
                    |class GreetPlugin : Plugin<Project> {
                    |    override fun apply(project: Project) {
                    |        project.tasks.register("greet") {
                    |            doLast { println("hello from greet") }
                    |        }
                    |    }
                    |}
                """.trimMargin(),
                "src/test/kotlin/com/example/GreetPluginSpec.kt" to """
                    |package com.example
                    |
                    |import io.kotest.core.spec.style.FunSpec
                    |import io.kotest.matchers.string.shouldContain
                    |import java.nio.file.Files
                    |import org.gradle.testkit.runner.GradleRunner
                    |
                    |class GreetPluginSpec : FunSpec({
                    |    test("greets in a TestKit build") {
                    |        val dir = Files.createTempDirectory("greet").toFile()
                    |        // jacoco-testkit-coverage dumps the build's coverage data when the build finishes.
                    |        dir.resolve("settings.gradle.kts").writeText(
                    |            "plugins { id(\"jacoco-testkit-coverage\") }\nrootProject.name = \"greet\"\n",
                    |        )
                    |        dir.resolve("build.gradle.kts").writeText("plugins { id(\"com.example.greet\") }\n")
                    |        val result = GradleRunner.create().withProjectDir(dir).withPluginClasspath()
                    |            .withArguments("greet").build()
                    |        result.output shouldContain "hello from greet"
                    |    }
                    |})
                """.trimMargin(),
            ),
        )
        Fixture(repo, repo, Git.open(repo).use { it.repository.resolve("HEAD").name })
    }

    /**
     * Projects the check cannot measure: `plain` applies no Kotlin plugin, and `nojvm` is Kotlin Multiplatform
     * without a JVM target.
     */
    private val unmeasured: Fixture by lazy {
        val repo = fixture(
            "unmeasured",
            buildScript = """
                |plugins {
                |    id("com.kelvsyc.internal.kotlin-multiplatform-base") apply false
                |    id("com.kelvsyc.internal.coverage") apply false
                |}
            """.trimMargin(),
            settingsBody = "include(\"plain\", \"nojvm\")",
            files = mapOf(
                "plain/build.gradle.kts" to "plugins {\n    base\n    id(\"com.kelvsyc.internal.coverage\")\n}\n",
                "nojvm/build.gradle.kts" to """
                    |plugins {
                    |    id("com.kelvsyc.internal.kotlin-multiplatform-base")
                    |    id("com.kelvsyc.internal.coverage")
                    |}
                """.trimMargin(),
            ),
        )
        Fixture(repo, repo, Git.open(repo).use { it.repository.resolve("HEAD").name })
    }

    // Report ---------------------------------------------------------------------------------------------------------

    @Test
    fun jvmLibraryProducesReport() {
        val fixture = jvm.reset()
        buildTwice(fixture.project, "coverageReport")
        val report = JacocoXmlReport.parse(fixture.xml)
        assertTrue(report.lines.getValue("com/example/Greeter.kt").values.any { it.coveredInstructions > 0 })
        assertTrue(File(fixture.project, "build/reports/jacoco/coverageReport/html/index.html").isFile)
    }

    @Test
    fun kmpCommonMainIsMeasured() {
        val fixture = kmp.reset()
        buildTwice(fixture.project, "coverageReport")
        val report = JacocoXmlReport.parse(fixture.xml)
        assertTrue(report.lines.getValue("com/example/Greeting.kt").values.any { it.coveredInstructions > 0 })

        // commonMain is a measured source root: an untested public function there fails the check.
        fixture.write(
            "src/commonMain/kotlin/com/example/Farewell.kt",
            "package com.example\n\nfun farewell(): String = \"bye\"\n",
        )
        val output = failTwice(fixture.project, "verifyChangedCoverage", fixture.baseArg).output
        assertTrue(output.contains("Uncovered public API"), output)
        assertTrue(output.contains("src/commonMain/kotlin/com/example/Farewell.kt"), output)
    }

    @Test
    fun gradlePluginTestKitIsMeasured() {
        val fixture = plugin.reset()
        buildTwice(fixture.project, "coverageReport")
        val report = JacocoXmlReport.parse(fixture.xml)
        val apply = report.methods.single { it.className == "com/example/GreetPlugin" && it.name == "apply" }
        assertTrue(apply.covered, report.methods.toString())
    }

    @Test
    fun useExistingDataDoesNotRunTests() {
        val fixture = jvm.reset()
        buildTwice(fixture.project, "test")
        assertTrue(File(fixture.project, "build/jacoco/test.exec").isFile)
        val result = buildTwice(fixture.project, "coverageReport", "-Pcoverage.useExistingData=true")
        assertNull(result.task(":test"), result.tasks.toString())
        assertTrue(fixture.xml.isFile)
    }

    @Test
    fun jacocoVersionFromCatalog() {
        val fixture = jvm.reset()
        val output = buildTwice(fixture.project, "printJacocoAgent").output
        assertTrue(output.contains("org.jacoco.agent-$jacocoVersion"), output)
    }

    // Changed-code check ---------------------------------------------------------------------------------------------

    @Test
    fun uncoveredNewPublicFunctionFailsCheck() {
        val fixture = jvm.reset()
        fixture.write("src/main/kotlin/com/example/Extra.kt", "package com.example\n\nfun extra(): Int = 2\n")
        fixture.commit()
        val result = failTwice(fixture.project, "verifyChangedCoverage", fixture.baseArg)
        assertTrue(result.output.contains("coverage: lib: FAILED"), result.output)
        assertTrue(result.output.contains("Uncovered public API"), result.output)
        // Paths are relative to the repository, not the project.
        assertTrue(result.output.contains("lib/src/main/kotlin/com/example/Extra.kt:3 extra"), result.output)
        val written = File(fixture.project, "build/reports/coverage/verifyChangedCoverage.txt").readText()
        assertTrue(written.contains("Uncovered public API"), written)
    }

    @Test
    fun testedNewFunctionPasses() {
        val fixture = jvm.reset()
        fixture.write("src/main/kotlin/com/example/Extra.kt", "package com.example\n\nfun extra(): Int = 2\n")
        fixture.write(
            "src/test/kotlin/com/example/ExtraSpec.kt",
            """
            |package com.example
            |
            |import io.kotest.core.spec.style.FunSpec
            |import io.kotest.matchers.shouldBe
            |
            |class ExtraSpec : FunSpec({
            |    test("extra") { extra() shouldBe 2 }
            |})
            """.trimMargin(),
        )
        fixture.commit()
        val output = buildTwice(fixture.project, "verifyChangedCoverage", fixture.baseArg).output
        assertTrue(output.contains("coverage: lib: PASSED"), output)
    }

    @Test
    fun exemptedFunctionPasses() {
        val fixture = jvm.reset()
        fixture.write(
            "src/main/kotlin/com/example/Extra.kt",
            "package com.example\n\n// coverage-exempt: platform glue\nfun extra(): Int = 2\n",
        )
        fixture.commit()
        val output = buildTwice(fixture.project, "verifyChangedCoverage", fixture.baseArg).output
        assertTrue(output.contains("coverage: lib: PASSED"), output)
        assertTrue(output.contains("platform glue"), output)
    }

    @Test
    fun uncommittedEditsAreChecked() {
        val fixture = jvm.reset()
        fixture.write(
            "src/main/kotlin/com/example/Greeter.kt",
            "package com.example\n\nfun greet(): String = \"hello\"\n\nfun untested(): Int = 3\n",
        )
        val output = failTwice(fixture.project, "verifyChangedCoverage", fixture.baseArg).output
        assertTrue(output.contains("lib/src/main/kotlin/com/example/Greeter.kt:5 untested"), output)
    }

    @Test
    fun unresolvableBaseFails() {
        val fixture = jvm.reset()
        val output = failTwice(fixture.project, "verifyChangedCoverage", "-Pcoverage.base=does-not-exist").output
        assertTrue(output.contains("coverage.base"), output)
    }

    @Test
    fun extensionOverridesBranchFloor() {
        val fixture = jvm.reset()
        fixture.write(
            "src/main/kotlin/com/example/Classify.kt",
            """
            |package com.example
            |
            |fun classify(a: Int, b: Int): String {
            |    val x = if (a > 0) "p" else "n"
            |    val y = if (b > 0) "p" else "n"
            |    return x + y
            |}
            """.trimMargin(),
        )
        fixture.write(
            "src/test/kotlin/com/example/ClassifySpec.kt",
            """
            |package com.example
            |
            |import io.kotest.core.spec.style.FunSpec
            |import io.kotest.matchers.shouldBe
            |
            |class ClassifySpec : FunSpec({
            |    test("positive") { classify(1, 1) shouldBe "pp" }
            |})
            """.trimMargin(),
        )
        fixture.commit()
        val failed = failTwice(fixture.project, "verifyChangedCoverage", fixture.baseArg).output
        assertTrue(failed.contains("Branches on changed lines: 2/4 (50%) (below floor)"), failed)

        File(fixture.project, "build.gradle.kts").appendText("\nkelvsycCoverage {\n    branchFloor = 0.5\n}\n")
        val passed = buildTwice(fixture.project, "verifyChangedCoverage", fixture.baseArg).output
        assertTrue(passed.contains("coverage: lib: PASSED"), passed)
        assertFalse(passed.contains("below floor"), passed)
    }

    @Test
    fun useExistingDataStillCompiles() {
        val fixture = jvm.reset()
        buildTwice(fixture.project, "test")
        fixture.write("src/main/kotlin/com/example/Extra.kt", "package com.example\n\nfun extra(): Int = 2\n")
        val output = failTwice(
            fixture.project, "verifyChangedCoverage", fixture.baseArg, "-Pcoverage.useExistingData=true",
        ).output
        assertTrue(output.contains("lib/src/main/kotlin/com/example/Extra.kt:3 extra"), output)
    }

    @Test
    fun staleReportIsNotReused() {
        val fixture = jvm.reset()
        buildTwice(fixture.project, "coverageReport")
        assertTrue(fixture.xml.isFile)
        File(fixture.project, "build/jacoco").listFiles { file -> file.extension == "exec" }.orEmpty()
            .forEach { it.delete() }
        // greet() is covered in the existing report; without execution data it must not count as covered.
        fixture.write(
            "src/main/kotlin/com/example/Greeter.kt",
            "package com.example\n\nfun greet(): String = \"hi\"\n\nfun untested(): Int = 1",
        )
        val output = failTwice(
            fixture.project, "verifyChangedCoverage", fixture.baseArg, "-Pcoverage.useExistingData=true",
        ).output
        assertTrue(output.contains("No coverage data"), output)
    }

    @Test
    fun projectWithoutKotlinFails() {
        val fixture = unmeasured.reset()
        val output = failTwice(fixture.project, ":plain:verifyChangedCoverage", fixture.baseArg).output
        assertTrue(output.contains("No JVM sources were found for coverage"), output)
    }

    @Test
    fun multiplatformWithoutJvmTargetFails() {
        val fixture = unmeasured.reset()
        val output = failTwice(fixture.project, ":nojvm:verifyChangedCoverage", fixture.baseArg).output
        assertTrue(output.contains("No JVM sources were found for coverage"), output)
        assertTrue(output.contains("no JVM target"), output)
    }

    // Helpers ---------------------------------------------------------------------------------------------------------

    /** A fixture repository [repo] with its project in [project], committed at [base]. */
    private class Fixture(val repo: File, val project: File, val base: String) {
        val xml: File get() = File(project, "build/reports/jacoco/coverageReport/coverageReport.xml")

        /** The Gradle argument selecting [base] as the diff base. */
        val baseArg: String get() = "-Pcoverage.base=$base"

        /** Restores the working tree and `HEAD` to [base], keeping ignored files (the build outputs). */
        fun reset(): Fixture = apply {
            Git.open(repo).use { git ->
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef(base).call()
                git.clean().setCleanDirectories(true).call()
            }
        }

        fun write(path: String, content: String) {
            File(project, path).apply { parentFile.mkdirs() }.writeText(content)
        }

        fun commit() {
            Git.open(repo).use { git ->
                git.add().addFilepattern(".").call()
                git.commit().setMessage("change").setSign(false).call()
            }
        }
    }

    /**
     * Writes a fixture repository whose build is in [project] (the repository root when empty), applying the shared
     * settings plugin and resolving the artifacts under test from Maven Local only, and commits it.
     */
    private fun fixture(
        name: String,
        buildScript: String,
        project: String = "",
        settingsBody: String = "",
        files: Map<String, String> = emptyMap(),
    ): File {
        val repo = File(root, name).apply { mkdirs() }
        val dir = File(repo, project).apply { mkdirs() }
        File(dir, "settings.gradle.kts").writeText(settingsScript(project.ifEmpty { name }) + "\n$settingsBody\n")
        File(dir, "build.gradle.kts").writeText(buildScript)
        File(dir, "gradle.properties").writeText("org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=768m\n")
        files.forEach { (path, content) -> File(dir, path).apply { parentFile.mkdirs() }.writeText(content) }
        File(repo, ".gitignore").writeText(".gradle/\n.kotlin/\nbuild/\n")
        Git.init().setDirectory(repo).setInitialBranch("main").call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("fixture").setSign(false).call()
        }
        return repo
    }

    private fun settingsScript(name: String): String = """
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

    /** Fails twice, the second time from the configuration cache. Returns the first build's result. */
    private fun failTwice(dir: File, vararg args: String): BuildResult {
        val first = runner(dir, *args).buildAndFail()
        val second = runner(dir, *args).buildAndFail()
        assertTrue(second.output.contains(CONFIGURATION_CACHE_REUSED), second.output)
        assertEquals(first.output.contains("FAILED"), second.output.contains("FAILED"))
        return first
    }

    private companion object {
        const val CONFIGURATION_CACHE_REUSED = "Configuration cache entry reused."

        /** A repository declaration resolving every `com.kelvsyc.internal` artifact and plugin from Maven Local. */
        const val MAVEN_LOCAL_CONVENTIONS = """exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeGroupByRegex("com\\.kelvsyc\\.internal(\\..*)?") }
        }"""
    }
}
