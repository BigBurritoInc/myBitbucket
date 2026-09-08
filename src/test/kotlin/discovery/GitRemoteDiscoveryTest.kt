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
                host = "bitbucket.example.com", hostAuthority = "bitbucket.example.com",
                baseUrl = "https://bitbucket.example.com"), remote)
    }

    @Test
    fun testParsesHttpsUrlWithContextPathAndWithoutGitSuffix() {
        val remote = parseCloneUrl("https://git.example.com/context/scm/PROJ/repo")
        assertEquals(DiscoveredRemote(
                repositoryUrl = "https://git.example.com/context/projects/PROJ/repos/repo",
                projectKey = "PROJ", slug = "repo", scheme = "https",
                host = "git.example.com", hostAuthority = "git.example.com",
                baseUrl = "https://git.example.com/context"), remote)
    }

    @Test
    fun testParsesSshUrlWithExplicitPort() {
        val remote = parseCloneUrl("ssh://git@bitbucket.example.com:7999/PROJ/repo.git")
        assertEquals(DiscoveredRemote(
                // No context-path signal from ssh alone -> falls back to a bare https guess.
                repositoryUrl = "https://bitbucket.example.com/projects/PROJ/repos/repo",
                projectKey = "PROJ", slug = "repo", scheme = null,
                host = "bitbucket.example.com", hostAuthority = "bitbucket.example.com:7999",
                baseUrl = "https://bitbucket.example.com"), remote)
    }

    @Test
    fun testParsesScpStyleUrlWithoutPort() {
        val remote = parseCloneUrl("git@bitbucket.example.com:PROJ/repo.git")
        assertEquals(DiscoveredRemote(
                repositoryUrl = "https://bitbucket.example.com/projects/PROJ/repos/repo",
                projectKey = "PROJ", slug = "repo", scheme = null,
                host = "bitbucket.example.com", hostAuthority = "bitbucket.example.com",
                baseUrl = "https://bitbucket.example.com"), remote)
    }

    @Test
    fun testReturnsNullForUrlWithNoRecognizableProjectAndSlug() {
        assertNull(parseCloneUrl("https://bitbucket.example.com/"))
    }

    @Test
    fun testReturnsNullForGarbageInput() {
        assertNull(parseCloneUrl("not a url at all"))
    }

    @Test
    fun testReturnsNullForGithubHttpsRemote() {
        // github.com's path (/owner/repo.git) has no /scm/ segment, so it never matches
        // HTTP_PATH_PATTERN - incidental, not a designed host check (see the next two tests).
        assertNull(parseCloneUrl("https://github.com/owner/repo.git"))
    }

    // Server's ssh clone URLs have no /scm/ segment either, so `parseCloneUrl` alone genuinely
    // cannot tell a real Bitbucket Server ssh remote apart from GitHub's/GitLab's identically-
    // shaped one - by design, this function does not try (a denylist of known non-Bitbucket
    // hosts was tried and rejected: self-hosted GitLab, Gitea, Azure DevOps on a custom domain
    // etc. would all slip through it). `discoverRepositoryUrl`'s caller confirms a candidate like
    // this with a live probe (`probeIsBitbucketServer`) before ever trusting it - see
    // ServerProbe.kt and CLAUDE.md's "Known incidents". These two tests document that ambiguity
    // rather than assert an exclusion that doesn't exist at this layer.
    @Test
    fun testParsesGithubSshRemoteAsAnUnconfirmedCandidate() {
        val remote = parseCloneUrl("git@github.com:owner/repo.git")
        assertEquals(DiscoveredRemote(
                repositoryUrl = "https://github.com/projects/owner/repos/repo",
                projectKey = "owner", slug = "repo", scheme = null,
                host = "github.com", hostAuthority = "github.com",
                baseUrl = "https://github.com"), remote)
    }

    @Test
    fun testParsesGitlabSshRemoteAsAnUnconfirmedCandidate() {
        val remote = parseCloneUrl("git@gitlab.com:owner/repo.git")
        assertEquals(DiscoveredRemote(
                repositoryUrl = "https://gitlab.com/projects/owner/repos/repo",
                projectKey = "owner", slug = "repo", scheme = null,
                host = "gitlab.com", hostAuthority = "gitlab.com",
                baseUrl = "https://gitlab.com"), remote)
    }
}
