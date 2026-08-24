package ui

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.*
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.components.JBCheckBox
import java.awt.BorderLayout
import javax.swing.*

import java.awt.*
import com.intellij.util.xmlb.XmlSerializerUtil
import java.net.MalformedURLException
import java.net.URL


/**
 * todo refactor
 * @author Dmitrii Kopylov
 * @since 25.08.2018
 */
class BitbucketHelperConfigurable : SearchableConfigurable, Configurable.NoScroll {

    var settings: Settings = Settings()

    override fun isModified(): Boolean {
        return projectField.text != settings.project ||
                slugField.text != settings.slug ||
                urlField.text != settings.url ||
                loginField.text != settings.login ||
                accessTokenField.text != settings.accessToken ||
                useAccessTokenCheckbox.isSelected != settings.useAccessTokenAuth
    }

    override fun getId(): String {
        return "preferences.BitbucketHelper4Idea"
    }

    override fun getDisplayName(): String {
        return "BitbucketHelper4Idea"
    }

    override fun apply() {
        val wasAccessTokenAuthUsed = settings.useAccessTokenAuth
        val newSettings = Settings()
        newSettings.project = projectField.text
        newSettings.slug = slugField.text
        newSettings.url = urlField.text
        newSettings.login = loginField.text
        newSettings.accessToken = accessTokenField.text
        newSettings.useAccessTokenAuth = useAccessTokenCheckbox.isSelected
        newSettings.validate()
        settings.copyFrom(newSettings)
        when {
            newSettings.useAccessTokenAuth ->
                //We can start an update task right away, no other info (e.g. login, password) is needed from user.
                UpdateTaskHolder.scheduleNew()
            !newSettings.useAccessTokenAuth && wasAccessTokenAuthUsed ->
                //If user has just disabled access token auth we stop the update task and wait until user enters
                //a password in a separate window.
                UpdateTaskHolder.stop()
            else -> // Basic login/password auth was used before and is still used.
                //If password was already entered, we recreate a task to reflect the changes that were
                //just made in settings. Otherwise we will wait until user enters a password.
                UpdateTaskHolder.reschedule()
        }
    }

    private val projectField = JTextField()
    private val slugField = JTextField()
    private val urlField = JTextField()
    private val loginField = JTextField()
    private val useAccessTokenCheckbox = JBCheckBox("Use Access Token")
    private val accessTokenField = JTextField()

    override fun createComponent(): JComponent? {
        val project = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext()) ?: return JLabel("Empty project!")
        settings = project.service<Storer>().settings

        val mainPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.anchor = GridBagConstraints.FIRST_LINE_START
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.insets = Insets(0, 0, 0, 10)
        gbc.fill = GridBagConstraints.CENTER

        mainPanel.add(JLabel("Project"), gbc)
        gbc.gridy++
        mainPanel.add(JLabel("Repository"), gbc)
        gbc.gridy++
        mainPanel.add(JLabel("Bitbucket URL"), gbc)
        gbc.gridy++
        val loginLabel = JLabel("Login")
        mainPanel.add(loginLabel, gbc)

        gbc.gridy = 0
        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        mainPanel.add(projectField, gbc)
        gbc.gridy++
        mainPanel.add(slugField, gbc)
        gbc.gridy++
        mainPanel.add(urlField, gbc)
        gbc.gridy++
        mainPanel.add(loginField, gbc)
        gbc.gridy++
        mainPanel.add(useAccessTokenCheckbox, gbc)
        gbc.gridy++
        useAccessTokenCheckbox.addChangeListener {
            accessTokenField.isVisible = useAccessTokenCheckbox.isSelected
            loginLabel.isVisible = !useAccessTokenCheckbox.isSelected
            loginField.isVisible = !useAccessTokenCheckbox.isSelected
        }
        accessTokenField.isVisible = false
        mainPanel.add(accessTokenField, gbc)
        val wrapper = JPanel(BorderLayout())
        wrapper.add(mainPanel, BorderLayout.NORTH)
        return wrapper
    }

    override fun reset() {
        projectField.text = settings.project
        slugField.text = settings.slug
        urlField.text = settings.url
        loginField.text = settings.login
        accessTokenField.text = settings.accessToken
        useAccessTokenCheckbox.isSelected = settings.useAccessTokenAuth
    }
}

data class Settings(var project: String = "", var slug: String = "", var login: String = "", var url: String = "",
                    var useAccessTokenAuth: Boolean = false, var accessToken: String = "") {

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
            throw ConfigurationException("Fill all the BitBucket settings", "Some settings are blank")
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

fun getStorerService(): Storer =
        CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext())!!.service<Storer>()

