package com.kelvsyc.internal.coverage

import com.kelvsyc.internal.coverage.engine.ChangedLines
import com.kelvsyc.internal.coverage.engine.Exemption
import com.kelvsyc.internal.coverage.engine.InvalidMarker
import com.kelvsyc.internal.coverage.engine.JacocoXmlReport
import com.kelvsyc.internal.coverage.engine.RuleSettings
import com.kelvsyc.internal.coverage.engine.SourceRoots
import com.kelvsyc.internal.coverage.engine.evaluate
import com.kelvsyc.internal.coverage.engine.render
import com.kelvsyc.internal.coverage.engine.scanDeclarations
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
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Checks that changed code is tested: every changed public declaration is executed, branches on changed lines meet
 * [branchFloor] once there are [minimumBranches] of them, and every `// coverage-exempt:` marker on a changed line
 * gives a reason. It fails when the project has no JVM sources, rather than passing on an empty diff.
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

    /** The source directories compiled for the JVM; the check fails when there are none. */
    @get:Internal
    abstract val measuredSourceDirectories: ConfigurableFileCollection

    /** Why [measuredSourceDirectories] is empty, for the failure message. */
    @get:Input
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
        if (measuredSourceDirectories.isEmpty) {
            throw GradleException(
                "No JVM sources were found for coverage in ${projectName.get()}: ${noSourcesReason.get()}.",
            )
        }
        val changes = changes.get()
        val repoRoot = File(changes.repoRoot)
        val changed = ChangedLines.parseUnifiedDiff(changes.diff, changes.untracked)
        val xml = jacocoXml.get().asFile
        val hasData = !executionData.isEmpty && xml.isFile
        val report = if (hasData) JacocoXmlReport.parse(xml) else JacocoXmlReport(emptyMap(), emptyList())
        val declarations = scanDeclarations(classDirectories.files.filter { it.isDirectory })

        val exemptions = mutableListOf<Exemption>()
        val invalid = mutableListOf<InvalidMarker>()
        for (path in changed.byFile.keys) {
            val file = File(repoRoot, path)
            if (!file.isFile) continue
            val (valid, reasonless) = scanExemptions(path, file.readText())
            exemptions.addAll(valid)
            invalid.addAll(reasonless)
        }

        val result = evaluate(
            changed, report, declarations, exemptions, invalid,
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
