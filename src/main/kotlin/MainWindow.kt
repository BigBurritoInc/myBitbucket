import bitbucket.BitbucketClientFactory
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import ui.*
import util.invokeLater
import java.awt.*
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.*


class MainWindow : ToolWindowFactory, DumbAware {

    private var window: ToolWindow? = null

    private lateinit var project: Project
    private lateinit var reviewingContent: Content
    private lateinit var ownContent: Content

    override fun createToolWindowContent(prj: Project, window: ToolWindow) {
        this.project = prj
        this.window = window
        currentProject = prj
        val cm = window.contentManager
        val reviewingPanel = createReviewPanel()
        val ownPanel = createOwnPanel()

        reviewingContent = addTab(cm, wrapIntoJBScroll(reviewingPanel), "Reviewing (0)")
        ownContent = addTab(cm, wrapIntoJBScroll(ownPanel), "Created (0)")

        Model.addListener(object: Listener {
            override fun ownCountChanged(count: Int) {
                ownContent.displayName = "Created ($count)"
            }

            override fun reviewedCountChanged(count: Int) {
                reviewingContent.displayName = "Reviewing ($count)"
            }
        })
        cm.setSelectedContent(reviewingContent)

        Model.addListener(reviewingPanel)
        Model.addListener(ownPanel)
        runUpdateTaskLater()

        // Re-highlight the checked-out PR as soon as the branch actually changes, instead of
        // only on the next 15s poll — covers checkout from the IDE's own branch widget/terminal,
        // not just the plugin's own Checkout button (which already calls Model.branchChanged()
        // straight from its own callback).
        prj.messageBus.connect(prj).subscribe(GitRepository.GIT_REPO_CHANGE,
                GitRepositoryChangeListener { Model.branchChanged() })
    }

    private fun runUpdateTaskLater() {
        invokeLater {
            // Uses `project` directly, not getStorerService() — that helper's data-context
            // lookup can still be null this early at startup.
            val settings = project.service<Storer>().settings
            if (settings.url.isNotBlank()) {
                settings.validate()
                UpdateTaskHolder.scheduleNew()
            }
        }
    }

    // Unused — Basic Auth login UI is hidden from the tool window, kept for reference.
    @Suppress("unused")
    private fun createLoginPanel(contentManager: ContentManager, reviewingContent: Content): JPanel {
        val wrapper = JPanel(BorderLayout())
        val passwordLabel = JBLabel("Password:")
        val passwordField = JPasswordField()
        val messageField = JBLabel()
        val button = JButton("Login")
        button.isEnabled = false
        val listener = {
            try {
                messageField.text = ""
                getStorerService().settings.validate()
                BitbucketClientFactory.password = passwordField.password
                UpdateTaskHolder.scheduleNew()
                passwordField.text = ""
                button.isEnabled = false
                contentManager.setSelectedContent(reviewingContent)
            } catch (e: ConfigurationException) {
                messageField.text = e.title + ". " + e.message
            }
        }
        button.addActionListener {listener.invoke()}

        passwordField.addKeyListener(object : KeyListener{
            override fun keyTyped(e: KeyEvent?) { }

            override fun keyPressed(e: KeyEvent?) {
                if (e != null && e.keyCode == KeyEvent.VK_ENTER)
                    listener.invoke()
            }

            override fun keyReleased(e: KeyEvent?) {
                button.isEnabled = !passwordField.password.isEmpty()
            }
        })
        val panel = JPanel(VerticalLayout(5))
        panel.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        panel.add(passwordLabel)
        panel.add(passwordField)
        panel.add(button)
        panel.add(messageField)
        wrapper.add(panel, BorderLayout.NORTH)
        return wrapper
    }

    private fun addTab(contentManager: ContentManager, component: JComponent, tabName: String): Content {
        val content = contentManager.factory.createContent(component, tabName, false)
        contentManager.addContent(content)
        return content
    }
}