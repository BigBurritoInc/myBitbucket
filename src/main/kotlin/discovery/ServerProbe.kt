package discovery

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.http.client.HttpClient
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpGet
import org.apache.http.util.EntityUtils
import util.LOG

private const val PROBE_TIMEOUT_MS = 4000

/** Confirms a [DiscoveredRemote] Server candidate actually points at a Bitbucket Server/Data
 * Center instance: one unauthenticated GET against the endpoint every Bitbucket Server exposes
 * without credentials - `/rest/api/1.0/application-properties`, which returns
 * `{"version":..., "displayName":...}`.
 *
 * Server's ssh clone URLs have no `/scm/` marker (see `SSH_PATH_PATTERN`'s comment in
 * GitRemoteDiscovery.kt), so `parseCloneUrl` accepts the same shape GitHub/GitLab ssh remotes
 * have - a denylist of known non-Bitbucket hosts was tried first and rejected as inherently
 * incomplete (self-hosted GitLab, Gitea, Azure DevOps on a custom domain, ... would all slip
 * through). Asking the actual host is the only thing that generalizes: any failure - wrong JSON
 * shape, non-200, connection refused, DNS failure, timeout - means "not a Bitbucket Server", and
 * the caller discards the candidate instead of prefilling a dialog with garbage.
 *
 * Must run off the EDT (network I/O) - callers run this inside the same modal progress they
 * already use for git-credential/secret lookups. docs: Known incidents */
fun probeIsBitbucketServer(baseUrl: String, httpClient: HttpClient): Boolean {
    val get = HttpGet("${baseUrl.trimEnd('/')}/rest/api/1.0/application-properties")
    get.config = RequestConfig.custom()
            .setConnectTimeout(PROBE_TIMEOUT_MS)
            .setSocketTimeout(PROBE_TIMEOUT_MS)
            .setConnectionRequestTimeout(PROBE_TIMEOUT_MS)
            .build()
    return try {
        val response = httpClient.execute(get)
        try {
            if (response.statusLine.statusCode != 200) return false
            val entity = response.entity ?: return false
            val node = ObjectMapper().readTree(EntityUtils.toString(entity))
            // version + displayName together is specific enough that a coincidental 200-with-JSON
            // from an unrelated server won't pass this too.
            node.has("version") && node.has("displayName")
        } finally {
            EntityUtils.consumeQuietly(response.entity)
        }
    } catch (e: Exception) {
        LOG.debug("Server discovery probe against $baseUrl failed", e)
        false
    }
}
