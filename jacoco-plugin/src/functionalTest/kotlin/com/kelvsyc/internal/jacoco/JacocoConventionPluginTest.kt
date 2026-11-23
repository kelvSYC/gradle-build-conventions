package com.kelvsyc.internal.jacoco

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files

/**
 * TestKit tests of the JaCoCo convention plugin, resolved from Maven Local at the version under test, applied to a
 * `java` project with one test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JacocoConventionPluginTest {

    private val root: File = Files.createTempDirectory("jacoco-conventions").toFile()
    private val fixture: File = File(root, "fixture").apply { mkdirs() }

    @AfterAll
    fun deleteFixture() {
        root.deleteRecursively()
    }

    private val conventionsVersion: String = systemProperty("conventions.version")
    private val jacoco: String = systemProperty("conventions.jacoco")

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
            |dependencyResolutionManagement {
            |    repositories {
            |        mavenCentral()
            |    }
            |}
            |
            |rootProject.name = "jacoco-fixture"
            """.trimMargin(),
        )
        write(
            "build.gradle.kts",
            """
            |plugins {
            |    java
            |    id("com.kelvsyc.internal.jacoco") version "$conventionsVersion"
            |}
            |
            |dependencies {
            |    testImplementation(platform("org.junit:junit-bom:5.14.1"))
            |    testImplementation("org.junit.jupiter:junit-jupiter")
            |    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            |}
            |
            |tasks.test {
            |    useJUnitPlatform()
            |}
            |
            |tasks.register("printJacocoAgent") {
            |    val agent = configurations.getByName("jacocoAgent").incoming.files
            |    doLast {
            |        println("jacocoAgent=" + agent.files.map { it.name })
            |    }
            |}
            """.trimMargin(),
        )
        write("gradle.properties", "org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=768m\n")
        write("src/main/java/fixture/Greeter.java", "package fixture;\npublic class Greeter { public String greet() { return \"hi\"; } }\n")
        write(
            "src/test/java/fixture/GreeterTest.java",
            """
            |package fixture;
            |import org.junit.jupiter.api.Test;
            |import static org.junit.jupiter.api.Assertions.assertEquals;
            |class GreeterTest {
            |    @Test void greets() { assertEquals("hi", new Greeter().greet()); }
            |}
            """.trimMargin(),
        )
    }

    @Test
    fun toolVersionComesFromCatalog() {
        val result = runner("printJacocoAgent").build()
        assertTrue(result.output.contains("org.jacoco.agent-$jacoco.jar"), result.output)
    }

    @Test
    fun jacocoTestReportProducesXmlAndHtml() {
        val result = runner("jacocoTestReport").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome, result.output)
        assertNotNull(result.task(":jacocoTestReport"), result.output)
        assertTrue(File(fixture, "build/reports/jacoco/test/jacocoTestReport.xml").isFile)
        assertTrue(File(fixture, "build/reports/jacoco/test/html/index.html").isFile)
    }

    @Test
    fun notWiredIntoCheck() {
        val result = runner("check", "--dry-run").build()
        assertTrue(result.output.contains(":test SKIPPED"), result.output)
        assertFalse(result.output.contains("jacocoTestReport"), result.output)
    }

    @Test
    fun configurationCacheReused() {
        runner("jacocoTestReport").build()
        val second = runner("jacocoTestReport").build()
        assertTrue(second.output.contains(CONFIGURATION_CACHE_REUSED), second.output)
        assertFalse(second.output.contains("FAILED"), second.output)
    }

    private fun runner(vararg args: String): GradleRunner = GradleRunner.create()
        .withProjectDir(fixture)
        .withArguments(*args, "--configuration-cache", "--stacktrace")
        .forwardOutput()

    private fun write(path: String, content: String) {
        File(fixture, path).apply { parentFile.mkdirs() }.writeText(content)
    }

    private fun systemProperty(name: String): String =
        System.getProperty(name) ?: error("System property $name is not set")

    private companion object {
        const val CONFIGURATION_CACHE_REUSED = "Configuration cache entry reused."

        const val MAVEN_LOCAL_CONVENTIONS = """exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeGroupByRegex("com\\.kelvsyc\\.internal(\\..*)?") }
        }"""
    }
}
