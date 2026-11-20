package com.kelvsyc.internal.settings

import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.gradle.api.initialization.Settings
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File
import java.io.IOException

/**
 * A GitHub repository, identified by its [owner] and [name].
 */
data class GitHubRepository(val owner: String, val name: String)

private val gitHubRemote = Regex(
    """^(?:https?://(?:[^@/]+@)?github\.com/|ssh://git@github\.com/|git@github\.com:)([^/]+)/([^/]+?)(?:\.git)?/?$""",
)

/**
 * Parses a GitHub remote URL in HTTPS (`https://github.com/owner/name[.git]`), SCP-like SSH
 * (`git@github.com:owner/name[.git]`) or URL SSH (`ssh://git@github.com/owner/name[.git]`) form.
 *
 * @return the repository, or `null` if [url] is not a GitHub repository URL
 */
fun parseGitHubRemote(url: String): GitHubRepository? =
    gitHubRemote.matchEntire(url.trim())?.let { GitHubRepository(it.groupValues[1], it.groupValues[2]) }

/**
 * Reads the URL of the `origin` remote of the git repository containing [projectDir]. The
 * repository is found by walking up from [projectDir], following `gitdir:` pointer files, so
 * git worktrees are supported.
 *
 * @return the URL, or `null` if [projectDir] is not in a git repository or it has no `origin`
 */
fun readOriginUrl(projectDir: File): String? {
    val builder = FileRepositoryBuilder().findGitDir(projectDir).readEnvironment()
    if (builder.gitDir == null) return null
    return try {
        builder.build().use { it.config.getString("remote", "origin", "url") }
    } catch (_: IOException) {
        null
    }
}

/**
 * The GitHub repository named by the `origin` remote of the git repository containing this
 * build's settings directory.
 *
 * The remote is read through a [ValueSource], so a changed `origin` invalidates the
 * configuration cache.
 *
 * @return the repository, or `null` if there is no git repository, no `origin`, or `origin`
 * is not a GitHub repository
 */
fun Settings.repositoryCoordinates(): GitHubRepository? {
    val settingsDir = layout.settingsDirectory.asFile
    val url = providers.of(OriginUrlValueSource::class.java) {
        parameters.directory.set(settingsDir.absolutePath)
    }.orNull
    return url?.let(::parseGitHubRemote)
}

/**
 * Reads the `origin` remote URL via [readOriginUrl].
 */
internal abstract class OriginUrlValueSource : ValueSource<String, OriginUrlValueSource.Params> {
    interface Params : ValueSourceParameters {
        /** Directory from which the git repository is discovered. */
        val directory: Property<String>
    }

    override fun obtain(): String? = readOriginUrl(File(parameters.directory.get()))
}
