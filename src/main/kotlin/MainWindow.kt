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
import ui.*
import util.invokeLater
import java.awt.*
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import javax.swing.*


class MainWindow : ToolWindowFactory, DumbAware {

    private var window: ToolWindow? = null

    // These are always assigned in createToolWindowContent() before anything else in this
    // class can observe them, so lateinit is safe and avoids constructing a throwaway Content
    // via an internal, unstable platform class (see git history for why that broke).
    private lateinit var loginContent: Content
    private lateinit var reviewingContent: Content
    private lateinit var ownContent: Content

    override fun createToolWindowContent(prj: Project, window: ToolWindow) {
        this.window = window
        val cm = window.contentManager
        val reviewingPanel = createReviewPanel()
        val ownPanel = createOwnPanel()

        reviewingContent = addTab(cm, wrapIntoJBScroll(reviewingPanel), "Reviewing (0)")
        ownContent = addTab(cm, wrapIntoJBScroll(ownPanel), "Created (0)")
        val loginPanel = createLoginPanel(cm, reviewingContent)
        loginContent = addTab(cm, loginPanel, "Login")

        Model.addListener(object: Listener {
            override fun ownCountChanged(count: Int) {
                ownContent.displayName = "Created ($count)"
            }

            override fun reviewedCountChanged(count: Int) {
                reviewingContent.displayName = "Reviewing ($count)"
            }
        })
        cm.setSelectedContent(loginContent)

        Model.addListener(reviewingPanel)
        Model.addListener(ownPanel)
        runUpdateTaskLater()
    }

    private fun runUpdateTaskLater() {
        //This piece of code has to be invoked after the MainWindow is constructed, so we use invokeLater
        //(StorerService is not available at the moment of window's construction)
        invokeLater {
            if (getStorerService().settings.useAccessTokenAuth) {
                getStorerService().settings.validate()
                window!!.contentManager.setSelectedContent(reviewingContent)
                UpdateTaskHolder.scheduleNew()
            }
        }
    }

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