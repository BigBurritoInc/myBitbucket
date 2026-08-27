package ui

import com.intellij.openapi.components.*
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.util.xmlb.XmlSerializerUtil
import java.net.MalformedURLException
import java.net.URL
import javax.swing.JComponent


/** Settings page, one per project (registered as projectConfigurable — see plugin.xml) — each
 * project has its own Repository URL/Access Token, no longer one shared page for the whole IDE.
 * Basic Auth is hidden from this UI (see Settings.useAccessTokenAuth); Access Token is the only
 * auth method exposed. */
class BitbucketHelperConfigurable(private val project: Project) : SearchableConfigurable, Configurable.NoScroll {
    var settings: Settings = Settings()

    // Only copied into `settings` after validate() succeeds — never persist an invalid edit.
    private var formSettings = Settings()

    private val repoUrlField = JBTextField()
    private val accessTokenField = JBPasswordField()

    override fun getId(): String = "preferences.BitbucketHelper4Idea"

    override fun getDisplayName(): String = "BitbucketHelper4Idea"

    override fun isModified(): Boolean =
            repoUrlField.text != reconstructRepositoryUrl(settings) ||
                    String(accessTokenField.password) != settings.accessToken

    override fun apply() {
        val (baseUrl, projectKey, slug) = parseRepositoryUrl(repoUrlField.text)
                ?: throw ConfigurationException(
                        "Paste a link to the repository or to a pull request in it, e.g. " +
                                "https://bitbucket.com/projects/PROJECT/repos/reposlug",
                        "Can't find a project/repository in that URL")
        formSettings.url = baseUrl
        formSettings.project = projectKey
        formSettings.slug = slug
        formSettings.accessToken = String(accessTokenField.password)
        formSettings.useAccessTokenAuth = true
        formSettings.validate()
        settings.copyFrom(formSettings)
        project.getService(UpdateTaskHolder::class.java).scheduleNew()
    }

    override fun reset() {
        formSettings.copyFrom(settings)
        accessTokenField.text = settings.accessToken
        repoUrlField.text = reconstructRepositoryUrl(settings)
    }

    override fun createComponent(): JComponent {
        settings = project.getService(Storer::class.java)!!.settings
        formSettings = Settings().also { it.copyFrom(settings) }
        accessTokenField.text = settings.accessToken
        repoUrlField.text = reconstructRepositoryUrl(settings)

        return panel {
            row {
                text("You are editing myBitbucket settings for project \"${project.name}\".")
            }
            row("Repository URL:") {
                cell(repoUrlField).align(AlignX.FILL)
                        .comment("Paste a link to the repository, or to any pull request in it " +
                                "— e.g. https://bitbucket.example.com/projects/PROJECT/repos/" +
                                "repo-slug/pull-requests/123/overview")
            }
            row("Access Token:") {
                cell(accessTokenField).align(AlignX.FILL)
            }
        }
    }
}

// Matches .../projects/{PROJECT}/repos/{repo-slug} anywhere in the pasted text. Excludes
// [](){} from captured groups so a Markdown-style link ("[label](href)") still matches.
private val REPOSITORY_URL_PATTERN =
        Regex("""(https?://[^\s\[\]()]+?)/projects/([^/\s\[\]()]+)/repos/([^/\s\[\]()]+)""")

/** @return (baseUrl, project, repoSlug), or null if [text] contains no recognizable repository URL. */
private fun parseRepositoryUrl(text: String): Triple<String, String, String>? {
    val match = REPOSITORY_URL_PATTERN.find(text.trim()) ?: return null
    val (baseUrl, project, slug) = match.destructured
    return Triple(baseUrl, project, slug)
}

/** The inverse of [parseRepositoryUrl]'s relevant parts: what to show back to the user on reset(). */
private fun reconstructRepositoryUrl(settings: Settings): String =
        if (settings.url.isBlank() || settings.project.isBlank() || settings.slug.isBlank()) ""
        else "${settings.url.trimEnd('/')}/projects/${settings.project}/repos/${settings.slug}"

data class Settings(var project: String = "", var slug: String = "", var login: String = "", var url: String = "",
                    var useAccessTokenAuth: Boolean = true, var accessToken: String = "") {

    fun copyFrom(other: Settings) {
        project = other.project
        slug = other.slug
        login = other.login
        url = other.url
        accessToken = other.accessToken
        useAccessTokenAuth = other.useAccessTokenAuth
    }

    fun validate() {
        if (project.isBlank() || slug.isBlank() || url.isBlank())
            throw ConfigurationException("Fill all the settings in the Settings -> myBitbucket", "Some settings are blank")
        if (useAccessTokenAuth && accessToken.isBlank()) {
            throw ConfigurationException(
                    "You have chosen Access Token auth, a token needs to be specified", "Access Token is blank")
        }
        if (!useAccessTokenAuth && login.isBlank()) {
            throw ConfigurationException("Login field is empty")
        }
        try {
            URL(url)
        } catch (e: MalformedURLException) {
            throw ConfigurationException(e.message, "Malformed BitBucket URL")
        }
    }
}

@State(name = "BitbucketHelper4Idea", storages = arrayOf(Storage(StoragePathMacros.WORKSPACE_FILE)))
class Storer : PersistentStateComponent<Settings> {
    val settings:Settings = Settings()

    override fun getState(): Settings {
        return settings
    }

    override fun loadState(state: Settings) {
        XmlSerializerUtil.copyBean(state, settings)
    }
}
