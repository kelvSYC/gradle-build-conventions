package com.kelvsyc.internal.semver

import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull

class SemverVersionTest {

    @TempDir
    lateinit var dir: File

    private fun initRepo(tag: String = "v0.1.0", annotated: Boolean = false): Git {
        val git = Git.init().setDirectory(dir).setInitialBranch("main").call()
        File(dir, "a.txt").writeText("a")
        git.add().addFilepattern("a.txt").call()
        commit(git, "first")
        if (annotated) {
            git.tag().setName(tag).setAnnotated(true).setMessage(tag).setSigned(false).call()
        } else {
            git.tag().setName(tag).setAnnotated(false).call()
        }
        return git
    }

    private fun commit(git: Git, message: String) {
        git.commit().setMessage(message).setSign(false).call()
    }

    private fun commitPastTag(git: Git) {
        File(dir, "b.txt").writeText("b")
        git.add().addFilepattern("b.txt").call()
        commit(git, "second")
    }

    private fun version(stage: String? = null, scope: String? = null) =
        computeVersion(dir, "v", stage, scope)

    @Test
    fun onTagIsBareVersion() {
        initRepo().close()
        assertEquals("0.1.0", version())
    }

    @Test
    fun aheadOfTagIsImplicitPatchSnapshot() {
        initRepo().use { commitPastTag(it) }
        assertEquals("0.1.1-SNAPSHOT", version())
    }

    @Test
    fun aheadOfTagHonoursScope() {
        initRepo().use { commitPastTag(it) }
        assertEquals("0.2.0-SNAPSHOT", version(scope = "minor"))
        assertEquals("1.0.0-SNAPSHOT", version(scope = "major"))
    }

    @Test
    fun snapshotStageOnTagBumps() {
        initRepo().close()
        assertEquals("0.1.1-SNAPSHOT", version(stage = "snapshot", scope = "patch"))
    }

    @Test
    fun dirtyAppendsBuildMetadata() {
        initRepo().use { git ->
            File(dir, "a.txt").writeText("changed")
            assertEquals("0.1.0+dirty", version())
            commitPastTag(git)
            assertEquals("0.1.1-SNAPSHOT+dirty", version())
        }
    }

    @Test
    fun annotatedTagResolves() {
        initRepo(annotated = true).close()
        assertEquals("0.1.0", version())
    }

    @Test
    fun nonSemverTagsSkipped() {
        initRepo().use { git ->
            commitPastTag(git)
            git.tag().setName("v0.2.0-beta").setAnnotated(false).call()
            File(dir, "c.txt").writeText("c")
            git.add().addFilepattern("c.txt").call()
            commit(git, "third")
            git.tag().setName("vfoo").setAnnotated(false).call()
        }
        assertEquals("0.1.1-SNAPSHOT", version())
    }

    @Test
    fun noTagIsNull() {
        Git.init().setDirectory(dir).call().use { git ->
            File(dir, "a.txt").writeText("a")
            git.add().addFilepattern("a.txt").call()
            commit(git, "first")
        }
        assertNull(version())
    }
}
