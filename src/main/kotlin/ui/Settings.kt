package ui

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.util.xmlb.XmlSerializerUtil

/** The one Bitbucket repository bound to this IDE project; no secret — see CredentialStore.
 * docs: Basic Auth */
data class Settings(var project: String = "", var slug: String = "", var login: String = "",
                     var url: String = "", var useAccessTokenAuth: Boolean = true,
                     var cloud: Boolean = false,
                     // Legacy field for the one-time pre-0.6 migration only — do not read/write
                     // it elsewhere. docs: Basic Auth
                     var accessToken: String = "",
                     var inboxNotificationsEnabled: Boolean = false) {

    fun copyFrom(other: Settings) {
        project = other.project
        slug = other.slug
        login = other.login
        url = other.url
        useAccessTokenAuth = other.useAccessTokenAuth
        cloud = other.cloud
        inboxNotificationsEnabled = other.inboxNotificationsEnabled
        // accessToken deliberately NOT copied here — see its own doc comment above.
    }

    fun isConfigured(): Boolean = url.isNotBlank() && project.isNotBlank() && slug.isNotBlank()
}

@State(name = "BitbucketHelper4Idea", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class Storer : PersistentStateComponent<Settings> {
    val settings: Settings = Settings()

    override fun getState(): Settings = settings

    override fun loadState(state: Settings) {
        XmlSerializerUtil.copyBean(state, settings)
    }
}

const val CLOUD_HOST = "bitbucket.org"
const val CLOUD_BASE_URL = "https://$CLOUD_HOST"

// Matches .../projects/{PROJECT}/repos/{repo-slug} anywhere in the pasted text (a repository
// link, or a pull-request link inside it). Excludes [](){} from captured groups so a
// Markdown-style link ("[label](href)") still matches.
private val REPOSITORY_URL_PATTERN =
        Regex("""(https?://[^\s\[\]()]+?)/projects/([^/\s\[\]()]+)/repos/([^/\s\[\]()]+)""")

// bitbucket.org/{workspace}/{repo_slug} anywhere in the pasted text — same reasoning as above,
// minus the Server-only /projects/.../repos/... infix Cloud URLs don't have.
private val CLOUD_REPOSITORY_URL_PATTERN =
        Regex("""https?://bitbucket\.org/([^/\s\[\]()]+)/([^/\s\[\]()]+)""", RegexOption.IGNORE_CASE)

/** @return (baseUrl, project, repoSlug), or null if [text] has no recognizable Server URL.
 * Cloud-agnostic callers should use [parseAnyRepositoryUrl] instead. */
fun parseRepositoryUrl(text: String): Triple<String, String, String>? {
    val match = REPOSITORY_URL_PATTERN.find(text.trim()) ?: return null
    val (baseUrl, project, slug) = match.destructured
    return Triple(baseUrl, project, slug)
}

/** One repository reference parsed from pasted text, flavour included — same shape
 * `discovery.DiscoveredRemote` produces, so a pasted URL and a discovered git remote are
 * indistinguishable to `ui.AddProjectDialog`. */
data class ParsedRepositoryUrl(val baseUrl: String, val project: String, val slug: String, val cloud: Boolean)

/** Tries the Cloud shape first, then the Server shape. Null if [text] contains neither.
 * docs: Adding Bitbucket Cloud support */
fun parseAnyRepositoryUrl(text: String): ParsedRepositoryUrl? {
    val cloudMatch = CLOUD_REPOSITORY_URL_PATTERN.find(text.trim())
    if (cloudMatch != null) {
        val (workspace, slug) = cloudMatch.destructured
        return ParsedRepositoryUrl(CLOUD_BASE_URL, workspace, slug, cloud = true)
    }
    val server = parseRepositoryUrl(text) ?: return null
    val (baseUrl, project, slug) = server
    return ParsedRepositoryUrl(baseUrl, project, slug, cloud = false)
}

/** Inverse of [parseAnyRepositoryUrl]. [baseUrl] is ignored for Cloud (one fixed host). */
fun reconstructRepositoryUrl(baseUrl: String, project: String, slug: String, cloud: Boolean = false): String =
        if (project.isBlank() || slug.isBlank()) ""
        else if (cloud) "$CLOUD_BASE_URL/$project/$slug"
        else if (baseUrl.isBlank()) ""
        else "${baseUrl.trimEnd('/')}/projects/$project/repos/$slug"

fun reconstructRepositoryUrl(settings: Settings): String =
        reconstructRepositoryUrl(settings.url, settings.project, settings.slug, settings.cloud)
