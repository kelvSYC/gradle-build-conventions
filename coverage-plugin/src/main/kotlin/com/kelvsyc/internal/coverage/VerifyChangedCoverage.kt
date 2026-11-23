package com.kelvsyc.internal.coverage

import com.kelvsyc.internal.coverage.engine.ChangedLines
import com.kelvsyc.internal.coverage.engine.JacocoXmlReport
import com.kelvsyc.internal.coverage.engine.RuleSettings
import com.kelvsyc.internal.coverage.engine.SourceRoots
import com.kelvsyc.internal.coverage.engine.evaluate
import com.kelvsyc.internal.coverage.engine.render
import com.kelvsyc.internal.coverage.engine.scanCompiled
import com.kelvsyc.internal.coverage.engine.scanExemptions
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Checks that changed code is tested: every changed line with code is executed by a test, branches on changed lines
 * meet [branchFloor] once there are [minimumBranches] of them, and every `// coverage-exempt:` marker on a changed line
 * gives a reason. It fails in a project without Kotlin, rather than passing on an empty diff.
 *
 * It writes the result to [reportFile], prints it, and fails when the check does not pass. Markers are read from the
 * working tree.
 */
@DisableCachingByDefault(because = "Reads the git working tree")
abstract class VerifyChangedCoverage : DefaultTask() {
    /** The changes to check. */
    @get:Internal
    abstract val changes: Property<GitChanges>

    /**
     * The JaCoCo XML report of [executionData]. It is read only when [executionData] is not empty: JaCoCo skips the
     * report without execution data, which would leave an earlier report in place.
     */
    @get:Internal
    abstract val jacocoXml: RegularFileProperty

    /** The execution data the report is generated from. */
    @get:Internal
    abstract val executionData: ConfigurableFileCollection

    /** The class directories the report covers, compiled before the check runs. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ConfigurableFileCollection

    /** When present, why the project has no sources the check can judge; the check then fails with it. */
    @get:Input
    @get:Optional
    abstract val noSourcesReason: Property<String>

    /** See [KelvsycCoverageExtension.branchFloor]. */
    @get:Input
    abstract val branchFloor: Property<Double>

    /** See [KelvsycCoverageExtension.minimumBranches]. */
    @get:Input
    abstract val minimumBranches: Property<Int>

    /** The project name the result is reported under. */
    @get:Input
    abstract val projectName: Property<String>

    /** The rendered result. */
    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    init {
        doNotTrackState("Reads the git working tree, which no task input describes")
    }

    @TaskAction
    fun verify() {
        noSourcesReason.orNull?.let {
            throw GradleException("No JVM sources were found for coverage in ${projectName.get()}: $it.")
        }
        val changes = changes.get()
        val repoRoot = File(changes.repoRoot)
        val changed = ChangedLines.parseUnifiedDiff(changes.diff, changes.untracked)
        val xml = jacocoXml.get().asFile
        val hasExecutionData = !executionData.isEmpty
        // Without execution data the report is empty, which the result reports as no test having run.
        val report = if (hasExecutionData && xml.isFile) JacocoXmlReport.parse(xml) else JacocoXmlReport(emptyMap())
        val compiled = scanCompiled(classDirectories.files.filter { it.isDirectory })

        val scans = changed.byFile.keys.filter { File(repoRoot, it).isFile }
            .associateWith { scanExemptions(it, File(repoRoot, it).readText()) }

        val result = evaluate(
            changed, report, compiled, scans,
            SourceRoots(changes.measured, changes.notMeasured),
            RuleSettings(branchFloor.get(), minimumBranches.get()),
        )
        val text = result.render(projectName.get())
        val output = reportFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(text + "\n")
        logger.lifecycle(text)
        if (!result.passed) {
            throw GradleException("Changed code is not sufficiently tested; see $output")
        }
    }
}
