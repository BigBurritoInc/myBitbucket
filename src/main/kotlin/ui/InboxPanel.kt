package ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import domain.InboxEvent
import domain.InboxEventKind
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel

// docs: Inbox
class InboxPanel(private val inboxStore: InboxStore, private val settings: Settings) : JPanel(), Listener {

    companion object {
        const val GAP_BETWEEN_ROWS = 5
    }

    private val listPanel = JPanel(VerticalLayout(GAP_BETWEEN_ROWS))
    private val notifyCheckbox = JCheckBox("Notify me about new activity", settings.inboxNotificationsEnabled)
    private var onUnreadCountChanged: ((Int) -> Unit)? = null

    init {
        layout = VerticalLayout(GAP_BETWEEN_ROWS)
        add(createHeader())
        add(listPanel)
        rebuild()
    }

    fun setOnUnreadCountChanged(callback: (Int) -> Unit) {
        onUnreadCountChanged = callback
    }

    override fun inboxUpdated(events: List<InboxEvent>) {
        rebuild()
    }

    private fun createHeader(): JPanel {
        val header = JPanel(HorizontalLayout(10))
        val markAllReadBtn = JButton("Mark all as read")
        markAllReadBtn.addActionListener {
            inboxStore.markAllRead()
            onUnreadCountChanged?.invoke(inboxStore.unreadCount())
            rebuild()
        }
        header.add(markAllReadBtn)
        notifyCheckbox.addActionListener {
            settings.inboxNotificationsEnabled = notifyCheckbox.isSelected
        }
        header.add(notifyCheckbox)
        return header
    }

    private fun rebuild() {
        synchronized(treeLock) {
            listPanel.removeAll()
            val events = inboxStore.inboxState.events
            if (events.isEmpty()) {
                listPanel.add(JBLabel("No activity yet.").apply { border = JBUI.Borders.empty(10) })
            } else {
                events.forEach { listPanel.add(createRow(it)) }
            }
        }
        revalidate()
        repaint()
    }

    private fun createRow(event: InboxEvent): JPanel {
        val row = JPanel(BorderLayout(8, 2))
        row.border = UIUtil.getTextFieldBorder()

        val icon = when (event.kind) {
            InboxEventKind.NEW_COMMENT -> AllIcons.Vcs.Author
            InboxEventKind.REPLY -> AllIcons.Vcs.Arrow_right
            InboxEventKind.APPROVAL -> AllIcons.General.InspectionsOK
        }
        row.add(JLabel(icon), BorderLayout.WEST)

        val content = JPanel(VerticalLayout(2))
        content.isOpaque = false

        val titleLink = LinkLabel.create(event.prTitle) {
            BrowserUtil.browse(event.prWebUrl)
            if (!event.read) {
                inboxStore.markRead(event.id)
                onUnreadCountChanged?.invoke(inboxStore.unreadCount())
                rebuild()
            }
        }
        if (!event.read) titleLink.font = titleLink.font.deriveFont(Font.BOLD)
        content.add(titleLink)

        val actionWord = when (event.kind) {
            InboxEventKind.NEW_COMMENT -> "commented"
            InboxEventKind.REPLY -> "replied"
            InboxEventKind.APPROVAL -> "approved"
        }
        content.add(JBLabel("<html><b>${escapeHtml(event.authorDisplayName)}</b> $actionWord "
                + "&#8226; ${friendlyTimeAgo(event.timestamp)}</html>"))

        event.snippet?.takeIf { it.isNotBlank() }?.let {
            val snippetLabel = JBLabel("<html>${escapeHtml(it)}</html>")
            snippetLabel.foreground = UIUtil.getContextHelpForeground()
            content.add(snippetLabel)
        }

        row.add(content, BorderLayout.CENTER)
        return row
    }
}
