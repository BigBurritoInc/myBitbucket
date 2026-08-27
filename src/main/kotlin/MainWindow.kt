import bitbucket.BitbucketClientFactory
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
        val model = prj.getService(Model::class.java)
        val cm = window.contentManager
        val reviewingPanel = createReviewPanel(prj)
        val ownPanel = createOwnPanel(prj)

        reviewingContent = addTab(cm, wrapIntoJBScroll(reviewingPanel), "Reviewing (0)")
        ownContent = addTab(cm, wrapIntoJBScroll(ownPanel), "Created (0)")

        model.addListener(object: Listener {
            override fun ownCountChanged(count: Int) {
                ownContent.displayName = "Created ($count)"
            }

            override fun reviewedCountChanged(count: Int) {
                reviewingContent.displayName = "Reviewing ($count)"
            }
        })
        cm.setSelectedContent(reviewingContent)

        model.addListener(reviewingPanel)
        model.addListener(ownPanel)
        runUpdateTaskLater()

        // Re-highlight the checked-out PR as soon as the branch actually changes, instead of
        // only on the next 15s poll — covers checkout from the IDE's own branch widget/terminal,
        // not just the plugin's own Checkout button (which already calls model.branchChanged()
        // straight from its own callback).
        prj.messageBus.connect(prj).subscribe(GitRepository.GIT_REPO_CHANGE,
                GitRepositoryChangeListener { model.branchChanged() })
    }

    private fun runUpdateTaskLater() {
        invokeLater {
            val settings = project.getService(Storer::class.java)!!.settings
            if (settings.url.isNotBlank()) {
                settings.validate()
                project.getService(UpdateTaskHolder::class.java).scheduleNew()
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
                project.getService(Storer::class.java)!!.settings.validate()
                BitbucketClientFactory.password = passwordField.password
                project.getService(UpdateTaskHolder::class.java).scheduleNew()
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
