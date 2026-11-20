package com.kelvsyc.internal.settings

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.URIish
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RemoteParsingTest {

    @TempDir
    lateinit var dir: File

    private val rifflet = GitHubRepository("kelvSYC", "rifflet")

    @Test
    fun remoteParsing() {
        assertEquals(rifflet, parseGitHubRemote("https://github.com/kelvSYC/rifflet.git"))
        assertEquals(rifflet, parseGitHubRemote("https://github.com/kelvSYC/rifflet"))
        assertEquals(rifflet, parseGitHubRemote("git@github.com:kelvSYC/rifflet.git"))
    }

    @Test
    fun remoteParsingAcceptsOtherGitHubForms() {
        assertEquals(rifflet, parseGitHubRemote("ssh://git@github.com/kelvSYC/rifflet.git"))
        assertEquals(rifflet, parseGitHubRemote("https://github.com/kelvSYC/rifflet/"))
    }

    @Test
    fun remoteParsingRejectsNonGitHubRemotes() {
        assertNull(parseGitHubRemote("https://gitlab.com/kelvSYC/rifflet.git"))
        assertNull(parseGitHubRemote("https://github.com/kelvSYC"))
        assertNull(parseGitHubRemote("/some/local/path"))
    }

    @Test
    fun originUrlIsRead() {
        Git.init().setDirectory(dir).call().use {
            it.remoteAdd().setName("origin").setUri(URIish("git@github.com:kelvSYC/rifflet.git")).call()
        }
        assertEquals("git@github.com:kelvSYC/rifflet.git", readOriginUrl(dir))
    }

    @Test
    fun noOriginIsNull() {
        Git.init().setDirectory(dir).call().close()
        assertNull(readOriginUrl(dir))
    }

    @Test
    fun noRepositoryIsNull() {
        assertNull(readOriginUrl(dir))
    }
}
