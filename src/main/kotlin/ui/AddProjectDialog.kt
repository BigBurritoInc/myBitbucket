package ui

import com.intellij.credentialStore.Credentials
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import credentials.CloudOAuth
import credentials.CredentialStore
import discovery.DiscoveredRemote
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.JButton
import javax.swing.JComponent

/** Add/Reconfigure Bitbucket repo dialog — [existing] null means "Add", non-null "Reconfigure".
 * docs: Basic Auth, Adding Bitbucket Cloud support */
class AddProjectDialog(
        private val project: Project,
        private val existing: Settings?,
        private val discovered: DiscoveredRemote?,
        private val detectedCredentials: Credentials?,
        // Valid only for [existing]'s current auth mode — stale after a mode switch.
        private val storedSecret: String?,
        private val cloudSignedIn: Boolean
) : DialogWrapper(project, true) {

    private val repoUrlField = JBTextField()
    private val loginField = JBTextField()
    private val passwordField = JBPasswordField()
    private val accessTokenField = JBPasswordField()

    private val cloudSignInButton = JButton("Sign in with Bitbucket…")
    private val cloudStatusLabel = JBLabel()
    private var pendingOAuthTokens: CloudOAuth.TokenBundle? = null
    // Starts at cloudSignedIn (true when the dialog opened) but changes live as the user
    // signs in/out inside this dialog — see updateCloudButton().
    private var cloudAuthenticated = cloudSignedIn

    // Must be created via buttonsGroup{}/radioButton() in createCenterPanel() — DSL v2 throws
    // if a plain JBRadioButton is handed to cell() instead, even if already grouped by AWT.
    private lateinit var useDetectedCredentialsButton: JBRadioButton
    private lateinit var useAccessTokenButton: JBRadioButton

    private lateinit var detectedCredsRow: Row
    private lateinit var loginRow: Row
    private lateinit var passwordRow: Row
    private lateinit var accessTokenRadioRow: Row
    private lateinit var accessTokenRow: Row
    private lateinit var cloudAuthRow: Row

    // Only the initial guess — see currentlyCloud() for what actually decides the dialog's mode
    // from here on.
    private val initialCloud = discovered?.cloud ?: existing?.cloud ?: false

    private val useAccessTokenInitially = when {
        existing != null -> existing.useAccessTokenAuth
        detectedCredentials != null -> false
        else -> true
    }
    private val detectedCredentialsLabel =
            if (detectedCredentials != null)
                "Use the detected Git credentials" +
                        (detectedCredentials.userName?.let { " (user: $it)" } ?: "")
            else "Use login and password"

    init {
        title = if (existing == null) "Add Bitbucket Repo" else "Reconfigure Bitbucket Repo"

        repoUrlField.text = existing?.let { reconstructRepositoryUrl(it) } ?: discovered?.repositoryUrl ?: ""
        // existing?.login is blank under Access Token auth, so also fall back to a detected git
        // username there.
        loginField.text = existing?.login?.takeIf { it.isNotBlank() } ?: detectedCredentials?.userName ?: ""
        // Detected git password wins; else pre-fill the saved secret for an existing Basic Auth
        // config.
        passwordField.text = detectedCredentials?.getPasswordAsString()
                ?: existing?.takeIf { !it.useAccessTokenAuth && !it.cloud }?.let { storedSecret }
                ?: ""
        // No git-detection equivalent — shows the saved secret only for existing Access Token
        // configs.
        accessTokenField.text = existing?.takeIf { it.useAccessTokenAuth && !it.cloud }?.let { storedSecret } ?: ""
        // Blank by default: reserved for transient sign-in feedback (an error), not steady
        // state — the button label alone already says whether you're signed in.
        cloudStatusLabel.text = ""
        updateCloudButton()

        init()
    }

    /** Re-derived from the URL field's live text, not fixed at dialog-open — pasting a URL of
     * the other flavour mid-edit switches the visible fields immediately. */
    private fun currentlyCloud(): Boolean = parseAnyRepositoryUrl(repoUrlField.text)?.cloud ?: initialCloud

    override fun createCenterPanel(): JComponent {
        repoUrlField.emptyText.text = "bitbucket.org/workspace/repo or .../projects/KEY/repos/slug"
        val result = panel {
            row("Repository URL:") {
                cell(repoUrlField).align(AlignX.FILL)
            }
            buttonsGroup {
                detectedCredsRow = row {
                    useDetectedCredentialsButton = radioButton(detectedCredentialsLabel).component
                }
                loginRow = row("Login:") { cell(loginField).align(AlignX.FILL) }
                passwordRow = row("Password:") {
                    val passwordCell = cell(passwordField).align(AlignX.FILL)
                }
                accessTokenRadioRow = row {
                    useAccessTokenButton = radioButton("Use an HTTP Access Token").component
                }
                accessTokenRow = row("Access Token:") {
                    val tokenCell = cell(accessTokenField).align(AlignX.FILL)
                }
            }
            cloudAuthRow = row {
                cell(cloudSignInButton)
                cell(cloudStatusLabel)
            }
        }

        useAccessTokenButton.isSelected = useAccessTokenInitially
        useDetectedCredentialsButton.isSelected = !useAccessTokenInitially
        val updateVisibility = {
            val cloud = currentlyCloud()
            detectedCredsRow.visible(!cloud)
            accessTokenRadioRow.visible(!cloud)
            val basicAuth = !cloud && useDetectedCredentialsButton.isSelected
            loginRow.visible(basicAuth)
            passwordRow.visible(basicAuth)
            accessTokenRow.visible(!cloud && !basicAuth)
            cloudAuthRow.visible(cloud)
        }
        updateVisibility()
        useDetectedCredentialsButton.addItemListener { updateVisibility() }
        useAccessTokenButton.addItemListener { updateVisibility() }
        repoUrlField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) { updateVisibility() }
            override fun removeUpdate(e: DocumentEvent) { updateVisibility() }
            override fun changedUpdate(e: DocumentEvent) { updateVisibility() }
        })
        cloudSignInButton.addActionListener { if (cloudAuthenticated) logOutOfCloud() else signIntoCloud() }

        return result
    }

    // Runs off the EDT; failure or cancellation leaves prior sign-in state untouched.
    private fun signIntoCloud() {
        var result: CloudOAuth.TokenBundle? = null
        var failure: Exception? = null
        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            try {
                result = CloudOAuth.authorize()
            } catch (e: ProcessCanceledException) {
                // Must propagate unmodified, not become `failure` — see
                // CloudOAuth.acceptWithCancellation(). Swallowing it here is what left this
                // dialog looking alive but inert (Cancel closing the browser had no effect) after
                // an interrupted sign-in. docs: Known incidents
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }, "Signing In to Bitbucket", true, project)
        val tokens = result
        if (tokens != null) {
            pendingOAuthTokens = tokens
            cloudAuthenticated = true
            cloudStatusLabel.text = ""
            updateCloudButton()
        } else {
            cloudStatusLabel.text = failure?.message ?: "Sign-in failed."
        }
    }

    // Immediate, not deferred to OK/Cancel like the rest of this dialog — "Log out" reads as an
    // action a user expects to take effect right away, not a pending form field.
    private fun logOutOfCloud() {
        // PasswordSafe access is blocking IO — must not run on the EDT.
        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            CloudOAuth.clear()
        }, "Signing Out of Bitbucket", false, project)
        pendingOAuthTokens = null
        cloudAuthenticated = false
        cloudStatusLabel.text = ""
        updateCloudButton()
    }

    private fun updateCloudButton() {
        cloudSignInButton.text = if (cloudAuthenticated) "Log out" else "Sign in with Bitbucket…"
    }

    override fun getPreferredFocusedComponent(): JComponent = repoUrlField

    override fun doValidate(): ValidationInfo? {
        val parsed = parseAnyRepositoryUrl(repoUrlField.text)
                ?: return ValidationInfo("Paste a link to the repository or to a pull request in it, " +
                        "e.g. https://bitbucket.example.com/projects/PROJECT/repos/reposlug or " +
                        "https://bitbucket.org/workspace/reposlug", repoUrlField)
        // Required when the flavour/auth mode being saved differs from what's configured —
        // CredentialStore is keyed by host, so a blank field would silently reuse a leftover secret.
        val secretRequired = existing == null || existing.cloud != parsed.cloud ||
                (!parsed.cloud && existing.useAccessTokenAuth != useAccessTokenButton.isSelected)
        if (parsed.cloud) {
            // cloudAuthenticated tracks live sign-in/log-out inside this dialog — see
            // updateCloudButton() — not just the cloudSignedIn snapshot from when it opened.
            if (pendingOAuthTokens == null && !cloudAuthenticated) {
                return ValidationInfo("Sign in with Bitbucket to continue", cloudSignInButton)
            }
        } else if (useDetectedCredentialsButton.isSelected) {
            if (loginField.text.isBlank()) {
                return ValidationInfo("Login is required", loginField)
            }
            if (secretRequired && passwordField.password.isEmpty()) {
                return ValidationInfo("A password is required", passwordField)
            }
        } else if (secretRequired && accessTokenField.password.isEmpty()) {
            return ValidationInfo("An Access Token is required", accessTokenField)
        }
        return null
    }

    override fun doOKAction() {
        val parsed = parseAnyRepositoryUrl(repoUrlField.text)!! // doValidate already checked
        val useAccessToken = useAccessTokenButton.isSelected
        val newSettings = if (parsed.cloud) {
            Settings(project = parsed.project, slug = parsed.slug, url = parsed.baseUrl,
                    login = "", useAccessTokenAuth = false, cloud = true)
        } else {
            Settings(project = parsed.project, slug = parsed.slug, url = parsed.baseUrl,
                    login = if (!useAccessToken) loginField.text else "",
                    useAccessTokenAuth = useAccessToken, cloud = false)
        }
        val secretText = if (parsed.cloud) null
        else String((if (useAccessToken) accessTokenField else passwordField).password)
        val oauthTokens = pendingOAuthTokens

        // PasswordSafe access is blocking IO — must not run on the EDT, hence the modal progress.
        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            if (parsed.cloud) {
                if (oauthTokens != null) {
                    CloudOAuth.store(oauthTokens)
                }
            } else if (!secretText.isNullOrBlank()) {
                val hostAuthority = CredentialStore.hostAuthorityFor(newSettings.url)
                if (hostAuthority != null) {
                    CredentialStore.write(hostAuthority, Credentials(newSettings.login, secretText))
                }
            }
            // Blank secret in the same auth mode: deliberately a no-op — existing stored secret stays.
        }, "Saving Repository Settings", false, project)

        project.getService(Storer::class.java)!!.settings.copyFrom(newSettings)
        super.doOKAction()
    }
}
