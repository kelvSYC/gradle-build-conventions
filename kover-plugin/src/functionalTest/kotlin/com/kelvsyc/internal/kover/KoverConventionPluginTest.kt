package com.kelvsyc.internal.kover

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files

/**
 * TestKit tests of the Kover convention plugin, resolved from Maven Local at the version under test, applied to three
 * subprojects of one build: a `kotlin-jvm-library` (`jvm`), a Kotlin Multiplatform JVM and JS project (`kmp`) and a
 * Kotlin Multiplatform JS-only project (`js`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KoverConventionPluginTest {

    private val root: File = Files.createTempDirectory("kover-conventions").toFile()
    private val fixture: File = File(root, "fixture").apply { mkdirs() }

    @AfterAll
    fun deleteFixture() {
        root.deleteRecursively()
    }

    private val conventionsVersion: String = systemProperty("conventions.version")
    private val kover: String = systemProperty("conventions.kover")

    init {
        write(
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
            |rootProject.name = "kover-fixture"
            |include("jvm", "kmp", "js")
            """.trimMargin(),
        )
        write(
            "build.gradle.kts",
            """
            |plugins {
            |    id("com.kelvsyc.internal.kotlin-jvm-library") version "$conventionsVersion" apply false
            |    id("com.kelvsyc.internal.kotlin-multiplatform-jvm") version "$conventionsVersion" apply false
            |    id("com.kelvsyc.internal.kotlin-multiplatform-js") version "$conventionsVersion" apply false
            |    id("com.kelvsyc.internal.kover") version "$conventionsVersion" apply false
            |}
            """.trimMargin(),
        )
        write("gradle.properties", "org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=768m\n")
        write("jvm/build.gradle.kts", projectScript("kotlin-jvm-library") + KOVER_JAR_PRINT)
        write("jvm/src/main/kotlin/com/example/Greeting.kt", GREETING_SOURCE)
        write("jvm/src/test/kotlin/com/example/GreetingSpec.kt", GREETING_SPEC)
        write("kmp/build.gradle.kts", projectScript("kotlin-multiplatform-jvm", "kotlin-multiplatform-js"))
        write("kmp/src/commonMain/kotlin/com/example/Greeting.kt", GREETING_SOURCE)
        write("kmp/src/jvmTest/kotlin/com/example/GreetingSpec.kt", GREETING_SPEC)
        write("js/build.gradle.kts", projectScript("kotlin-multiplatform-js"))
        write("js/src/commonMain/kotlin/com/example/Greeting.kt", GREETING_SOURCE)
    }

    /** A build script applying the given Kotlin kinds and the Kover convention, printing whether Kover is applied. */
    private fun projectScript(vararg kinds: String): String {
        val ids = (kinds.map { "com.kelvsyc.internal.$it" } + "com.kelvsyc.internal.kover")
            .joinToString("\n") { "    id(\"$it\")" }
        return "plugins {\n$ids\n}\n\n" +
            "println(project.name + \".kover=\" + plugins.hasPlugin(\"org.jetbrains.kotlinx.kover\"))\n"
    }

    /** The configuration output of the whole fixture; printed only when configuration runs, so built once. */
    private val configured: String by lazy { buildTwice("help").output }

    @Test
    fun appliesToJvmMultiplatformAndJsOnly() {
        val output = configured
        for (project in PROJECTS) {
            assertTrue(output.contains("$project.kover=true\n"), output)
        }
    }

    @Test
    fun koverVersionFromCatalog() {
        val output = configured
        assertTrue(output.contains("jvm.koverJar=kover-gradle-plugin-$kover.jar\n"), output)
    }

    @Test
    fun koverNotInCheck() {
        for (project in PROJECTS) {
            val output = buildTwice(":$project:check", "--dry-run").output
            assertTrue(output.contains(":$project:check SKIPPED"), output)
            // koverFindJar is a prerequisite of the test task, not of check; no verify or report task is.
            val koverTasks = Regex(":$project:(kover\\w+)").findAll(output).map { it.groupValues[1] }.toSet()
            assertTrue(koverTasks.all { it == "koverFindJar" }, output)
        }
    }

    @Test
    fun reportCoversCommonMainFromJvmTests() {
        buildTwice(":kmp:koverXmlReport")
        val report = File(fixture, "kmp/build/reports/kover/report.xml").readText()
        assertTrue(report.contains("com/example/GreetingKt"), report)
    }

    @Test
    fun jvmLibraryReportCoversMain() {
        buildTwice(":jvm:koverXmlReport")
        val report = File(fixture, "jvm/build/reports/kover/report.xml").readText()
        assertTrue(report.contains("com/example/GreetingKt"), report)
    }

    @Test
    fun jsOnlyReportIsEmpty() {
        buildTwice(":js:koverXmlReport")
        val report = File(fixture, "js/build/reports/kover/report.xml").readText()
        assertFalse(report.contains("<class "), report)
    }

    private fun runner(vararg args: String): GradleRunner = GradleRunner.create()
        .withProjectDir(fixture)
        .withArguments(*args, "--configuration-cache", "--stacktrace")
        .forwardOutput()

    /** Builds twice; the second build must reuse the configuration cache. Returns the first build's result. */
    private fun buildTwice(vararg args: String): BuildResult {
        val first = runner(*args).build()
        val second = runner(*args).build()
        assertTrue(second.output.contains(CONFIGURATION_CACHE_REUSED), second.output)
        assertFalse(second.output.contains("FAILED"), second.output)
        return first
    }

    private fun write(path: String, content: String) {
        File(fixture, path).apply { parentFile.mkdirs() }.writeText(content)
    }

    private fun systemProperty(name: String): String =
        System.getProperty(name) ?: error("System property $name is not set")

    private companion object {
        const val CONFIGURATION_CACHE_REUSED = "Configuration cache entry reused."

        val PROJECTS = listOf("jvm", "kmp", "js")

        /** Prints the jar the Kover plugin class is loaded from, which carries the Kover version. */
        const val KOVER_JAR_PRINT = """
println(project.name + ".koverJar=" + Class.forName("kotlinx.kover.gradle.plugin.KoverGradlePlugin")
    .protectionDomain.codeSource.location.path.substringAfterLast('/'))
"""

        const val GREETING_SOURCE = "package com.example\n\nfun greeting(): String = \"hello\"\n"

        const val GREETING_SPEC = """package com.example

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GreetingSpec : FunSpec({
    test("greets") { greeting() shouldBe "hello" }
})
"""

        const val MAVEN_LOCAL_CONVENTIONS = """exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeGroupByRegex("com\\.kelvsyc\\.internal(\\..*)?") }
        }"""
    }
}
