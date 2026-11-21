package com.kelvsyc.internal.kotlin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Fails if any `commonMain` Kotlin source imports a platform package (`java.`, `javax.`, `android.`, `sun.`).
 *
 * With a single JVM target, such imports compile, and only break once another target is added.
 */
@DisableCachingByDefault(because = "Verification task without outputs")
abstract class CheckCommonMainPlatformImports : DefaultTask() {
    /** The `commonMain` Kotlin sources. */
    @get:InputFiles
    @get:SkipWhenEmpty
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** The directory violations are reported relative to. */
    @get:Internal
    abstract val projectDirectory: DirectoryProperty

    @TaskAction
    fun check() {
        val base = projectDirectory.get().asFile
        val violations = sources.files.sorted().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (PLATFORM_IMPORTS.any { line.trimStart().startsWith(it) }) {
                    "  ${file.relativeTo(base).invariantSeparatorsPath}:${index + 1}: ${line.trim()}"
                } else {
                    null
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Platform-specific imports found in commonMain:\n${violations.joinToString("\n")}")
        }
    }

    private companion object {
        val PLATFORM_IMPORTS = listOf("import java.", "import javax.", "import android.", "import sun.")
    }
}
