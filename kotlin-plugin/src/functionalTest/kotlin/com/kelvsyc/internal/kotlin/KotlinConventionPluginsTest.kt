package com.kelvsyc.internal.kotlin

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
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * TestKit tests of the Kotlin convention plugins, resolved from Maven Local at the version under test.
 *
 * Kotlin builds are slow, so each fixture build is shared by the tests that assert on it: fixtures are built lazily,
 * once per class. Every fixture build runs twice with the configuration cache, and the second run must reuse it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KotlinConventionPluginsTest {

    /** Shared by every test of the class, unlike a `@TempDir`, which JUnit creates per test. */
    private val root: File = Files.createTempDirectory("kotlin-conventions").toFile()

    @AfterAll
    fun deleteFixtures() {
        root.deleteRecursively()
    }

    private val conventionsVersion: String = System.getProperty("conventions.version")
        ?: error("System property conventions.version is not set")

    /** The compiler Kotlin version, the catalog's `kotlin`. */
    private val kotlinVersion: String = System.getProperty("conventions.kotlin")
        ?: error("System property conventions.kotlin is not set")

    // Shared fixtures ------------------------------------------------------------------------------------------------

    /** A published KMP JVM library with common and JVM sources and Kotest specs, checked and its metadata generated. */
    private val kmpJvm: Fixture by lazy {
        val dir = fixture(
            "kmp-jvm",
            buildScript = """
                |import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
                |
                |plugins {
                |    id("com.kelvsyc.internal.kotlin-multiplatform-library") version "$conventionsVersion"
                |    id("com.kelvsyc.internal.kotlin-multiplatform-jvm") version "$conventionsVersion"
                |    `maven-publish`
                |}
                |
                |group = "com.example"
                |version = "1.0"
                |
                |$PRINT_COMPILER_OPTIONS
                |
                |afterEvaluate {
                |    println("hasDependencyAnalysis=" + plugins.hasPlugin("com.autonomousapps.dependency-analysis"))
                |    println("compileToolchain=" + compile.get().kotlinJavaToolchain.javaVersion.get().majorVersion)
                |}
                |
                |tasks.named<Test>("jvmTest") {
                |    val launcher = javaLauncher
                |    doFirst { println("testLauncher=" + launcher.get().metadata.languageVersion) }
                |}
            """.trimMargin(),
            files = mapOf(
                "src/commonMain/kotlin/com/example/Greeting.kt" to "package com.example\n\nfun greeting(): String = \"hello\"\n",
                "src/jvmMain/kotlin/com/example/Lists.kt" to
                    "package com.example\n\nfun takeFirst(list: MutableList<Int>): Int = list.removeFirst()\n",
                "src/commonTest/kotlin/com/example/GreetingSpec.kt" to """
                    |package com.example
                    |
                    |import io.kotest.core.spec.style.FunSpec
                    |import io.kotest.matchers.shouldBe
                    |
                    |class GreetingSpec : FunSpec({
                    |    test("greets") { greeting() shouldBe "hello" }
                    |})
                """.trimMargin(),
                "src/jvmTest/kotlin/com/example/JdkSpec.kt" to """
                    |package com.example
                    |
                    |import io.kotest.core.spec.style.FunSpec
                    |import io.kotest.matchers.shouldBe
                    |
                    |class JdkSpec : FunSpec({
                    |    test("runs on the release JDK") { System.getProperty("java.specification.version") shouldBe "17" }
                    |    test("takes the first element") { takeFirst(mutableListOf(1, 2)) shouldBe 1 }
                    |})
                """.trimMargin(),
            ),
        )
        Fixture(dir, buildTwice(dir, "check", "generateMetadataFileForJvmPublication", "jvmSourcesJar"))
    }

    /** Two `kotlin-jvm-library` projects, applying `maven-publish` before and after the convention plugin. */
    private val jvmLibrary: Fixture by lazy {
        val library = { pluginsBlock: String ->
            """
            |plugins {
            |$pluginsBlock
            |}
            |
            |group = "com.example"
            |version = "1.0"
            |
            |println(project.name + ".publications=" + publishing.publications.names)
            |println(project.name + ".hasDependencyAnalysis=" + plugins.hasPlugin("com.autonomousapps.dependency-analysis"))
            """.trimMargin()
        }
        val source = "package com.example\n\nfun answer(): Int = ANSWER\n\nprivate const val ANSWER = 42\n"
        val dir = fixture(
            "jvm-library",
            settingsBody = """include("before", "after")""",
            buildScript = """
                |plugins {
                |    id("com.kelvsyc.internal.kotlin-jvm-library") version "$conventionsVersion" apply false
                |}
            """.trimMargin(),
            files = mapOf(
                "before/build.gradle.kts" to library("    `maven-publish`\n    id(\"com.kelvsyc.internal.kotlin-jvm-library\")"),
                "after/build.gradle.kts" to library("    id(\"com.kelvsyc.internal.kotlin-jvm-library\")\n    `maven-publish`"),
                "before/src/main/kotlin/com/example/Answer.kt" to source,
                "after/src/main/kotlin/com/example/Answer.kt" to source,
            ),
        )
        Fixture(
            dir,
            buildTwice(
                dir,
                ":before:generateMetadataFileForMavenPublication",
                ":after:generateMetadataFileForMavenPublication",
            ),
        )
    }

    /**
     * Subprojects overriding the floors: `ext` through the `kelvsycKotlin` extension, `plain` through plain compiler
     * options set by the build script (to an API version above the default `coreLibrariesVersion`).
     */
    private val overrides: Fixture by lazy {
        val sample = "package com.example\n\nfun sample(): Int = 1\n"
        val dir = fixture(
            "overrides",
            settingsBody = """include("ext", "plain")""",
            buildScript = """
                |plugins {
                |    id("com.kelvsyc.internal.kotlin-multiplatform-jvm") version "$conventionsVersion" apply false
                |}
            """.trimMargin(),
            files = mapOf(
                "ext/build.gradle.kts" to """
                    |import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
                    |import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
                    |
                    |plugins {
                    |    id("com.kelvsyc.internal.kotlin-multiplatform-jvm")
                    |    `maven-publish`
                    |}
                    |
                    |kelvsycKotlin {
                    |    languageVersion = KotlinVersion.KOTLIN_2_3
                    |    coreLibrariesVersion = "2.3.0"
                    |}
                    |
                    |$PRINT_COMPILER_OPTIONS
                """.trimMargin(),
                "plain/build.gradle.kts" to """
                    |import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
                    |import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
                    |
                    |plugins {
                    |    id("com.kelvsyc.internal.kotlin-multiplatform-jvm")
                    |}
                    |
                    |kotlin {
                    |    compilerOptions {
                    |        apiVersion.set(KotlinVersion.KOTLIN_2_3)
                    |        languageVersion.set(KotlinVersion.KOTLIN_2_3)
                    |    }
                    |}
                    |
                    |$PRINT_COMPILER_OPTIONS
                """.trimMargin(),
                "ext/src/commonMain/kotlin/com/example/Sample.kt" to sample,
                "plain/src/commonMain/kotlin/com/example/Sample.kt" to sample,
            ),
        )
        Fixture(dir, buildTwice(dir, "help", ":ext:generateMetadataFileForJvmPublication"))
    }

    // KMP JVM ---------------------------------------------------------------------------------------------------------

    @Test
    fun jvmClassesTargetJava17() {
        val classes = File(kmpJvm.dir, "build/classes/kotlin/jvm/main/com/example")
        for (name in listOf("GreetingKt.class", "ListsKt.class")) {
            val bytes = File(classes, name).readBytes()
            val major = ((bytes[MAJOR_VERSION_OFFSET].toInt() and BYTE_MASK) shl Byte.SIZE_BITS) or
                (bytes[MAJOR_VERSION_OFFSET + 1].toInt() and BYTE_MASK)
            assertEquals(JAVA_17_CLASS_MAJOR_VERSION, major, name)
        }
    }

    @Test
    fun removeFirstCompilesAgainstJava17Api() {
        val calls = methodCalls(File(kmpJvm.dir, "build/classes/kotlin/jvm/main/com/example/ListsKt.class"))
        assertTrue("kotlin/collections/CollectionsKt.removeFirst" in calls, calls.toString())
        assertFalse("java/util/List.removeFirst" in calls, calls.toString())
    }

    @Test
    fun publishedJvmVariantDeclaresJvm17AndStdlibFloor() {
        for (module in listOf(
            File(kmpJvm.dir, "build/publications/jvm/module.json"),
            File(jvmLibrary.dir, "before/build/publications/maven/module.json"),
            File(jvmLibrary.dir, "after/build/publications/maven/module.json"),
        )) {
            val json = module.readText()
            assertTrue(json.contains("\"org.gradle.jvm.version\": 17"), "$module:\n$json")
            assertFalse(Regex("\"org.gradle.jvm.version\": (?!17\\b)").containsMatchIn(json), "$module:\n$json")
            assertTrue(
                Regex("\"module\": \"kotlin-stdlib\",\\s*\"version\": \\{\\s*\"requires\": \"2.2.21\"").containsMatchIn(json),
                "$module:\n$json",
            )
        }
    }

    @Test
    fun compilerOptionsUseFloors() {
        val output = kmpJvm.result.output
        assertTrue(output.contains("root.languageVersion=2.2\n"), output)
        assertTrue(output.contains("root.apiVersion=2.2\n"), output)
        assertTrue(output.contains("root.jvmTarget=17\n"), output)
        assertTrue(output.contains("root.jdkRelease=true\n"), output)
        assertTrue(output.contains("root.coreLibrariesVersion=2.2.21\n"), output)
    }

    @Test
    fun extensionOverrideRaisesLanguageVersion() {
        val output = overrides.result.output
        assertTrue(output.contains("ext.languageVersion=2.3\n"), output)
        assertTrue(output.contains("ext.apiVersion=2.3\n"), output)
        assertTrue(output.contains("ext.coreLibrariesVersion=2.3.0\n"), output)
        val module = File(overrides.dir, "ext/build/publications/jvm/module.json").readText()
        assertTrue(
            Regex("\"module\": \"kotlin-stdlib\",\\s*\"version\": \\{\\s*\"requires\": \"2.3.0\"").containsMatchIn(module),
            module,
        )
    }

    @Test
    fun plainCompilerOptionsOverrideWins() {
        val output = overrides.result.output
        assertTrue(output.contains("plain.languageVersion=2.3\n"), output)
        assertTrue(output.contains("plain.apiVersion=2.3\n"), output)
        assertTrue(output.contains("plain.coreLibrariesVersion=2.2.21\n"), output)
    }

    @Test
    fun jvmApiAboveStdlibFails() {
        val output = failTwice(overrides.dir, ":plain:compileKotlinJvm").output
        assertTrue(output.contains("API version 2.3"), output)
        assertTrue(output.contains("coreLibrariesVersion 2.2.21"), output)
    }

    @Test
    fun testsRunOnJdk17() {
        val output = kmpJvm.result.output
        assertTrue(output.contains("testLauncher=17\n"), output)
        assertTrue(output.contains("compileToolchain=25\n"), output)
        // JdkSpec asserts java.specification.version from inside the test JVM.
        assertTrue(testCases(kmpJvm).any { it.contains("runs on the release JDK") }, testCases(kmpJvm).toString())
    }

    @Test
    fun kotestSpecRuns() {
        val cases = testCases(kmpJvm)
        assertEquals(EXPECTED_TEST_CASES, cases.size, cases.toString())
        val reports = File(kmpJvm.dir, "build/test-results/jvmTest").listFiles { file -> file.extension == "xml" }.orEmpty()
        assertTrue(reports.none { it.readText().contains("<failure") }, reports.joinToString { it.readText() })
    }

    @Test
    fun libraryPublishesSources() {
        assertTrue(File(kmpJvm.dir, "build/libs/kmp-jvm-jvm-1.0-sources.jar").isFile)
        val module = File(kmpJvm.dir, "build/publications/jvm/module.json").readText()
        assertTrue(module.contains("\"url\": \"kmp-jvm-jvm-1.0-sources.jar\""), module)
    }

    @Test
    fun commonMainJvmImportFails() {
        assertEquals(TaskOutcome.SUCCESS, kmpJvm.result.task(":checkCommonMainPlatformImports")?.outcome)

        val dir = fixture(
            "common-main-import",
            buildScript = """
                |plugins {
                |    id("com.kelvsyc.internal.kotlin-multiplatform-jvm") version "$conventionsVersion"
                |}
            """.trimMargin(),
            files = mapOf(
                "src/commonMain/kotlin/com/example/Ids.kt" to
                    "package com.example\n\nimport java.util.UUID\n\nfun id(): String = UUID.randomUUID().toString()\n",
            ),
        )
        val output = failTwice(dir, "check", "--continue").output
        assertTrue(output.contains("Platform-specific imports found in commonMain"), output)
        assertTrue(output.contains("src/commonMain/kotlin/com/example/Ids.kt:3: import java.util.UUID"), output)
    }

    // Kotlin toolchain checks -----------------------------------------------------------------------------------------

    @Test
    fun kotlinPluginMismatchFails() {
        val dir = fixture(
            "plugin-mismatch",
            buildScript = """
                |buildscript {
                |    dependencies {
                |        constraints {
                |            classpath("org.jetbrains.kotlin:kotlin-gradle-plugin") {
                |                version { strictly("$OLDER_KOTLIN") }
                |            }
                |        }
                |    }
                |}
                |
                |plugins {
                |    id("com.kelvsyc.internal.kotlin-multiplatform-jvm") version "$conventionsVersion"
                |}
            """.trimMargin(),
        )
        val output = failTwice(dir, "help").output
        assertTrue(output.contains("Kotlin Gradle plugin $OLDER_KOTLIN is applied"), output)
        assertTrue(output.contains("kotlin = \"$kotlinVersion\""), output)
    }

    @Test
    fun misalignedKotlinArtifactFails() {
        val dir = fixture(
            "misaligned",
            // Gradle rejects buildscript repositories next to exclusive plugin repositories.
            pluginRepositories = MAVEN_LOCAL_CONVENTIONS_NON_EXCLUSIVE,
            buildScript = """
                |buildscript {
                |    repositories {
                |        maven(uri("fabricated-repository")) {
                |            content { includeModule("org.jetbrains.kotlin", "kotlin-daemon-client") }
                |        }
                |    }
                |    dependencies {
                |        classpath("org.jetbrains.kotlin:kotlin-daemon-client:99.0")
                |    }
                |}
                |
                |plugins {
                |    id("com.kelvsyc.internal.kotlin-multiplatform-jvm") version "$conventionsVersion"
                |}
            """.trimMargin(),
        )
        fabricateModule(File(dir, "fabricated-repository"), "org.jetbrains.kotlin", "kotlin-daemon-client", "99.0")
        val output = failTwice(dir, "help").output
        assertTrue(output.contains("differ from Kotlin Gradle plugin $kotlinVersion"), output)
        assertTrue(output.contains("kotlin-daemon-client:99.0"), output)
    }

    // kotlin-jvm-library ----------------------------------------------------------------------------------------------

    @Test
    fun jvmLibraryRegistersMavenPublicationInEitherOrder() {
        val output = jvmLibrary.result.output
        assertTrue(output.contains("before.publications=[maven]\n"), output)
        assertTrue(output.contains("after.publications=[maven]\n"), output)
        assertTrue(File(jvmLibrary.dir, "before/build/publications/maven/module.json").isFile)
        assertTrue(File(jvmLibrary.dir, "after/build/publications/maven/module.json").isFile)
    }

    // JS --------------------------------------------------------------------------------------------------------------

    @Test
    fun jsTargetConfiguredWithKspKotestAndNodePin() {
        val dir = fixture(
            "js",
            buildScript = """
                |import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
                |
                |plugins {
                |    id("com.kelvsyc.internal.kotlin-multiplatform-js") version "$conventionsVersion"
                |}
                |
                |afterEvaluate {
                |    println("targets=" + kotlin.targets.names)
                |    println("ksp=" + plugins.hasPlugin("com.google.devtools.ksp"))
                |    println("kotest=" + plugins.hasPlugin("io.kotest"))
                |    println("jsMainDependencies=" + configurations["jsMainImplementation"].dependencies.map {
                |        it.group + ":" + it.name + ":" + it.version
                |    })
                |    println("node=" + extensions.getByType<NodeJsEnvSpec>().version.get())
                |}
            """.trimMargin(),
            files = mapOf("src/commonMain/kotlin/com/example/Sample.kt" to "package com.example\n\nfun sample(): Int = 1\n"),
        )
        val output = buildTwice(dir, "help").output
        assertTrue(output.contains("targets=[js, metadata]\n"), output)
        assertTrue(output.contains("ksp=true\n"), output)
        assertTrue(output.contains("kotest=true\n"), output)
        assertTrue(output.contains("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion"), output)
        assertTrue(output.contains("node=22.12.0\n"), output)
    }

    // Native ----------------------------------------------------------------------------------------------------------

    private val nativeFixture: String by lazy {
        val dir = fixture(
            "native",
            buildScript = """
                |import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
                |
                |plugins {
                |    id("com.kelvsyc.internal.kotlin-multiplatform-native") version "$conventionsVersion"
                |}
                |
                |afterEvaluate {
                |    println("targets=" + kotlin.targets.names.sorted())
                |    println("kotest=" + plugins.hasPlugin("io.kotest"))
                |    println("ksp=" + plugins.hasPlugin("com.google.devtools.ksp"))
                |    println("base=" + plugins.hasPlugin("com.kelvsyc.internal.kotlin-multiplatform-base"))
                |    kotlin.targets.withType<KotlinNativeTarget>().forEach { target ->
                |        target.compilations.forEach { compilation ->
                |            val optIn = compilation.compileTaskProvider.get().compilerOptions.optIn.get()
                |            println("optIn." + target.name + "." + compilation.name + "=" + optIn)
                |        }
                |    }
                |}
            """.trimMargin(),
            files = mapOf("src/commonMain/kotlin/com/example/Sample.kt" to "package com.example\n\nfun sample(): Int = 1\n"),
        )
        buildTwice(dir, "help").output
    }

    @Test
    fun nativeTargetMatchesHost() {
        val hostOs = System.getProperty("os.name")
        val expected = when {
            hostOs.startsWith("Linux") -> "linuxX64"
            hostOs == "Mac OS X" -> "macosArm64"
            hostOs.startsWith("Windows") -> "mingwX64"
            else -> error("Unsupported host OS: $hostOs")
        }
        assertTrue(nativeFixture.contains("targets=[$expected, metadata]\n"), nativeFixture)
    }

    @Test
    fun nativeCompilationsOptInToCinterop() {
        val lines = nativeFixture.lines().filter { it.startsWith("optIn.") }
        assertTrue(lines.size >= 2, nativeFixture)
        lines.forEach { assertTrue(it.contains("kotlinx.cinterop.ExperimentalForeignApi"), it) }
    }

    @Test
    fun appliesBaseAndKotest() {
        assertTrue(nativeFixture.contains("ksp=true\n"), nativeFixture)
        assertTrue(nativeFixture.contains("kotest=true\n"), nativeFixture)
        assertTrue(nativeFixture.contains("base=true\n"), nativeFixture)
    }

    // Dependency Analysis ---------------------------------------------------------------------------------------------

    @Test
    fun dependencyAnalysisAppliedNotInCheck() {
        assertTrue(kmpJvm.result.output.contains("hasDependencyAnalysis=true\n"), kmpJvm.result.output)
        assertTrue(jvmLibrary.result.output.contains("before.hasDependencyAnalysis=true\n"), jvmLibrary.result.output)
        assertTrue(jvmLibrary.result.output.contains("after.hasDependencyAnalysis=true\n"), jvmLibrary.result.output)

        val output = buildTwice(jvmLibrary.dir, "check", "--dry-run").output
        assertTrue(output.contains(":before:check SKIPPED"), output)
        assertFalse(output.contains("projectHealth"), output)
        assertFalse(output.contains("buildHealth"), output)
    }

    // Versionless plugin IDs ------------------------------------------------------------------------------------------

    @Test
    fun versionlessKotlinPluginIdsResolve() {
        val dir = fixture(
            "versionless",
            settings = """
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
                |rootProject.name = "versionless"
            """.trimMargin(),
            buildScript = """
                |plugins {
                |    id("com.kelvsyc.internal.kotlin-multiplatform-base") apply false
                |    id("com.kelvsyc.internal.kotlin-multiplatform-js") apply false
                |    id("com.kelvsyc.internal.kotlin-jvm-library") apply false
                |    id("com.kelvsyc.internal.kotlin-multiplatform-library")
                |    id("com.kelvsyc.internal.kotlin-multiplatform-jvm")
                |}
                |
                |println("multiplatform=" + plugins.hasPlugin("org.jetbrains.kotlin.multiplatform"))
            """.trimMargin(),
            git = true,
        )
        val output = buildTwice(dir, "help").output
        assertTrue(output.contains("multiplatform=true\n"), output)
    }

    // ABI validation --------------------------------------------------------------------------------------------------

    /** A fixture of one project applying [plugins] (lines of a `plugins` block, versionless), with [ABI_SOURCE]. */
    private fun abiFixture(name: String, plugins: List<String>, sourceSet: String, property: String?): File {
        val pluginLines = plugins.joinToString("\n") { "    id(\"$it\") version \"$conventionsVersion\"" }
        return fixture(
            name,
            buildScript = "plugins {\n$pluginLines\n}\n",
            files = mapOf("src/$sourceSet/kotlin/com/example/Api.kt" to ABI_SOURCE),
            gradleProperties = property?.let { "$ABI_PROPERTY=$it\n" }.orEmpty(),
        )
    }

    @Test
    fun abiValidationOffByDefault() {
        val dir = abiFixture("abi-absent", listOf("com.kelvsyc.internal.kotlin-jvm-library"), "main", null)
        val tasks = buildTwice(dir, "tasks", "--all").output
        assertFalse(tasks.contains("checkKotlinAbi"), tasks)
        buildTwice(dir, "check")
    }

    @Test
    fun abiValidationOffWhenFalse() {
        val dir = abiFixture("abi-false", listOf("com.kelvsyc.internal.kotlin-jvm-library"), "main", "false")
        val tasks = buildTwice(dir, "tasks", "--all").output
        assertFalse(tasks.contains("checkKotlinAbi"), tasks)
        buildTwice(dir, "check")
    }

    @Test
    fun abiValidationInvalidValueFails() {
        val dir = abiFixture("abi-invalid", listOf("com.kelvsyc.internal.kotlin-jvm-library"), "main", "yes")
        val output = failTwice(dir, "help").output
        assertTrue(output.contains("yes is not a supported value for $ABI_PROPERTY; use true or false"), output)
    }

    @Test
    fun abiValidationLifecycleOnJvmLibrary() {
        val dir = abiFixture("abi-jvm", listOf("com.kelvsyc.internal.kotlin-jvm-library"), "main", "true")
        val missing = failTwice(dir, "check", NO_WATCH_FS).output
        assertTrue(missing.contains("Expected file with ABI declarations"), missing)
        assertTrue(missing.contains("updateKotlinAbi"), missing)

        buildTwice(dir, "updateKotlinAbi", NO_WATCH_FS)
        val dump = File(dir, "api/abi-jvm.api").readText()
        assertTrue(dump.contains("answer"), dump)
        assertTrue(dump.contains("published"), dump)
        assertFalse(dump.contains("hidden"), dump)
        buildTwice(dir, "check", NO_WATCH_FS)

        // Each edit must be compiled, so that a passing check is not just a stale build.
        val source = File(dir, "src/main/kotlin/com/example/Api.kt")
        source.writeText(ABI_SOURCE.replace("hidden(): Int = 1", "hidden(): Int = 3"))
        val internalChange = buildTwice(dir, "check", NO_WATCH_FS)
        assertEquals(TaskOutcome.SUCCESS, internalChange.task(":compileKotlin")?.outcome, internalChange.output)

        source.writeText(ABI_SOURCE + "\nfun extra(): Int = 0\n")
        val changed = failTwice(dir, "check", NO_WATCH_FS)
        assertEquals(TaskOutcome.SUCCESS, changed.task(":compileKotlin")?.outcome, changed.output)
        assertTrue(changed.output.contains("ABI has changed"), changed.output)
        assertTrue(changed.output.contains("extra"), changed.output)
    }

    @Test
    fun abiValidationDumpsJvmAndKlibOnMultiplatform() {
        val dir = abiFixture(
            "abi-kmp",
            listOf(
                "com.kelvsyc.internal.kotlin-multiplatform-library",
                "com.kelvsyc.internal.kotlin-multiplatform-jvm",
                "com.kelvsyc.internal.kotlin-multiplatform-js",
            ),
            "commonMain",
            "true",
        )
        buildTwice(dir, "updateKotlinAbi")
        assertTrue(File(dir, "api/abi-kmp.api").isFile)
        val klib = File(dir, "api/abi-kmp.klib.api").readText()
        assertTrue(klib.contains("answer"), klib)
        buildTwice(dir, "checkKotlinAbi")
    }

    @Test
    fun abiValidationSkippedWithNativeTarget() {
        // The library plugin comes first, as in kotlin-tools' kotlin-core: the native target is added after it.
        val dir = abiFixture(
            "abi-native",
            listOf(
                "com.kelvsyc.internal.kotlin-multiplatform-library",
                "com.kelvsyc.internal.kotlin-multiplatform-jvm",
                "com.kelvsyc.internal.kotlin-multiplatform-native",
            ),
            "commonMain",
            "true",
        )
        val tasks = buildTwice(dir, "tasks", "--all", "--info").output
        assertFalse(tasks.contains("checkKotlinAbi"), tasks)
        assertTrue(tasks.contains("ABI validation is skipped"), tasks)
        // A dry run: compiling native code would provision the Kotlin/Native toolchain on a fresh machine, and its
        // platform libraries are configuration inputs, so the second build could not reuse the configuration cache.
        val dryRun = buildTwice(dir, "check", "--dry-run").output
        assertFalse(dryRun.contains("checkKotlinAbi"), dryRun)
    }

    @Test
    fun abiValidationNotAppliedByTargetPlugins() {
        val dir = abiFixture("abi-target", listOf("com.kelvsyc.internal.kotlin-multiplatform-jvm"), "commonMain", "true")
        val tasks = buildTwice(dir, "tasks", "--all").output
        assertFalse(tasks.contains("checkKotlinAbi"), tasks)
    }

    // Helpers ---------------------------------------------------------------------------------------------------------

    private class Fixture(val dir: File, val result: BuildResult)

    /**
     * Writes a fixture build in its own directory. Unless [settings] replaces it, the settings script resolves the
     * artifacts under test from Maven Local only and provisions JDKs through the foojay resolver.
     */
    private fun fixture(
        name: String,
        buildScript: String,
        settingsBody: String = "",
        settings: String? = null,
        pluginRepositories: String = MAVEN_LOCAL_CONVENTIONS,
        files: Map<String, String> = emptyMap(),
        git: Boolean = false,
        gradleProperties: String = "",
    ): File {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "settings.gradle.kts").writeText(
            settings ?: """
                |pluginManagement {
                |    repositories {
                |        $pluginRepositories
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
                |$settingsBody
            """.trimMargin(),
        )
        File(dir, "build.gradle.kts").writeText(buildScript)
        File(dir, "gradle.properties").writeText("org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=768m\n$gradleProperties")
        files.forEach { (path, content) -> File(dir, path).apply { parentFile.mkdirs() }.writeText(content) }
        File(dir, ".gitignore").writeText(".gradle/\n.kotlin/\nbuild/\n")
        if (git) {
            Git.init().setDirectory(dir).setInitialBranch("main").call().use { repository ->
                repository.add().addFilepattern(".").call()
                repository.commit().setMessage("fixture").setSign(false).call()
            }
        }
        return dir
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
        return first
    }

    /** Fails twice: a failure must not depend on whether the configuration cache was stored. */
    private fun failTwice(dir: File, vararg args: String): BuildResult {
        val first = runner(dir, *args).buildAndFail()
        runner(dir, *args).buildAndFail()
        return first
    }

    private fun testCases(fixture: Fixture): List<String> =
        File(fixture.dir, "build/test-results/jvmTest").listFiles { file -> file.extension == "xml" }.orEmpty()
            .flatMap { report -> Regex("<testcase name=\"([^\"]*)\"").findAll(report.readText()).map { it.groupValues[1] } }

    /** The `owner.name` of every method call in [classFile]. */
    private fun methodCalls(classFile: File): Set<String> {
        val calls = mutableSetOf<String>()
        ClassReader(classFile.readBytes()).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        name: String,
                        descriptor: String,
                        isInterface: Boolean,
                    ) {
                        calls += "$owner.$name"
                    }
                }
            },
            0,
        )
        return calls
    }

    /** Writes [group]:[module]:[version] to the Maven repository [repository], as a POM and an empty jar. */
    private fun fabricateModule(repository: File, group: String, module: String, version: String) {
        val moduleDir = File(repository, "${group.replace('.', '/')}/$module/$version").apply { mkdirs() }
        File(moduleDir, "$module-$version.pom").writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<project xmlns="http://maven.apache.org/POM/4.0.0">
            |  <modelVersion>4.0.0</modelVersion>
            |  <groupId>$group</groupId>
            |  <artifactId>$module</artifactId>
            |  <version>$version</version>
            |</project>
            """.trimMargin(),
        )
        ZipOutputStream(File(moduleDir, "$module-$version.jar").outputStream()).use { jar ->
            jar.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            jar.write("Manifest-Version: 1.0\n".toByteArray())
            jar.closeEntry()
        }
    }

    private companion object {
        /**
         * A released Kotlin Gradle plugin older than the catalog's `kotlin`. Fixed rather than derived from it: any
         * real release below the compiler version will do, and the catalog only moves forward.
         */
        const val OLDER_KOTLIN = "2.4.10"

        const val CONFIGURATION_CACHE_REUSED = "Configuration cache entry reused."

        const val ABI_PROPERTY = "com.kelvsyc.kotlin.abiValidation"

        /**
         * For builds that follow an edit of the fixture's sources: with file system watching, a loaded machine can start
         * the build before the daemon sees the edit, and the build then runs on the old sources.
         */
        const val NO_WATCH_FS = "--no-watch-fs"

        /** Public, internal and `@PublishedApi internal` declarations, for the ABI validation fixtures. */
        const val ABI_SOURCE = """package com.example

fun answer(): Int = 42

internal fun hidden(): Int = 1

@PublishedApi
internal fun published(): Int = 2
"""

        /** Class file major version of Java 17. */
        const val JAVA_17_CLASS_MAJOR_VERSION = 61

        /** Offset of the major version in a class file, after the magic number and the minor version. */
        const val MAJOR_VERSION_OFFSET = 6

        const val BYTE_MASK = 0xFF

        /** Test cases in the KMP JVM fixture: one in GreetingSpec, two in JdkSpec. */
        const val EXPECTED_TEST_CASES = 3

        /** A repository declaration resolving every `com.kelvsyc.internal` artifact and plugin from Maven Local. */
        const val MAVEN_LOCAL_CONVENTIONS = """exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeGroupByRegex("com\\.kelvsyc\\.internal(\\..*)?") }
        }"""

        /** Like [MAVEN_LOCAL_CONVENTIONS], without making Maven Local exclusive for those groups. */
        const val MAVEN_LOCAL_CONVENTIONS_NON_EXCLUSIVE = """mavenLocal {
            content { includeGroupByRegex("com\\.kelvsyc\\.internal(\\..*)?") }
        }"""

        /**
         * Build script lines printing the floors as the JVM compile task sees them, prefixed with the project name.
         * Expects a `KotlinJvmCompile` import.
         */
        const val PRINT_COMPILER_OPTIONS = """val compile = tasks.named<KotlinJvmCompile>("compileKotlinJvm")
afterEvaluate {
    val options = compile.get().compilerOptions
    val prefix = if (project == rootProject) "root." else project.name + "."
    println(prefix + "languageVersion=" + options.languageVersion.get().version)
    println(prefix + "apiVersion=" + options.apiVersion.get().version)
    println(prefix + "jvmTarget=" + options.jvmTarget.get().target)
    println(prefix + "jdkRelease=" + options.freeCompilerArgs.get().contains("-Xjdk-release=17"))
    println(prefix + "coreLibrariesVersion=" + kotlin.coreLibrariesVersion)
}"""
    }
}
