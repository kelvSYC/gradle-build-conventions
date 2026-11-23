package com.kelvsyc.internal.coverage

import com.kelvsyc.internal.coverage.engine.RuleSettings
import java.util.Properties
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.testing.jacoco.plugins.JacocoPlugin
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget

/**
 * Coverage convention: JaCoCo on every `Test` task, a `coverageReport` and the changed-code check
 * `verifyChangedCoverage` ([VerifyChangedCoverage]), configured by the `kelvsycCoverage` extension
 * ([KelvsycCoverageExtension]). Neither task is part of `check`.
 *
 * JaCoCo is the version this plugin was built with. With `java-gradle-plugin`, TestKit builds that use
 * `withPluginClasspath()` and apply the `jacoco-testkit-coverage` plugin record coverage too
 * (`io.github.gmazzo.gradle.testkit.jacoco`).
 *
 * `coverageReport` reads every `.exec` file in `build/jacoco` against the JVM main classes and their sources: `main`
 * for Kotlin JVM projects, the JVM targets' main compilations (`commonMain`, `jvmMain` and their other source sets)
 * for Kotlin Multiplatform. The classes are always compiled. It depends on every `Test` task, unless the Gradle
 * property `coverage.useExistingData` is `true`, in which case it reads the execution data already on disk. The check
 * diffs the working tree against the merge base of `HEAD` and the Gradle property `coverage.base` (default
 * `origin/main`), restricted to those sources and, for Kotlin Multiplatform, the other targets' main sources, which
 * are reported as not measurable. It fails in a project without JVM sources.
 */
class CoveragePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(JacocoPlugin::class.java)
        project.extensions.getByType(JacocoPluginExtension::class.java).toolVersion = CoverageConventions.JACOCO

        val defaults = RuleSettings()
        val extension = project.extensions.create("kelvsycCoverage", KelvsycCoverageExtension::class.java)
        extension.branchFloor.convention(defaults.branchFloor)
        extension.minimumBranches.convention(defaults.minimumBranches)

        project.pluginManager.withPlugin("java-gradle-plugin") {
            project.pluginManager.apply(TESTKIT_JACOCO_PLUGIN)
        }

        val classDirs = project.files()
        val measuredSources = project.files()
        val notMeasuredSources = project.files()
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            val kotlin = project.extensions.getByType(KotlinJvmProjectExtension::class.java)
            kotlin.target.compilations.configureEach {
                if (name == KotlinCompilation.MAIN_COMPILATION_NAME) classDirs.from(output.classesDirs)
            }
            measuredSources.from(project.provider { kotlin.sourceSets.getByName("main").kotlin.srcDirs })
            project.pluginManager.withPlugin("java") {
                val java = project.extensions.getByType(JavaPluginExtension::class.java)
                measuredSources.from(project.provider { java.sourceSets.getByName("main").java.srcDirs })
            }
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            configureMultiplatform(project, classDirs, measuredSources, notMeasuredSources)
        }

        val useExistingData = project.providers.gradleProperty(USE_EXISTING_DATA_PROPERTY)
            .map { it.toBoolean() }.getOrElse(false)
        val testTasks = project.tasks.withType(Test::class.java)
        // Set rather than read from the report: a report's output location does not survive the configuration cache
        // as another task's input.
        val xmlReport = project.layout.buildDirectory.file("reports/jacoco/coverageReport/coverageReport.xml")
        val execFiles = project.fileTree(project.layout.buildDirectory.dir("jacoco")) { include("*.exec") }
        val report = project.tasks.register("coverageReport", JacocoReport::class.java) {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Generates the JaCoCo report of the JVM main classes from every build/jacoco/*.exec."
            executionData.from(execFiles)
            classDirectories.from(classDirs)
            sourceDirectories.from(measuredSources)
            reports.xml.required.set(true)
            reports.xml.outputLocation.set(xmlReport)
            reports.html.required.set(true)
            reports.csv.required.set(false)
            if (useExistingData) mustRunAfter(testTasks) else dependsOn(testTasks)
        }

        val noSourcesReason = project.provider {
            if (project.pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform")) {
                "the Kotlin Multiplatform project has no JVM target; do not apply com.kelvsyc.internal.coverage to it"
            } else {
                "the project applies neither the Kotlin JVM nor the Kotlin Multiplatform plugin"
            }
        }
        val projectName = if (project.path == ":") project.name else project.rootProject.name + project.path
        project.tasks.register("verifyChangedCoverage", VerifyChangedCoverage::class.java) {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Checks that the code changed since the coverage.base merge base is tested."
            dependsOn(report)
            jacocoXml.set(xmlReport)
            executionData.from(execFiles)
            measuredSourceDirectories.from(measuredSources)
            this.noSourcesReason.set(noSourcesReason)
            classDirectories.from(classDirs)
            branchFloor.set(extension.branchFloor)
            minimumBranches.set(extension.minimumBranches)
            this.projectName.set(projectName)
            reportFile.set(project.layout.buildDirectory.file("reports/coverage/verifyChangedCoverage.txt"))
            changes.set(
                project.providers.of(GitChangesValueSource::class.java) {
                    parameters.workingDirectory.set(project.layout.projectDirectory)
                    parameters.baseRef.set(project.providers.gradleProperty(BASE_PROPERTY))
                    parameters.measuredRoots.set(absolutePaths(measuredSources))
                    parameters.notMeasuredRoots.set(absolutePaths(notMeasuredSources))
                },
            )
        }
    }

    private fun configureMultiplatform(
        project: Project,
        classDirs: ConfigurableFileCollection,
        measuredSources: ConfigurableFileCollection,
        notMeasuredSources: ConfigurableFileCollection,
    ) {
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        val jvmTargets = { kotlin.targets.filter { it.platformType == KotlinPlatformType.jvm } }
        val measuredSets = { jvmTargets().flatMap { it.mainSourceSets() }.toSet() }
        kotlin.targets.configureEach {
            if (platformType == KotlinPlatformType.jvm) {
                compilations.configureEach {
                    if (name == KotlinCompilation.MAIN_COMPILATION_NAME) classDirs.from(output.classesDirs)
                }
            }
        }
        measuredSources.from(project.provider { measuredSets().flatMap { it.kotlin.srcDirs } })
        notMeasuredSources.from(
            project.provider {
                val measured = measuredSets()
                kotlin.targets
                    .filter { it.platformType !in MEASURED_OR_SHARED }
                    .flatMap { it.mainSourceSets() }
                    .filter { it !in measured }
                    .flatMap { it.kotlin.srcDirs }
            },
        )
    }

    private fun KotlinTarget.mainSourceSets(): Set<KotlinSourceSet> =
        compilations.findByName(KotlinCompilation.MAIN_COMPILATION_NAME)?.allKotlinSourceSets.orEmpty()

    private fun absolutePaths(files: FileCollection): Provider<List<String>> =
        files.elements.map { elements -> elements.map { it.asFile.absolutePath } }

    private companion object {
        /** Platforms whose main sources are measured, or shared with measured ones (`commonMain`). */
        val MEASURED_OR_SHARED = setOf(KotlinPlatformType.jvm, KotlinPlatformType.common)

        const val TESTKIT_JACOCO_PLUGIN = "io.github.gmazzo.gradle.testkit.jacoco"
        const val BASE_PROPERTY = "coverage.base"
        const val USE_EXISTING_DATA_PROPERTY = "coverage.useExistingData"
    }
}

/**
 * Facts about this release of the coverage convention.
 */
internal object CoverageConventions {
    /** The JaCoCo version, the shared catalog's `jacoco`. */
    val JACOCO: String by lazy {
        val stream = CoverageConventions::class.java.getResourceAsStream("coverage.properties")
            ?: error("coverage.properties is missing from the coverage plugin")
        val properties = stream.use { Properties().apply { load(it) } }
        properties.getProperty("jacoco") ?: error("coverage.properties does not define 'jacoco'")
    }
}
