package discovery

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.GitUtil

/** One repository candidate, reduced to the same shape `ui.parseAnyRepositoryUrl` gives a pasted
 * URL. [scheme] null = ssh; [gitRoot] set only via [discoverRepositoryUrl]; [cloud] true means the
 * host is `bitbucket.org`. [baseUrl] is the server root (no `/projects/.../repos/...` suffix) -
 * null for Cloud, where it's always the fixed `https://bitbucket.org` and callers don't need it.
 * Used by `probeIsBitbucketServer` to confirm a Server candidate before it's trusted: URL shape
 * alone can't tell a real Bitbucket Server ssh remote from GitHub's/GitLab's identically-shaped
 * one. docs: Adding Bitbucket Cloud support, Known incidents */
data class DiscoveredRemote(
        val repositoryUrl: String,
        val projectKey: String,
        val slug: String,
        val scheme: String?,
        val host: String,
        val hostAuthority: String,
        val cloud: Boolean = false,
        val gitRoot: VirtualFile? = null,
        val baseUrl: String? = null
)

/** One candidate repository from [project]'s git remotes (not a picker list, since the plugin
 * binds at most one repository per project). Prefers "origin", else the first remote with a
 * recognizable Bitbucket Server or Cloud clone URL; null if none.
 * docs: Per-project state */
fun discoverRepositoryUrl(project: Project): DiscoveredRemote? {
    for (repository in GitUtil.getRepositories(project)) {
        val remotes = repository.remotes
        val chosen = remotes.firstOrNull { it.name == "origin" } ?: remotes.firstOrNull() ?: continue
        val url = chosen.firstUrl ?: continue
        val parsed = parseCloneUrl(url) ?: continue
        return parsed.copy(gitRoot = repository.root)
    }
    return null
}

// (/.+)? = optional context path (Bitbucket Server can be mounted under a sub-path); the clone
// URL always has a literal /scm/ segment before the project key and repo slug for http(s).
private val HTTP_PATH_PATTERN = Regex("""^(/.+)?/scm/([^/]+)/([^/]+?)(?:\.git)?$""")

// ssh clone URLs have no /scm/ segment at all - just .../PROJECT/repo(.git). No context-path
// signal is available from an ssh remote alone.
private val SSH_PATH_PATTERN = Regex("""^(/.+)?/([^/]+)/([^/]+?)(?:\.git)?$""")

// Cloud clone URLs have neither /scm/ nor a context path - just /workspace/repo(.git). Checked
// before the Server-shaped patterns, so a Cloud ssh remote can't be misread as Server.
// docs: Adding Bitbucket Cloud support
private val CLOUD_PATH_PATTERN = Regex("""^/([^/]+)/([^/]+?)(?:\.git)?$""")

private val SCHEME_PATTERN = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://""")

/** Visible for testing. Parses a single git remote URL (http(s) or ssh, including scp-style
 * `user@host:path`) into a [DiscoveredRemote], or null if it isn't shaped like a Bitbucket
 * Server or Cloud clone URL. This is pattern matching only, no network I/O - for Server (unlike
 * Cloud's fixed `bitbucket.org` host) that's necessarily just a guess: Server's ssh clone URLs
 * have no `/scm/` marker (see SSH_PATH_PATTERN's comment), so `git@github.com:owner/repo.git`
 * parses exactly the same shape as a real Server remote. [discoverRepositoryUrl] is the one path
 * that turns this guess into something shown to the user, and it confirms Server candidates with
 * a live probe before trusting them - see `probeIsBitbucketServer`. docs: Known incidents */
internal fun parseCloneUrl(rawUrl: String): DiscoveredRemote? {
    val uri = try {
        java.net.URI(toUriParseable(rawUrl))
    } catch (e: Exception) {
        return null
    }
    val host = uri.host ?: return null
    val scheme = uri.scheme?.lowercase()
    val isHttp = scheme == "http" || scheme == "https"
    val path = uri.path ?: return null

    if (host.equals("bitbucket.org", ignoreCase = true)) {
        val match = CLOUD_PATH_PATTERN.find(path) ?: return null
        val workspace = match.groupValues[1]
        val slug = match.groupValues[2]
        if (workspace.isBlank() || slug.isBlank()) return null
        return DiscoveredRemote(
                // Cloud has one fixed host, whatever scheme/port this particular remote used.
                repositoryUrl = "https://bitbucket.org/$workspace/$slug",
                projectKey = workspace,
                slug = slug,
                scheme = if (isHttp) scheme else null,
                host = host,
                hostAuthority = "bitbucket.org",
                cloud = true)
    }

    val pattern = if (isHttp) HTTP_PATH_PATTERN else SSH_PATH_PATTERN
    val match = pattern.find(path) ?: return null
    val contextPath = match.groupValues[1]
    val projectKey = match.groupValues[2]
    val slug = match.groupValues[3]
    if (projectKey.isBlank() || slug.isBlank()) return null

    val portPart = if (uri.port > 0) ":${uri.port}" else ""
    val hostAuthority = if (uri.port > 0) "$host:${uri.port}" else host

    // ssh has no context-path signal, so the base URL falls back to a bare https guess on the
    // same host - the dialog's field stays editable for when that's wrong.
    val baseUrl = if (isHttp) "$scheme://$host$portPart$contextPath" else "https://$host"

    return DiscoveredRemote(
            repositoryUrl = "${baseUrl.trimEnd('/')}/projects/$projectKey/repos/$slug",
            projectKey = projectKey,
            slug = slug,
            scheme = if (isHttp) scheme else null,
            host = host,
            hostAuthority = hostAuthority,
            baseUrl = baseUrl)
}

/** scp-style (`git@host:PROJECT/repo.git`) has no valid URI port after the colon, so prefixing
 * `ssh://` alone isn't enough - the separating `:` must also become `/`. A URL that already has
 * an explicit scheme (`ssh://...`, `https://...`) is left untouched. */
private fun toUriParseable(rawUrl: String): String {
    if (SCHEME_PATTERN.containsMatchIn(rawUrl)) return rawUrl
    val colonIndex = rawUrl.indexOf(':')
    if (colonIndex < 0) return rawUrl
    val afterColon = rawUrl.substring(colonIndex + 1)
    if (afterColon.substringBefore('/').toIntOrNull() != null) return rawUrl // host:port, not scp-style
    return "ssh://" + rawUrl.substring(0, colonIndex) + "/" + afterColon
}
