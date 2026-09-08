import bitbucket.BitbucketClientFactory
import com.intellij.credentialStore.Credentials
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import credentials.CloudOAuth
import credentials.CredentialStore
import credentials.GitCredentialHelper
import discovery.DiscoveredRemote
import discovery.discoverRepositoryUrl
import discovery.probeIsBitbucketServer
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import ui.*
import java.awt.*
import javax.swing.*


// A ToolWindowFactory instance is shared across every open project - the platform calls
// createToolWindowContent(project, ...) once per project on the SAME instance, which is exactly
// why every method here takes project/toolWindow as an explicit parameter instead of this class
// ever storing one on `this`. It used to: `project`/`window`/`reviewingContent`/`ownContent`/
// `inboxContent` were instance fields, so opening a second project silently overwrote the first
// project's - "Reconfigure" in project A's gear menu could end up reading/writing project B's
// Storer, and a tab title update could land on the wrong project's tool window. Fixed by making
// every per-project value a local, closed over by that project's own callbacks only.
// docs: Per-project state, Known incidents
class MainWindow : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val settings = project.getService(Storer::class.java)!!.settings
        if (settings.isConfigured()) {
            showConfiguredContent(project, toolWindow)
        } else {
            showAddProjectPrompt(project, toolWindow)
        }
    }

    private fun showConfiguredContent(project: Project, toolWindow: ToolWindow) {
        val model = project.getService(Model::class.java)
        val cm = toolWindow.contentManager
        val reviewingPanel = createReviewPanel(project)
        val ownPanel = createOwnPanel(project)
        val settings = project.getService(Storer::class.java)!!.settings

        val reviewingContent = addTab(cm, wrapIntoJBScroll(reviewingPanel), "Reviewing (0)")
        val ownContent = addTab(cm, wrapIntoJBScroll(ownPanel), "Created (0)")
        // Inbox tab is implemented but disabled pending a UX rework - see
        // UpdateTaskHolder.INBOX_FEATURE_ENABLED. Null here (this project's local, not shared)
        // just means the feature is off; inboxUnreadCountChanged() below no-ops on it. docs: Inbox
        var inboxContent: Content? = null
        if (UpdateTaskHolder.INBOX_FEATURE_ENABLED) {
            val inboxStore = project.getService(InboxStore::class.java)
            val inboxPanel = InboxPanel(inboxStore, settings)
            val content = addTab(cm, wrapIntoJBScroll(inboxPanel), "Inbox (${inboxStore.unreadCount()})")
            inboxContent = content
            inboxPanel.setOnUnreadCountChanged { count -> content.displayName = "Inbox ($count)" }
            model.addListener(inboxPanel)
        }

        model.addListener(object: Listener {
            override fun ownCountChanged(count: Int) {
                ownContent.displayName = "Created ($count)"
            }

            override fun reviewedCountChanged(count: Int) {
                reviewingContent.displayName = "Reviewing ($count)"
            }

            override fun inboxUnreadCountChanged(count: Int) {
                inboxContent?.displayName = "Inbox ($count)"
            }
        })
        cm.setSelectedContent(reviewingContent)

        model.addListener(reviewingPanel)
        model.addListener(ownPanel)
        project.getService(UpdateTaskHolder::class.java).scheduleNew()

        // Re-highlight on any branch change, not just the next poll - covers checkout from the
        // IDE's own branch widget/terminal, not only this plugin's own Checkout button.
        project.messageBus.connect(project).subscribe(GitRepository.GIT_REPO_CHANGE,
                GitRepositoryChangeListener { model.branchChanged() })

        // Inline action, no plugin.xml <action> entry - same pattern DemoModeAction uses to stay
        // out of Find Action/menus, here reached only from the tool window's own gear icon.
        val reconfigureAction = object : DumbAwareAction("Reconfigure Bitbucket repo…") {
            override fun actionPerformed(e: AnActionEvent) = reconfigure(project)
        }
        toolWindow.setAdditionalGearActions(DefaultActionGroup(reconfigureAction))
    }

    // docs: Basic Auth
    private fun showAddProjectPrompt(project: Project, toolWindow: ToolWindow) {
        val cm = toolWindow.contentManager
        val panel = JPanel(VerticalLayout(10))
        panel.border = BorderFactory.createEmptyBorder(20, 20, 20, 20)
        val button = JButton("Add Bitbucket repo")
        button.addActionListener { addProject(project, toolWindow) }
        panel.add(button)
        addTab(cm, panel, "myBitbucket")
    }

    // On a successful save, the empty-state placeholder tab is torn down and replaced with the
    // real PR tabs - the only path that ever builds them.
    private fun addProject(project: Project, toolWindow: ToolWindow) {
        val discovery = discoverRemoteAndCredentials(project, existing = null)
        val dialog = AddProjectDialog(project, existing = null, discovered = discovery.discovered,
                detectedCredentials = discovery.credentials, storedSecret = discovery.storedSecret,
                cloudSignedIn = discovery.cloudSignedIn)
        if (dialog.showAndGet()) {
            toolWindow.contentManager.removeAllContents(true)
            showConfiguredContent(project, toolWindow)
        }
    }

    // Tabs and listeners already exist from the one showConfiguredContent() call - reconfiguring
    // only persists the new Settings/credential and restarts the poll, no tab rebuilding.
    private fun reconfigure(project: Project) {
        val existing = project.getService(Storer::class.java)!!.settings
        val discovery = discoverRemoteAndCredentials(project, existing = existing)
        val dialog = AddProjectDialog(project, existing = existing, discovered = discovery.discovered,
                detectedCredentials = discovery.credentials, storedSecret = discovery.storedSecret,
                cloudSignedIn = discovery.cloudSignedIn)
        if (dialog.showAndGet()) {
            project.getService(UpdateTaskHolder::class.java).scheduleNew()
        }
    }

    // Plain data class, not a Triple - cloudSignedIn made a fourth field necessary.
    private data class DiscoveryResult(val discovered: DiscoveredRemote?, val credentials: Credentials?,
                                        val storedSecret: String?, val cloudSignedIn: Boolean)

    // Fast EDT-safe discovery, then off-EDT lookups under one modal progress: confirming a Server
    // candidate is real, a saved git credential, [existing]'s already-saved secret, and (Cloud
    // only) whether an OAuth token is already stored.
    // docs: Basic Auth, Adding Bitbucket Cloud support
    private fun discoverRemoteAndCredentials(project: Project, existing: Settings?): DiscoveryResult {
        var discovered = discoverRepositoryUrl(project)
        var credentials: Credentials? = null
        var storedSecret: String? = null
        var cloudSignedIn = false
        val scheme = discovered?.scheme
        val gitRoot = discovered?.gitRoot
        // Cloud never reuses a git credential - that's a git username+token, not the email+API
        // token Cloud's REST API wants. docs: Adding Bitbucket Cloud support
        val tryGitCredentials = scheme != null && gitRoot != null && discovered?.cloud != true
        // A Server candidate parsed from the git remote is only a URL-shape guess - ssh remotes
        // especially have no /scm/ marker to tell a real Bitbucket Server clone URL apart from
        // GitHub's/GitLab's identically-shaped one - so it's confirmed with a live, unauthenticated
        // probe before it ever reaches the dialog (see ServerProbe.kt). Cloud needs no probe:
        // bitbucket.org is the one fixed host, nothing to disambiguate. docs: Known incidents
        val needsProbe = discovered?.cloud == false
        if (tryGitCredentials || existing != null || needsProbe) {
            ProgressManager.getInstance().runProcessWithProgressSynchronously({
                if (needsProbe && !probeIsBitbucketServer(discovered!!.baseUrl!!, BitbucketClientFactory.createHttpClient())) {
                    discovered = null
                }
                if (tryGitCredentials && discovered != null) {
                    credentials = GitCredentialHelper.tryFill(project, gitRoot!!, scheme!!, discovered!!.host)
                }
                if (existing != null) {
                    storedSecret = CredentialStore.hostAuthorityFor(existing.url)
                            ?.let { CredentialStore.read(it) }?.getPasswordAsString()
                    if (existing.cloud) {
                        cloudSignedIn = CloudOAuth.stored() != null
                    }
                }
            }, "Detecting Bitbucket Repository", true, project)
        }
        return DiscoveryResult(discovered, credentials, storedSecret, cloudSignedIn)
    }

    private fun addTab(contentManager: ContentManager, component: JComponent, tabName: String): Content {
        val content = contentManager.factory.createContent(component, tabName, false)
        contentManager.addContent(content)
        return content
    }
}
