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

/** Add/Reconfigure Bitbucket repo dialog - [existing] null means "Add", non-null "Reconfigure".
 * docs: Basic Auth, Adding Bitbucket Cloud support */
class AddProjectDialog(
        private val project: Project,
        private val existing: Settings?,
        private val discovered: DiscoveredRemote?,
        private val detectedCredentials: Credentials?,
        // Valid only for [existing]'s current auth mode - stale after a mode switch.
        private val storedSecret: String?,
        private val cloudSignedIn: Boolean
) : DialogWrapper(project, true) {

    private val repoUrlField = JBTextField()
    private val loginField = JBTextField()
    private val passwordField = JBPasswordField()
    private val accessTokenField = JBPasswordField()

    private val cloudSignInButton = JButton("Sign in with Bitbucket…")
    private val cloudStatusLabel = JBLabel()
    // Captured before updateCloudSignInUi() ever repaints the label green for success or leaves
    // it showing a sign-in failure message - the color/text a fresh error should reset back to.
    private val defaultStatusForeground = cloudStatusLabel.foreground
    private var pendingOAuthTokens: CloudOAuth.TokenBundle? = null
    // Starts at cloudSignedIn (true when the dialog opened) but changes live as the user
    // signs in/out inside this dialog - see updateCloudSignInUi().
    private var cloudAuthenticated = cloudSignedIn

    // Must be created via buttonsGroup{}/radioButton() in createCenterPanel() - DSL v2 throws
    // if a plain JBRadioButton is handed to cell() instead, even if already grouped by AWT.
    private lateinit var useDetectedCredentialsButton: JBRadioButton
    private lateinit var useAccessTokenButton: JBRadioButton

    private lateinit var detectedCredsRow: Row
    private lateinit var loginRow: Row
    private lateinit var passwordRow: Row
    private lateinit var accessTokenRadioRow: Row
    private lateinit var accessTokenRow: Row
    private lateinit var cloudAuthRow: Row

    // Only the initial guess - see currentlyCloud() for what actually decides the dialog's mode
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
        // No git-detection equivalent - shows the saved secret only for existing Access Token
        // configs.
        accessTokenField.text = existing?.takeIf { it.useAccessTokenAuth && !it.cloud }?.let { storedSecret } ?: ""

        init()
    }

    /** Re-derived from the URL field's live text, not fixed at dialog-open - pasting a URL of
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
            // Which fields are even relevant (and so required) just changed along with
            // visibility - re-check right away rather than waiting for the next keystroke.
            updateOkButtonState()
        }
        updateVisibility()
        // Sets the sign-in button/status label to their initial state and, since it also calls
        // updateOkButtonState(), the OK button - deliberately not done back in init{}: doValidate()
        // reads useDetectedCredentialsButton/useAccessTokenButton, which are still lateinit and
        // unassigned until the panel { } block above runs.
        updateCloudSignInUi()
        useDetectedCredentialsButton.addItemListener { updateVisibility() }
        useAccessTokenButton.addItemListener { updateVisibility() }
        repoUrlField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) { updateVisibility() }
            override fun removeUpdate(e: DocumentEvent) { updateVisibility() }
            override fun changedUpdate(e: DocumentEvent) { updateVisibility() }
        })
        // These three don't affect row visibility, only whether there's a complete, valid
        // credential to save - updateOkButtonState() alone, no need for the full updateVisibility().
        for (field in listOf(loginField, passwordField, accessTokenField)) {
            field.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = updateOkButtonState()
                override fun removeUpdate(e: DocumentEvent) = updateOkButtonState()
                override fun changedUpdate(e: DocumentEvent) = updateOkButtonState()
            })
        }
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
                // Must propagate unmodified, not become `failure` - see
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
            updateCloudSignInUi()
        } else {
            // Doesn't go through updateCloudSignInUi(): that always renders the *steady* state
            // (green "Successfully logged in" or blank) from cloudAuthenticated, which would
            // immediately overwrite this transient failure message.
            cloudStatusLabel.foreground = defaultStatusForeground
            cloudStatusLabel.text = failure?.message ?: "Sign-in failed."
            updateOkButtonState()
        }
    }

    // Immediate, not deferred to OK/Cancel like the rest of this dialog - "Log out" reads as an
    // action a user expects to take effect right away, not a pending form field.
    private fun logOutOfCloud() {
        // PasswordSafe access is blocking IO - must not run on the EDT.
        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            CloudOAuth.clear()
        }, "Signing Out of Bitbucket", false, project)
        pendingOAuthTokens = null
        cloudAuthenticated = false
        updateCloudSignInUi()
    }

    // Drives the sign-in button's label and the status text/color next to it from
    // [cloudAuthenticated] - this is the *steady*-state renderer (green "Successfully logged in"
    // or blank); a transient sign-in failure message is set directly by signIntoCloud() instead,
    // which is also why it calls updateOkButtonState() on its own rather than through here.
    private fun updateCloudSignInUi() {
        cloudSignInButton.text = if (cloudAuthenticated) "Log out" else "Sign in with Bitbucket…"
        if (cloudAuthenticated) {
            // Same green as the PR cards' Approve/Merge buttons - one "this succeeded" color
            // across the plugin. docs: Known incidents
            cloudStatusLabel.foreground = APPROVE_GREEN
            cloudStatusLabel.text = "Successfully logged in"
        } else {
            cloudStatusLabel.foreground = defaultStatusForeground
            cloudStatusLabel.text = ""
        }
        updateOkButtonState()
    }

    // doValidate() is only ever consulted automatically on the OK click itself - this dialog's
    // DialogPanel opts out of the platform's continuous validation (see
    // DialogWrapper.continuousValidation()), so nothing proactively greys out OK otherwise. Called
    // from every listener above that can change what doValidate() would return.
    private fun updateOkButtonState() {
        isOKActionEnabled = doValidate() == null
    }

    override fun getPreferredFocusedComponent(): JComponent = repoUrlField

    override fun doValidate(): ValidationInfo? {
        val parsed = parseAnyRepositoryUrl(repoUrlField.text)
                ?: return ValidationInfo("Paste a link to the repository or to a pull request in it, " +
                        "e.g. https://bitbucket.example.com/projects/PROJECT/repos/reposlug or " +
                        "https://bitbucket.org/workspace/reposlug", repoUrlField)
        if (parsed.cloud) {
            // cloudAuthenticated tracks live sign-in/log-out inside this dialog - see
            // updateCloudSignInUi() - not just the cloudSignedIn snapshot from when it opened.
            if (pendingOAuthTokens == null && !cloudAuthenticated) {
                return ValidationInfo("Sign in with Bitbucket to continue", cloudSignInButton)
            }
        } else if (useDetectedCredentialsButton.isSelected) {
            if (loginField.text.isBlank()) {
                return ValidationInfo("Login is required", loginField)
            }
            // Always required, even when reconfiguring the same auth mode: the field is already
            // pre-filled with the real stored secret in that case (see init{}), so this only ever
            // bites when the user has actually cleared it - which used to silently fall back to
            // "keep the old stored secret" (see doOKAction()) while OK stayed clickable, an easy
            // trap to hit by accident. docs: Known incidents
            if (passwordField.password.isEmpty()) {
                return ValidationInfo("A password is required", passwordField)
            }
        } else if (accessTokenField.password.isEmpty()) {
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

        // PasswordSafe access is blocking IO - must not run on the EDT, hence the modal progress.
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
            // secretText is never blank here in practice - doValidate() now requires the
            // relevant field to be non-empty whenever !parsed.cloud (see "Known incidents"), so
            // OK can't be reached otherwise. Left as a guard rather than an assertion: harmless if
            // it ever is, since it just means "nothing new to store, existing secret stays".
        }, "Saving Repository Settings", false, project)

        project.getService(Storer::class.java)!!.settings.copyFrom(newSettings)
        super.doOKAction()
    }
}
