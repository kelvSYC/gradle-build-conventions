package com.kelvsyc.internal.settings

import org.eclipse.jgit.api.Git
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SharedSettingsPluginTest {

    @TempDir
    lateinit var dir: File

    private val conventionsVersion: String = System.getProperty("conventions.version")
        ?: error("System property conventions.version is not set")

    /**
     * Writes a consumer build applying the settings plugin at the version under test, then
     * commits it to a fresh git repository (optionally tagged and with an `origin` remote).
     */
    private fun consumer(
        settingsBody: String = "",
        settingsPreamble: String = "",
        buildScript: String = "",
        files: Map<String, String> = emptyMap(),
        tag: String? = null,
        origin: String? = null,
    ) {
        File(dir, "settings.gradle.kts").writeText(
            """
            |$settingsPreamble
            |pluginManagement {
            |    repositories {
            |        exclusiveContent {
            |            forRepository { mavenLocal() }
            |            filter {
            |                includeGroup("com.kelvsyc.internal")
            |                includeGroup("com.kelvsyc.internal.settings")
            |                includeGroup("com.kelvsyc.internal.semver")
            |            }
            |        }
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
            |        exclusiveContent {
            |            forRepository { mavenLocal() }
            |            filter { includeGroup("com.kelvsyc.internal") }
            |        }
            |    }
            |}
            |
            |rootProject.name = "consumer"
            |$settingsBody
            """.trimMargin(),
        )
        File(dir, "build.gradle.kts").writeText(buildScript)
        files.forEach { (path, content) -> File(dir, path).apply { parentFile.mkdirs() }.writeText(content) }
        File(dir, ".gitignore").writeText(".gradle/\n.kotlin/\nbuild/\n")

        Git.init().setDirectory(dir).setInitialBranch("main").call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("consumer").setSign(false).call()
            if (tag != null) git.tag().setName(tag).setAnnotated(false).call()
            if (origin != null) {
                git.remoteAdd().setName("origin").setUri(org.eclipse.jgit.transport.URIish(origin)).call()
            }
        }
    }

    private fun build(vararg args: String): BuildResult = GradleRunner.create()
        .withProjectDir(dir)
        .withArguments(*args, "--configuration-cache", "--stacktrace")
        .forwardOutput()
        .build()

    @Test
    fun consumerGetsSharedCatalog() {
        consumer(buildScript = """println("kotlin=" + libs.versions.kotlin.get())""")
        val result = build("help")
        assertTrue(result.output.contains("kotlin=2.4.21"), result.output)
    }

    @Test
    fun consumerIsVersionedBySemver() {
        consumer(buildScript = """println("version=" + version)""", tag = "v1.2.3")
        val result = build("help")
        assertTrue(result.output.contains("version=1.2.3\n"), result.output)
    }

    @Test
    fun groupDefaultsToKelvsyc() {
        consumer(buildScript = """println("group=" + group)""")
        val result = build("help")
        assertTrue(result.output.contains("group=com.kelvsyc\n"), result.output)
    }

    @Test
    fun groupCanBeOverridden() {
        consumer(
            settingsBody = """kelvsyc { group = "com.kelvsyc.rifflet" }""",
            buildScript = """println("group=" + group)""",
        )
        val result = build("help")
        assertTrue(result.output.contains("group=com.kelvsyc.rifflet\n"), result.output)
    }

    @Test
    fun groupSetInBuildScriptIsKept() {
        consumer(buildScript = "group = \"from.build.script\"\nprintln(\"group=\" + group)")
        val result = build("help")
        assertTrue(result.output.contains("group=from.build.script\n"), result.output)
    }

    @Test
    fun groupSetInGradlePropertiesIsKept() {
        consumer(
            settingsBody = "include(\"sub\")",
            files = mapOf(
                "sub/gradle.properties" to "group=from.gradle.properties\n",
                "sub/build.gradle.kts" to "println(\"sub-group=\" + group)",
            ),
        )
        val result = build("help")
        assertTrue(result.output.contains("sub-group=from.gradle.properties\n"), result.output)
    }

    @Test
    fun groupSetByParentSubprojectsBlockIsKept() {
        consumer(
            settingsBody = "include(\"sub\")",
            buildScript = "subprojects { group = \"from.subprojects\" }",
            files = mapOf("sub/build.gradle.kts" to "println(\"sub-group=\" + group)"),
        )
        val result = build("help")
        assertTrue(result.output.contains("sub-group=from.subprojects\n"), result.output)
    }

    @Test
    fun subprojectGroupDefaultsToKelvsyc() {
        consumer(
            settingsBody = "include(\"sub\", \"sub:nested\")",
            files = mapOf(
                "sub/build.gradle.kts" to "println(\"sub-group=\" + group)",
                "sub/nested/build.gradle.kts" to "println(\"nested-group=\" + group)",
            ),
        )
        val result = build("help")
        assertTrue(result.output.contains("sub-group=com.kelvsyc\n"), result.output)
        assertTrue(result.output.contains("nested-group=com.kelvsyc\n"), result.output)
    }

    @Test
    fun repositoryCoordinatesFromOrigin() {
        consumer(
            settingsPreamble = "import com.kelvsyc.internal.settings.repositoryCoordinates",
            settingsBody = """println("coordinates=" + repositoryCoordinates())""",
            origin = "git@github.com:kelvSYC/rifflet.git",
        )
        val result = build("help")
        assertTrue(
            result.output.contains("coordinates=GitHubRepository(owner=kelvSYC, name=rifflet)"),
            result.output,
        )
    }

    @Test
    fun repositoryCoordinatesWithoutOriginIsNull() {
        consumer(
            settingsPreamble = "import com.kelvsyc.internal.settings.repositoryCoordinates",
            settingsBody = """println("coordinates=" + repositoryCoordinates())""",
        )
        val result = build("help")
        assertTrue(result.output.contains("coordinates=null"), result.output)
    }
}
