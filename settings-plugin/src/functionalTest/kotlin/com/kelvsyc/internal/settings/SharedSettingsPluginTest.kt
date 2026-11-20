package com.kelvsyc.internal.settings

import org.eclipse.jgit.api.Git
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SharedSettingsPluginTest {

    @TempDir
    lateinit var dir: File

    private val conventionsVersion: String = System.getProperty("conventions.version")
        ?: error("System property conventions.version is not set")

    /**
     * A settings script applying the settings plugin at the version under test, resolving the
     * artifacts under test from Maven Local only.
     */
    private fun settingsScript(
        rootProjectName: String,
        settingsBody: String = "",
        settingsPreamble: String = "",
        pluginManagementBody: String = "",
    ) = """
        |$settingsPreamble
        |pluginManagement {
        |    $pluginManagementBody
        |    repositories {
        |        exclusiveContent {
        |            forRepository { mavenLocal() }
        |            filter {
        |                includeGroup("com.kelvsyc.internal")
        |                includeGroup("com.kelvsyc.internal.settings")
        |                includeGroup("com.kelvsyc.internal.semver")
        |                includeGroup("com.kelvsyc.internal.github-publishing")
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
        |rootProject.name = "$rootProjectName"
        |$settingsBody
    """.trimMargin()

    /**
     * Writes a consumer build applying the settings plugin at the version under test, then
     * commits it to a fresh git repository (optionally tagged and with an `origin` remote).
     */
    private fun consumer(
        settingsBody: String = "",
        settingsPreamble: String = "",
        pluginManagementBody: String = "",
        buildScript: String = "",
        files: Map<String, String> = emptyMap(),
        tag: String? = null,
        origin: String? = null,
    ) {
        File(dir, "settings.gradle.kts").writeText(
            settingsScript("consumer", settingsBody, settingsPreamble, pluginManagementBody),
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
    fun internalProjectPluginsDefaultToTheSettingsPluginVersion() {
        consumer(
            buildScript = """
                plugins {
                    id("com.kelvsyc.internal.github-publishing")
                }
                println("repositories=" + publishing.repositories.names)
            """.trimIndent(),
        )
        val result = build("help")
        assertTrue(result.output.contains("repositories=[GitHubPackages]"), result.output)
    }

    /**
     * Plugins in a longer namespace, such as a build's own convention plugins, keep resolving
     * from where the build declares them. The included build applies the settings plugin itself
     * and one of its precompiled script plugins applies another versionless: given a version,
     * that request would fail, as the plugin is already on the classpath.
     */
    @Test
    fun localNamespacedPluginsAreNotForced() {
        consumer(
            pluginManagementBody = """includeBuild("local-plugins")""",
            buildScript = """
                plugins {
                    id("com.kelvsyc.internal.example.local")
                }
            """.trimIndent(),
            files = mapOf(
                "local-plugins/settings.gradle.kts" to settingsScript("local-plugins"),
                "local-plugins/build.gradle.kts" to "plugins {\n    `kotlin-dsl`\n}\n",
                "local-plugins/src/main/kotlin/com.kelvsyc.internal.example.base.gradle.kts" to
                    "println(\"base-plugin-applied\")\n",
                "local-plugins/src/main/kotlin/com.kelvsyc.internal.example.local.gradle.kts" to """
                    plugins {
                        id("com.kelvsyc.internal.example.base")
                    }

                    println("local-plugin-applied")
                """.trimIndent(),
            ),
        )
        val result = build("help")
        assertTrue(result.output.contains("base-plugin-applied"), result.output)
        assertTrue(result.output.contains("local-plugin-applied"), result.output)
    }

    /**
     * A version a build declares for a plugin in a longer namespace, here in
     * `pluginManagement.plugins`, is kept rather than replaced by the settings plugin's version.
     */
    @Test
    fun localNamespacedPluginVersionsAreKept() {
        val repository = File(dir, "repository")
        publishPlugin(repository, "com.kelvsyc.internal.example.published", "1.0")
        consumer(
            pluginManagementBody = """
                plugins {
                    id("com.kelvsyc.internal.example.published") version "1.0"
                }
                repositories {
                    maven("${repository.toURI()}")
                }
            """.trimIndent(),
            buildScript = """
                plugins {
                    id("com.kelvsyc.internal.example.published")
                }
                println("base=" + plugins.hasPlugin("base"))
            """.trimIndent(),
        )
        val result = build("help")
        assertTrue(result.output.contains("base=true\n"), result.output)
    }

    @Test
    fun platformIsAvailableFromLibs() {
        consumer(buildScript = """println("platform=" + libs.conventions.platform.get())""")
        val result = build("help")
        assertTrue(
            result.output.contains("platform=com.kelvsyc.internal:platform:$conventionsVersion\n"),
            result.output,
        )
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

    /**
     * Writes plugin [id] at [version] to the Maven repository [repository]: its marker and a jar
     * whose plugin descriptor points at Gradle's own `base` plugin, so no compilation is needed.
     */
    private fun publishPlugin(repository: File, id: String, version: String) {
        val module = "plugin-${id.substringAfterLast('.')}"
        fun pom(group: String, artifact: String, dependency: String = "") = """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<project xmlns="http://maven.apache.org/POM/4.0.0">
            |  <modelVersion>4.0.0</modelVersion>
            |  <groupId>$group</groupId>
            |  <artifactId>$artifact</artifactId>
            |  <version>$version</version>
            |  $dependency
            |</project>
        """.trimMargin()

        val markerArtifact = "$id.gradle.plugin"
        File(repository, "${id.replace('.', '/')}/$markerArtifact/$version/$markerArtifact-$version.pom")
            .apply { parentFile.mkdirs() }
            .writeText(
                pom(
                    id,
                    markerArtifact,
                    "<packaging>pom</packaging><dependencies><dependency><groupId>example</groupId>" +
                        "<artifactId>$module</artifactId><version>$version</version></dependency></dependencies>",
                ),
            )

        val moduleDir = File(repository, "example/$module/$version").apply { mkdirs() }
        File(moduleDir, "$module-$version.pom").writeText(pom("example", module))
        ZipOutputStream(File(moduleDir, "$module-$version.jar").outputStream()).use { jar ->
            jar.putNextEntry(ZipEntry("META-INF/gradle-plugins/$id.properties"))
            jar.write("implementation-class=org.gradle.api.plugins.BasePlugin\n".toByteArray())
            jar.closeEntry()
        }
    }
}
