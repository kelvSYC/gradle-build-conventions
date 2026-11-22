package com.kelvsyc.internal.gradle

import com.kelvsyc.internal.kotlin.KotlinConventions
import com.kelvsyc.internal.kotlin.applyDependencyAnalysis
import com.kelvsyc.internal.kotlin.checkKotlinPluginVersion
import com.kelvsyc.internal.kotlin.checkKotlinToolchainAlignment
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.add
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

/**
 * Configures what both Gradle-plugin kinds share, once the project has `java-library` and the Kotlin JVM plugin, and
 * returns the project's tier.
 *
 * Published code (`main`) compiles against the tier's Gradle API and Kotlin, not the Gradle running the build: see
 * [GradleTier]. Everything else is tier-independent: JDK 21 compiles with no release override (bytecode 21), other
 * compilations use Kotlin 2.3 (in the plugin kind too, over `kotlin-dsl`'s own version), published dependencies exclude
 * the Kotlin standard library and `kotlin-reflect`, tests get Kotest on the JUnit Platform and the JVM arguments mockk
 * needs, and `verifyGradleLinkage` joins `check`. The Dependency Analysis plugin and the Kotlin toolchain checks are
 * those of the Kotlin conventions.
 */
internal fun Project.configureGradleKind(): GradleTier {
    applyDependencyAnalysis()
    checkKotlinPluginVersion()
    checkKotlinToolchainAlignment()

    val tier = gradleTier()
    extensions.create("kelvsycGradle", KelvsycGradleExtension::class.java, tier)

    extensions.getByType(JavaPluginExtension::class.java).withSourcesJar()

    extensions.getByType(KotlinJvmProjectExtension::class.java).jvmToolchain(GRADLE_KIND_JDK)
    // Set on the tasks rather than the extension: `kotlin-dsl` sets its own language and API version on them.
    tasks.withType(KotlinCompile::class.java).configureEach {
        compilerOptions {
            if (name == MAIN_COMPILATION) {
                apiVersion.set(tier.kotlinVersion)
                languageVersion.set(tier.kotlinVersion)
                // Gradle 9.0's JSpecify annotations mark some results non-null that later releases mark nullable,
                // such as ValueSource.obtain(), and Kotlin enforces JSpecify strictly by default. Report these as
                // warnings, as Kotlin already does for the JSR-305 annotations of Gradle 8.14.
                if (tier == GradleTier.GRADLE_9_0) {
                    freeCompilerArgs.add("-Xjspecify-annotations=warn")
                }
            } else {
                apiVersion.set(OTHER_COMPILATIONS_KOTLIN)
                languageVersion.set(OTHER_COMPILATIONS_KOTLIN)
            }
        }
    }

    excludeKotlinStdlibFromPublishedDependencies()
    declareGradleTier(tier)
    compileMainForGradleTier(tier)
    registerGradleLinkageCheck(tier)

    dependencies.add("implementation", dependencies.platform(KotlinConventions.PLATFORM))
    dependencies.add("compileOnly", "org.gradle.experimental:gradle-public-api:${tier.publicApiVersion}") {
        gradlePublicApi()
    }
    dependencies.add("compileOnly", "org.jetbrains.kotlin:kotlin-stdlib:${tier.embeddedKotlin}")
    dependencies.add("compileOnly", "org.jetbrains.kotlin:kotlin-reflect:${tier.embeddedKotlin}")
    dependencies.add("compileOnly", "org.jspecify:jspecify:${GradleConventions.JSPECIFY}")
    for (module in KOTEST_MODULES) {
        dependencies.add("testImplementation", module)
    }

    tasks.withType(Test::class.java).configureEach {
        useJUnitPlatform()
        // mockk uses ByteBuddy dynamic agent loading; JVM 21+ warns and later JVMs may deny it by default.
        jvmArgs("-XX:+EnableDynamicAgentLoading")
        // ByteBuddy only officially supports the JVMs it was released for; experimental mode allows newer ones.
        jvmArgs("-Dnet.bytebuddy.experimental=true")
        // Pre-attach the ByteBuddy agent at JVM startup: dynamic self-attachment is unreliable on newer JVMs.
        jvmArgumentProviders.add(ByteBuddyAgent(classpath))
        // Gradle's ProjectBuilder needs java.lang open for class-loader injection on JDK 25+.
        jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    }

    return tier
}

/** The JDK that compiles and tests both kinds; with no release override, the published bytecode is Java 21. */
private const val GRADLE_KIND_JDK = 21

/** The task compiling `main`, the published code. */
private const val MAIN_COMPILATION = "compileKotlin"

/** The Kotlin language and API version of every compilation but `main`'s, which uses the tier's. */
private val OTHER_COMPILATIONS_KOTLIN = KotlinVersion.KOTLIN_2_3

/**
 * The Kotest modules tests get, versioned by the shared platform. Unlike the Kotlin conventions, not
 * `kotest-assertions-shared`: `kotest-assertions-core` brings it, and the Dependency Analysis plugin reports it as
 * unused in projects whose specs use no class of its own.
 */
private val KOTEST_MODULES = listOf(
    "io.kotest:kotest-assertions-core",
    "io.kotest:kotest-framework-engine",
    "io.kotest:kotest-runner-junit5",
)

/**
 * `-javaagent` for the ByteBuddy agent on a test [classpath], if it has one (mockk brings it).
 *
 * The classpath is already an input of the test task, so it is not tracked again here.
 */
private class ByteBuddyAgent(@get:Internal val classpath: FileCollection) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        classpath.find { "byte-buddy-agent" in it.name }?.let { listOf("-javaagent:$it") } ?: emptyList()
}

/**
 * Versions the Gradle-plugin convention plugins add to consumers' builds, fixed when the plugins were built.
 */
internal object GradleConventions {
    private val properties: Properties by lazy {
        val stream = GradleConventions::class.java.getResourceAsStream("gradle-conventions.properties")
            ?: error("gradle-conventions.properties is missing from the shared Gradle-plugin convention plugins")
        stream.use { Properties().apply { load(it) } }
    }

    /** The version of `org.jspecify:jspecify` that `main` compiles against: the shared catalog's `jspecify`. */
    val JSPECIFY: String by lazy {
        properties.getProperty("jspecify") ?: error("gradle-conventions.properties does not define 'jspecify'")
    }
}
