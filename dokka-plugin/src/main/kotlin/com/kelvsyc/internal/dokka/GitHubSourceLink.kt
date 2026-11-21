package com.kelvsyc.internal.dokka

import com.kelvsyc.internal.settings.parseGitHubRemote
import com.kelvsyc.internal.settings.readOriginUrl
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.gradle.api.Project
import org.gradle.api.invocation.Gradle
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File
import java.io.IOException
import java.net.URI

/**
 * The URL of this project's directory on GitHub at the checked-out commit:
 * `https://github.com/<owner>/<repo>/blob/<HEAD commit>/<path>`, where the repository is named by the `origin` git
 * remote and the path is the project directory's, relative to the root project of the outermost build of the
 * composite (the root of the git repository, in the builds using these conventions). For that root project itself,
 * the URL ends at the commit.
 *
 * The remote and the commit are read through value sources, so a changed `origin` invalidates the configuration
 * cache, and a new commit is picked up without invalidating it.
 *
 * @return the URL, or `null` if there is no git repository, no `origin`, or `origin` is not a GitHub repository
 */
internal fun Project.gitHubSourceLink(): Provider<URI>? {
    val projectDir = layout.projectDirectory.asFile
    val repository = providers.of(OriginUrlValueSource::class.java) {
        parameters.directory.set(projectDir.absolutePath)
    }.orNull?.let(::parseGitHubRemote) ?: return null

    val outermostRoot = generateSequence(gradle, Gradle::getParent).last().rootProject.layout.projectDirectory.asFile
    // Empty for the outermost root project itself, whose sources sit directly under the commit.
    val path = projectDir.toRelativeString(outermostRoot).replace(File.separatorChar, '/')
    val commit = providers.of(HeadCommitValueSource::class.java) {
        parameters.directory.set(projectDir.absolutePath)
    }
    return commit.map { sha ->
        val tree = "https://github.com/${repository.owner}/${repository.name}/blob/$sha"
        URI(if (path.isEmpty()) tree else "$tree/$path")
    }
}

/** Reads the `origin` remote URL via [readOriginUrl]. */
internal abstract class OriginUrlValueSource : ValueSource<String, GitDirectoryParameters> {
    override fun obtain(): String? = readOriginUrl(File(parameters.directory.get()))
}

/** Reads the commit `HEAD` points at, in the git repository containing the directory. */
internal abstract class HeadCommitValueSource : ValueSource<String, GitDirectoryParameters> {
    override fun obtain(): String? {
        val builder = FileRepositoryBuilder().findGitDir(File(parameters.directory.get())).readEnvironment()
        if (builder.gitDir == null) return null
        return try {
            builder.build().use { it.resolve("HEAD")?.name }
        } catch (_: IOException) {
            null
        }
    }
}

/** Parameters of the git value sources. */
internal interface GitDirectoryParameters : ValueSourceParameters {
    /** Directory from which the git repository is discovered. */
    val directory: Property<String>
}
