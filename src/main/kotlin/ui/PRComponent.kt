package ui

import bitbucket.data.PR
import bitbucket.data.PRParticipant
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.ui.SeparatorComponent
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import java.util.function.Consumer
import javax.swing.*
import javax.swing.event.HyperlinkEvent


// What a PRComponent's Checkout/Approve/Merge buttons need. Model implements this; PanelRunner
// stubs it with no-ops, since it runs standalone without a real project — see CLAUDE.md
// "PanelRunner".
interface PRActions {
    fun checkout(pr: PR)
    fun approve(pr: PR, callback: Consumer<Boolean>)
    fun merge(pr: PR, callback: Consumer<Boolean>)
}

open class PRComponent(val pr: PR, protected val actions: PRActions) : JPanel() {

    val greenColor: Color = JBColor.GREEN

    private val checkoutBtn = JButton("Checkout", AllIcons.Vcs.Branch)
    val approveBtn = JButton("Approve", AllIcons.General.InspectionsOK)
    val mergeBtn = JButton("Merge", AllIcons.Vcs.Merge)
    private val prLink: JLabel
    private val targetBranchLabel: JLabel
    private val authorLabel: JBLabel
    private val reviewersPanel: ReviewersPanel

    private val descriptionPane: JEditorPane? =
            if (pr.description.isNotBlank()) createDescriptionPane(pr.description) else null
    // Description wrapped with a thin rule above and below, so it reads as its own block.
    private val descriptionBlock: JPanel? = this.descriptionPane?.let { pane ->
        JPanel(BorderLayout(0, TOP_BOTTOM_INSET / 2)).apply {
            isOpaque = false
            add(descriptionSeparator(), BorderLayout.NORTH)
            add(pane, BorderLayout.CENTER)
            add(descriptionSeparator(), BorderLayout.SOUTH)
        }
    }
    // JLabel, not JButton: button chrome padding fights the GridBagLayout insets below. Always
    // present (blank icon when there's no description) so column 0's width never depends on
    // whether a given PR has a description — see createExpandToggleLabel().
    private val expandToggleLabel: JLabel = createExpandToggleLabel()
    private var expanded = false
    private var onToggleExpand: (() -> Unit)? = null
    // GridBagConstraints for the description row; added/removed on toggle rather than hidden.
    private lateinit var descriptionGbc: GridBagConstraints

    companion object {
        private val LEFT_RIGHT_INSET = JBUI.scale(7)
        private val TOP_BOTTOM_INSET = JBUI.scale(10)
        private val CHEVRON_INSET = JBUI.scale(1)
        private const val TOTAL_COLUMNS = 4
    }

    init {
        this.prLink = this.createPrLinkLabel(this.pr)
        this.targetBranchLabel = JBLabel(this.pr.toBranch, AllIcons.Vcs.Arrow_right, SwingConstants.LEFT)
        val updatedAt = friendlyTimeAgo(this.pr.updatedAt)
        val commentsCount = "${this.pr.commentCount} " + if (this.pr.commentCount == 1) { "comment" } else { "comments" }
        // <nobr> stops the HTML label from wrapping "ago" onto its own line.
        this.authorLabel = JBLabel("<html><nobr><b>${escapeHtml(this.pr.author.user.displayName)}</b> "
                                 + "($commentsCount) last updated $updatedAt</nobr></html>",
                                 AllIcons.Vcs.Author, SwingConstants.LEFT)
        this.reviewersPanel = ReviewersPanel(ArrayList(this.pr.reviewers))
        this.mergeBtn.isVisible = false
        this.approveBtn.isVisible = false

        this.createComponentSpecificButton()
        this.checkoutBtn.addActionListener { actions.checkout(this.pr) }

        this.border = UIUtil.getTextFieldBorder()
        this.background = UIUtil.getListBackground(false)

        this.layout = GridBagLayout()
        val gbc = GridBagConstraints()
        gbc.insets.left = LEFT_RIGHT_INSET
        gbc.insets.right = LEFT_RIGHT_INSET
        gbc.insets.top = TOP_BOTTOM_INSET
        gbc.insets.bottom = TOP_BOTTOM_INSET / 2

        // Row 0: chevron (col 0) + title (col 1..). CHEVRON_INSET on both sides keeps the
        // left-edge-to-chevron gap equal to the chevron-to-title gap.
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.gridwidth = 1
        gbc.weightx = 0.0
        gbc.anchor = GridBagConstraints.NORTHWEST
        gbc.fill = GridBagConstraints.NONE
        gbc.insets.left = CHEVRON_INSET
        gbc.insets.right = CHEVRON_INSET
        this.add(this.expandToggleLabel, gbc)

        gbc.gridx = 1
        gbc.gridwidth = TOTAL_COLUMNS - 1
        gbc.weightx = 1.0
        gbc.anchor = GridBagConstraints.WEST
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets.left = 0
        this.add(this.prLink, gbc)

        gbc.gridx = 0
        gbc.gridwidth = TOTAL_COLUMNS
        gbc.gridy++
        gbc.insets.top = 0
        gbc.insets.bottom = 0
        gbc.insets.left = LEFT_RIGHT_INSET
        gbc.insets.right = LEFT_RIGHT_INSET

        // Row 1, under the title; setExpanded() adds/removes descriptionBlock here on demand.
        this.descriptionGbc = gbc.clone() as GridBagConstraints
        this.descriptionGbc.insets.top = TOP_BOTTOM_INSET / 2
        this.descriptionGbc.insets.bottom = TOP_BOTTOM_INSET / 2
        this.descriptionGbc.fill = GridBagConstraints.HORIZONTAL

        gbc.gridy++
        this.add(this.authorLabel, gbc)
        gbc.gridy++
        this.add(this.targetBranchLabel, gbc)

        gbc.weightx = 0.0
        gbc.gridwidth = 1
        gbc.gridy++
        gbc.fill = GridBagConstraints.NONE
        gbc.insets.top = TOP_BOTTOM_INSET
        gbc.insets.bottom = TOP_BOTTOM_INSET
        // Column 0 stays reserved for the chevron (never used here) so its width never depends
        // on the button row. Checkout and approve share one cell — same position, same size —
        // since exactly one of them is ever visible at a time (see currentBranchChanged()).
        gbc.gridx = 1
        this.approveBtn.preferredSize = this.checkoutBtn.preferredSize
        this.add(this.checkoutBtn, gbc)
        this.add(this.approveBtn, gbc)
        gbc.gridx = 2
        gbc.anchor = GridBagConstraints.WEST
        this.add(this.mergeBtn, gbc)

        gbc.gridx = 3
        gbc.weightx = 1.0
        gbc.anchor = GridBagConstraints.EAST
        this.add(this.reviewersPanel, gbc)
    }

    /** Registers the callback [Panel] uses to coordinate "only one PR expanded at a time". */
    fun setOnToggleExpand(callback: () -> Unit) {
        this.onToggleExpand = callback
    }

    /** Shows or hides this card's description row. No-op if there's no description or state is unchanged. */
    fun setExpanded(expand: Boolean) {
        val block = this.descriptionBlock ?: return
        if (expand == this.expanded) return
        this.expanded = expand
        expandToggleLabel.icon = if (expand) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        expandToggleLabel.toolTipText = if (expand) "Hide description" else "Show description"
        if (expand) {
            this.add(block, this.descriptionGbc)
        } else {
            this.remove(block)
        }
        this.revalidate()
        this.repaint()
    }

    private fun createPrLinkLabel(pr: PR): LinkLabel<*> {
        val prLinkLabel = LinkLabel.create(pr.title) { BrowserUtil.browse(pr.links.getSelfHref()) }
        prLinkLabel.font = prLinkLabel.font.deriveFont(prLinkLabel.font.size * 1.2f)
        prLinkLabel.toolTipText = "<html>${pr.links.getSelfHref()}</html>"
        return prLinkLabel
    }

    private fun createExpandToggleLabel(): JLabel {
        if (this.descriptionPane == null) return JLabel(EmptyIcon.create(AllIcons.General.ArrowRight))
        val label = JLabel(AllIcons.General.ArrowRight)
        label.toolTipText = "Show description"
        label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        label.addMouseListener(object : MouseAdapter() {
            // Read lazily, not captured: onToggleExpand is still null at construction time.
            override fun mouseClicked(e: MouseEvent) {
                this@PRComponent.onToggleExpand?.invoke()
            }
        })
        return label
    }

    // One thin line colored like the card's border, instead of SeparatorComponent's default thicker highlight+shadow pair.
    private fun descriptionSeparator(): SeparatorComponent = SeparatorComponent(0, UIUtil.getBoundsColor(), null)

    private fun createDescriptionPane(description: String): JEditorPane {
        val pane = WrappingHtmlPane()
        pane.contentType = "text/html"
        pane.isEditable = false
        pane.isOpaque = false
        pane.border = null
        // Pull theme colors from UIManager — Swing's HTML rendering defaults to black text.
        // Label.foreground itself is too pale to read at this text size under some light themes
        // (e.g. Islands Light) — Gray._30 is a standard IntelliJ Platform gray, dark enough to
        // read without going full black. Dark themes are untouched: still Label.foreground.
        val foreground = if (JBColor.isBright()) Gray._30 else UIManager.getColor("Label.foreground") ?: Color.BLACK
        val font = UIManager.getFont("Label.font")
        val colorHex = String.format("#%02x%02x%02x", foreground.red, foreground.green, foreground.blue)
        val fontFamily = font?.family ?: "sans-serif"
        val fontSize = font?.size ?: 12
        pane.text = "<html><body style=\"font-family: '$fontFamily'; font-size: ${fontSize}pt; " +
                "color: $colorHex; margin: 0;\">${markdownToHtml(description)}</body></html>"
        pane.caretPosition = 0
        pane.addHyperlinkListener { e ->
            // e.getURL(), not e.url: "URL" being all-caps breaks Kotlin's synthetic-property mapping.
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                e.getURL()?.let { BrowserUtil.browse(it.toString()) }
            }
        }
        return pane
    }

    open fun createComponentSpecificButton() {
        this.approveBtn.addActionListener {
            actions.approve(this.pr, Consumer { approved ->
                if (approved) {
                    this.approveBtn.text = "Approved"
                    this.approveBtn.isEnabled = false
                }
            })
        }
        this.approveBtn.foreground = this.greenColor
        this.approveBtn.font = UIUtil.getButtonFont()
        this.approveBtn.isVisible = true
    }

    open fun currentBranchChanged(branch: String) {
        val isActive = this.pr.fromBranch == branch
        this.border = if (isActive) BorderFactory.createLineBorder(UIUtil.getListSelectionBackground(), 3)
                else UIUtil.getTextFieldBorder()
        this.approveBtn.isVisible = isActive
        this.checkoutBtn.isVisible = !isActive
    }
}

// JEditorPane ignores the layout's assigned width when sizing itself. Forces a reflow at the
// actual assigned width via the standard setSize()-then-getPreferredSize() trick.
private class WrappingHtmlPane : JEditorPane() {
    override fun getPreferredSize(): Dimension {
        val availableWidth = width.takeIf { it > 0 }
                ?: parent?.width?.takeIf { it > 0 }
                ?: return super.getPreferredSize()
        setSize(availableWidth, Short.MAX_VALUE.toInt())
        return Dimension(availableWidth, super.getPreferredSize().height)
    }
}

/** A pull-request where an author is yourself */
class OwnPRComponent(ownPR: PR, actions: PRActions) : PRComponent(ownPR, actions) {

    override fun createComponentSpecificButton() {
        this.mergeBtn.isVisible = true
        this.mergeBtn.isEnabled = pr.mergeStatus.canMerge
        if (!pr.mergeStatus.canMerge) {
            this.mergeBtn.toolTipText = pr.mergeStatus.vetoesSummaries()
        } else {
            this.mergeBtn.addActionListener {
                actions.merge(this.pr, Consumer { approved ->
                    if (approved) {
                        this.mergeBtn.text = "Merged"
                        this.mergeBtn.isEnabled = false
                    }
                })
            }
        }
        this.mergeBtn.foreground = this.greenColor
        this.mergeBtn.font = UIUtil.getButtonFont()
    }

    override fun currentBranchChanged(branch: String) {
        super.currentBranchChanged(branch)
        this.approveBtn.isVisible = false
    }
}

class ReviewersPanel(reviewers: MutableList<PRParticipant>) : JPanel(HorizontalLayout(5)) {
    companion object {
        const val ALWAYS_DISPLAY_REVIEWERS_COUNT = 5
    }

    init {
        this.isOpaque = false
        reviewers.sortWith(Comparator { o1, o2 -> o1.status.compareTo(o2.status) })
        val labels: Map<PRParticipant, ReviewerItem> = reviewers.associateWith { prParticipant -> ReviewerItem(prParticipant) }

        val alwaysVisibleReviewerCount = if (reviewers.size == ALWAYS_DISPLAY_REVIEWERS_COUNT + 1)
            reviewers.size
        else
            Math.min(ALWAYS_DISPLAY_REVIEWERS_COUNT, reviewers.size)

        reviewers.take(alwaysVisibleReviewerCount).forEach { this.add(labels[it]) }

        val reviewersInCombo = reviewers.size - alwaysVisibleReviewerCount
        if (reviewersInCombo > 0) {
            val otherReviewersButton = JLayeredPane()
            val realButton = JButton("+$reviewersInCombo")
            val avatarSize = ReviewerComponentFactory.avatarSize
            realButton.setBounds(0, ReviewerComponentFactory.statusIconSize / 3, avatarSize, avatarSize)
            otherReviewersButton.add(realButton)
            this.add(otherReviewersButton)
            val height = this.preferredSize.height
            otherReviewersButton.preferredSize = Dimension(height, height)
            val menu = JBPopupMenu()
            reviewers.takeLast(reviewersInCombo).forEach { prParticipant: PRParticipant ->
                val itemPanel = JPanel(FlowLayout(FlowLayout.LEFT))
                itemPanel.add(labels[prParticipant])
                itemPanel.add(JLabel(prParticipant.user.displayName))
                menu.add(itemPanel)
            }
            realButton.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    menu.show(otherReviewersButton, e.x, e.y)
                }
            })
        }
    }
}

class ReviewerItem(reviewer: PRParticipant) : JLayeredPane() {
    // Tooltip shows the name, since the icon itself is always the same generic avatar.
    private val avatarLabel: JLabel = JLabel(ReviewerComponentFactory.defaultAvatarIcon).apply {
        toolTipText = reviewer.user.displayName
    }

    companion object {
        val AVATAR_Z_INDEX = Integer(0)
        val STATUS_ICON_Z_INDEX = Integer(1)
    }

    init {
        val avatarSize = ReviewerComponentFactory.avatarSize
        val statusIconSize = ReviewerComponentFactory.statusIconSize
        val size = avatarSize + statusIconSize / 3
        this.preferredSize = Dimension(size, size)
        this.avatarLabel.setBounds(0, statusIconSize / 3, avatarSize, avatarSize)
        this.add(this.avatarLabel, AVATAR_Z_INDEX)
        val statusIcon = ReviewerComponentFactory.getStatusIcon(reviewer)
        if (statusIcon != null) {
            val statusLabel = JLabel(statusIcon)
            statusLabel.setBounds(size - statusIconSize, 0, statusIconSize, statusIconSize)
            this.add(statusLabel, STATUS_ICON_Z_INDEX)
        }
    }
}