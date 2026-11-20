package com.kelvsyc.internal.semver

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File
import java.io.IOException

/**
 * Gradle [ValueSource] that derives a project version from the nearest ancestor git tag
 * matching a configurable prefix. The computation lives in [computeVersion].
 *
 * Version rules:
 * - The git repository is located via [FileRepositoryBuilder.findGitDir], which walks up the
 *   directory tree and follows `gitdir:` pointer files natively, resolving git worktrees
 *   without any additional configuration.
 * - The base version is the nearest ancestor commit reachable from HEAD carrying a tag that
 *   starts with [Params.tagPrefix] and whose suffix parses as a clean `MAJOR.MINOR.PATCH`
 *   version. Tags with pre-release or build-metadata suffixes (e.g. `v1.2.3-beta`) are
 *   skipped; the walk continues until a clean three-part version is found.
 * - If HEAD is the tagged commit and [Params.stage] is not `"snapshot"`, the version is the
 *   tag's version unchanged.
 * - If [Params.stage] is `"snapshot"`, or HEAD is ahead of the tagged commit, the component
 *   named by [Params.scope] (default `"patch"`) is incremented and `-SNAPSHOT` is appended.
 * - If the working tree is dirty, `+dirty` is appended as SemVer build metadata.
 * - The result is `null` if the repository cannot be opened or no matching tag is found
 *   (e.g. shallow clone, brand-new repository).
 *
 * Known limitation: dirty detection uses [Git.status], which traverses the entire working
 * tree, so it may be slow on very large monorepos.
 */
abstract class SemverValueSource : ValueSource<String, SemverValueSource.Params> {

    /**
     * Parameters for [SemverValueSource].
     */
    interface Params : ValueSourceParameters {
        /**
         * The settings directory of the build, used as the starting point for git repository
         * discovery. [FileRepositoryBuilder.findGitDir] walks upward from this directory.
         */
        val settingsDirectory: DirectoryProperty

        /**
         * The tag prefix used to filter version tags. Only tags whose name starts with this
         * value are considered. Defaults to `"v"` in [SemverSettingsPlugin].
         */
        val tagPrefix: Property<String>

        /**
         * The release stage. Set to `"snapshot"` to force a pre-release snapshot version
         * with the version component specified by [scope] incremented. When blank, a
         * snapshot is still produced if HEAD is ahead of the tagged commit; otherwise the
         * version matches the tag exactly.
         */
        val stage: Property<String>

        /**
         * The version component to increment when [stage] is `"snapshot"`. Accepts
         * `"major"`, `"minor"`, or `"patch"` (default when blank or unrecognised).
         */
        val scope: Property<String>
    }

    override fun obtain(): String? = computeVersion(
        repoDir = parameters.settingsDirectory.asFile.get(),
        tagPrefix = parameters.tagPrefix.get(),
        stage = parameters.stage.orNull?.takeIf { it.isNotBlank() },
        scope = parameters.scope.orNull?.takeIf { it.isNotBlank() },
    )
}

private data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer) = compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    override fun toString() = "$major.$minor.$patch"
}

private data class TaggedVersion(val version: SemVer, val isHead: Boolean)

/**
 * Computes the project version for the git repository containing [repoDir].
 *
 * The base version is taken from the nearest `[tagPrefix]MAJOR.MINOR.PATCH` tag reachable
 * from HEAD. The result is that version unchanged when HEAD is the tagged commit and [stage]
 * is not `"snapshot"`. Otherwise (stage `"snapshot"`, or HEAD ahead of the tagged commit) the
 * component named by [scope] (`"major"`, `"minor"`, or `"patch"` when null or unrecognised)
 * is incremented and `-SNAPSHOT` is appended. A dirty working tree appends `+dirty`.
 *
 * @return the version, or `null` if the repository cannot be opened or has no matching tag
 */
internal fun computeVersion(repoDir: File, tagPrefix: String, stage: String?, scope: String?): String? {
    val builder = FileRepositoryBuilder()
        .findGitDir(repoDir)
        .readEnvironment()
    if (builder.gitDir == null) return null
    return try {
        builder.build().use { findVersion(it, tagPrefix, stage, scope) }
    } catch (_: IOException) {
        null
    }
}

private fun findVersion(repo: Repository, tagPrefix: String, stage: String?, scope: String?): String? {
    val tagged = findLatestSemVer(repo, tagPrefix) ?: return null
    val baseVersion = if (stage == "snapshot" || !tagged.isHead) {
        "${bump(tagged.version, scope)}-SNAPSHOT"
    } else {
        tagged.version.toString()
    }
    val isDirty = try {
        !Git(repo).status().call().isClean
    } catch (_: GitAPIException) {
        false
    }
    return if (isDirty) "$baseVersion+dirty" else baseVersion
}

private fun findLatestSemVer(repo: Repository, tagPrefix: String): TaggedVersion? {
    val tagMap = buildTagMap(repo, tagPrefix)
    val head = repo.resolve("HEAD")
    if (tagMap.isEmpty() || head == null) return null
    val walk = RevWalk(repo)
    return try {
        walk.sort(RevSort.COMMIT_TIME_DESC)
        walk.markStart(walk.parseCommit(head))
        var result: TaggedVersion? = null
        for (commit in walk) {
            val semver = tagMap[commit.id] ?: continue
            result = TaggedVersion(semver, commit.id == head)
            break
        }
        result
    } finally {
        walk.dispose()
    }
}

/**
 * Maps each commit carrying at least one clean `[tagPrefix]MAJOR.MINOR.PATCH` tag to the highest
 * such version. Tags that do not parse are ignored, so a commit tagged both `v3.0.0` and
 * `v3.0.0-rc1` maps to `3.0.0`.
 */
private fun buildTagMap(repo: Repository, tagPrefix: String): Map<ObjectId, SemVer> {
    val map = mutableMapOf<ObjectId, SemVer>()
    for (ref in repo.refDatabase.getRefsByPrefix("refs/tags/$tagPrefix")) {
        val semver = parseSemVer(ref.name.removePrefix("refs/tags/$tagPrefix")) ?: continue
        val peeled = repo.refDatabase.peel(ref)
        val id = peeled.peeledObjectId ?: ref.objectId
        map.merge(id, semver, ::maxOf)
    }
    return map
}

private fun parseSemVer(version: String): SemVer? {
    val parts = version.split(".")
    if (parts.size != 3) return null
    return try {
        SemVer(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
    } catch (_: NumberFormatException) {
        null
    }
}

private fun bump(semver: SemVer, scope: String?): SemVer = when (scope) {
    "major" -> SemVer(semver.major + 1, 0, 0)
    "minor" -> SemVer(semver.major, semver.minor + 1, 0)
    else -> SemVer(semver.major, semver.minor, semver.patch + 1)
}
