package com.kelvsyc.internal.coverage

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Serializable
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

/**
 * The changes of a git working tree since a diff base, restricted to a project's source roots.
 *
 * Paths are relative to [repoRoot], with `/` separators: [measured] and [notMeasured] are the source roots, [diff] is
 * a unified diff without context lines, and [untracked] maps each untracked file to its line count.
 */
data class GitChanges(
    val repoRoot: String,
    val measured: List<String>,
    val notMeasured: List<String>,
    val diff: String,
    val untracked: Map<String, Int>,
) : Serializable {
    private companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Reads [GitChanges] with the git CLI: the working tree, including uncommitted and untracked files, against the merge
 * base of `HEAD` and the diff base.
 *
 * The repository is the one containing [Parameters.workingDirectory]. The diff base is [Parameters.baseRef], or
 * `origin/main` when absent; if git cannot find its merge base with `HEAD`, this fails, naming the `coverage.base`
 * Gradle property, and explaining the fix for a shallow clone when the repository is one.
 */
abstract class GitChangesValueSource @Inject constructor(
    private val execOperations: ExecOperations,
) : ValueSource<GitChanges, GitChangesValueSource.Parameters> {

    /** Parameters of [GitChangesValueSource]. */
    interface Parameters : ValueSourceParameters {
        /** A directory inside the repository. */
        val workingDirectory: DirectoryProperty

        /** The diff base; `origin/main` when absent. */
        val baseRef: Property<String>

        /** Absolute paths of the source roots compiled for the JVM. */
        val measuredRoots: ListProperty<String>

        /** Absolute paths of the other main source roots. */
        val notMeasuredRoots: ListProperty<String>
    }

    override fun obtain(): GitChanges {
        val start = parameters.workingDirectory.get().asFile
        val repoRoot = File(git(start, "rev-parse", "--show-toplevel").trim()).canonicalFile
        val measured = relativize(repoRoot, parameters.measuredRoots.get())
        val notMeasured = relativize(repoRoot, parameters.notMeasuredRoots.get())

        val ref = parameters.baseRef.orNull ?: DEFAULT_BASE
        val mergeBase = run(repoRoot, "merge-base", "--end-of-options", "HEAD", ref)
        if (mergeBase.exitCode != 0) throw baseFailure(repoRoot, ref, mergeBase.error.trim())

        val roots = measured + notMeasured
        if (roots.isEmpty()) return GitChanges(repoRoot.path, measured, notMeasured, "", emptyMap())
        val diff = git(
            repoRoot,
            "-c", "core.quotepath=false", "-c", "diff.mnemonicPrefix=false",
            "diff", "--unified=0", "-M", "--no-color", "--no-ext-diff", "--src-prefix=a/", "--dst-prefix=b/",
            mergeBase.output.trim(), "--", *roots.toTypedArray(),
        )
        val untracked = git(repoRoot, "ls-files", "-z", "--others", "--exclude-standard", "--", *roots.toTypedArray())
            .split('\u0000').filter { it.isNotEmpty() }
            .associateWith { lineCount(File(repoRoot, it)) }
        return GitChanges(repoRoot.path, measured, notMeasured, diff, untracked)
    }

    private fun baseFailure(repoRoot: File, ref: String, error: String): GradleException {
        val shallow = run(repoRoot, "rev-parse", "--is-shallow-repository").output.trim() == "true"
        val detail = if (error.isEmpty()) "" else " ($error)"
        val remedy = if (shallow) {
            "This is a shallow clone: check out with fetch-depth: 0 (or fetch the base), " +
                "or set -Pcoverage.base=<ref> to a ref that shares history with HEAD."
        } else {
            "Fetch it, or set the Gradle property coverage.base to a ref that shares history with HEAD " +
                "(-Pcoverage.base=<ref>)."
        }
        return GradleException("Cannot find the merge base of HEAD and '$ref' for the coverage diff$detail. $remedy")
    }

    private fun relativize(repoRoot: File, roots: List<String>): List<String> =
        roots.mapNotNull { File(it).canonicalFile.relativeToOrNull(repoRoot)?.invariantSeparatorsPath }
            .filter { it.isNotEmpty() && !it.startsWith("..") }
            .distinct()

    private fun lineCount(file: File): Int {
        val text = file.readText()
        if (text.isEmpty()) return 0
        return text.lines().size - if (text.endsWith('\n')) 1 else 0
    }

    private class Result(val exitCode: Int, val output: String, val error: String)

    private fun run(dir: File, vararg args: String): Result {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val result = execOperations.exec {
            workingDir = dir
            commandLine(listOf("git") + args)
            standardOutput = output
            errorOutput = error
            isIgnoreExitValue = true
        }
        return Result(result.exitValue, output.toString(Charsets.UTF_8), error.toString(Charsets.UTF_8))
    }

    private fun git(dir: File, vararg args: String): String {
        val result = run(dir, *args)
        if (result.exitCode != 0) {
            throw GradleException("git ${args.joinToString(" ")} failed in $dir: ${result.error.trim()}")
        }
        return result.output
    }

    private companion object {
        const val DEFAULT_BASE = "origin/main"
    }
}
