package discovery

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitRemoteDiscoveryTest {

    @Test
    fun testParsesPlainHttpsUrl() {
        val remote = parseCloneUrl("https://bitbucket.example.com/scm/PROJ/repo.git")
        assertEquals(DiscoveredRemote(
                repositoryUrl = "https://bitbucket.example.com/projects/PROJ/repos/repo",
                projectKey = "PROJ", slug = "repo", scheme = "https",
                host = "bitbucket.example.com", hostAuthority = "bitbucket.example.com"), remote)
    }

    @Test
    fun testParsesHttpsUrlWithContextPathAndWithoutGitSuffix() {
        val remote = parseCloneUrl("https://git.example.com/context/scm/PROJ/repo")
        assertEquals(DiscoveredRemote(
                repositoryUrl = "https://git.example.com/context/projects/PROJ/repos/repo",
                projectKey = "PROJ", slug = "repo", scheme = "https",
                host = "git.example.com", hostAuthority = "git.example.com"), remote)
    }

    @Test
    fun testParsesSshUrlWithExplicitPort() {
        val remote = parseCloneUrl("ssh://git@bitbucket.example.com:7999/PROJ/repo.git")
        assertEquals(DiscoveredRemote(
                // No context-path signal from ssh alone -> falls back to a bare https guess.
                repositoryUrl = "https://bitbucket.example.com/projects/PROJ/repos/repo",
                projectKey = "PROJ", slug = "repo", scheme = null,
                host = "bitbucket.example.com", hostAuthority = "bitbucket.example.com:7999"), remote)
    }

    @Test
    fun testParsesScpStyleUrlWithoutPort() {
        val remote = parseCloneUrl("git@bitbucket.example.com:PROJ/repo.git")
        assertEquals(DiscoveredRemote(
                repositoryUrl = "https://bitbucket.example.com/projects/PROJ/repos/repo",
                projectKey = "PROJ", slug = "repo", scheme = null,
                host = "bitbucket.example.com", hostAuthority = "bitbucket.example.com"), remote)
    }

    @Test
    fun testReturnsNullForUrlWithNoRecognizableProjectAndSlug() {
        assertNull(parseCloneUrl("https://bitbucket.example.com/"))
    }

    @Test
    fun testReturnsNullForGarbageInput() {
        assertNull(parseCloneUrl("not a url at all"))
    }
}
