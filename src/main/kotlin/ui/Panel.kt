package ui

import bitbucket.ReviewAttention
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import domain.PR
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * @param attentionOf how much of the user's attention each pull request still wants. Drives both the
 *        display order (whatever needs doing first, approved last) and which ones collapse behind
 *        the "Show already approved" button. The Created list has no reviewing angle, so it leaves
 *        the default and every pull request sits in one bucket, ordered by recency alone.
 */
abstract class Panel(
        private val attentionOf: (PR) -> ReviewAttention = { ReviewAttention.NEEDS_ATTENTION }
) : JPanel(), Listener {
    companion object {
        const val GAP_BETWEEN_PR_COMPONENTS = 5
    }

    // The list is its own model rather than reading state back off the child components: hidden pull
    // requests have no component at all, and the footer button isn't a PRComponent either.
    private val prs: MutableList<PR> = ArrayList()

    // Tracked at the list level, not on PRComponent: components get discarded/recreated on refresh.
    private var expandedPrId: Long? = null
    private var showHidden = false
    private var currentBranch: String? = null

    init {
        layout = VerticalLayout(GAP_BETWEEN_PR_COMPONENTS)
    }

    fun dataUpdated(diff: Diff) {
        prs.removeAll { diff.removed.containsKey(it.id) }
        for (i in prs.indices) {
            diff.updated[prs[i].id]?.let { prs[i] = it }
        }
        // Oldest first so that, inserting each at the top, the newest ends up first overall.
        diff.added.values.sortedBy { it.updatedAt }.forEach { prs.add(0, it) }
        rebuild()
    }

    abstract fun createPRComponent(pr: PR): PRComponent

    private fun rebuild() {
        synchronized(treeLock) {
            removeAll()
            // sortedBy is stable, so within a bucket the insertion order — newest first — survives.
            // Sorting the whole list rather than the visible part is what keeps the order steady when
            // the approved ones are revealed: they append to the end instead of interleaving.
            val ordered = prs.sortedBy { attentionOf(it).ordinal }
            val (hidden, alwaysVisible) = ordered.partition { attentionOf(it) == ReviewAttention.APPROVED }
            val visible = if (showHidden) ordered else alwaysVisible
            visible.forEach { add(newComponent(it)) }
            if (hidden.isNotEmpty()) add(toggleHiddenButton())
        }
        revalidate()
        repaint()
    }

    // A plain JButton, same chrome as each row's Checkout button, just without an icon. Wrapped in a
    // left-aligned FlowLayout because VerticalLayout stretches its children to the full container
    // width, which would give a button as wide as the tool window.
    private fun toggleHiddenButton(): JComponent {
        val button = JButton(if (showHidden) "Hide already approved" else "Show already approved")
        button.addActionListener {
            showHidden = !showHidden
            rebuild()
        }
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(GAP_BETWEEN_PR_COMPONENTS)
            add(button)
        }
    }

    // Wraps createPRComponent() with the expand/collapse wiring every PRComponent needs,
    // regardless of which Panel subclass (Reviewing vs. Created) is creating it.
    private fun newComponent(pr: PR): PRComponent {
        val component = createPRComponent(pr)
        component.setOnToggleExpand { toggleExpanded(component.pr.id) }
        component.setExpanded(component.pr.id == expandedPrId)
        currentBranch?.let { component.currentBranchChanged(it) }
        return component
    }

    // Only one PR's description is expanded at a time within this list: expanding one collapses
    // whichever other one was open, clicking the already-expanded one just collapses it.
    private fun toggleExpanded(prId: Long) {
        expandedPrId = if (expandedPrId == prId) null else prId
        forEachPRComponent { it.setExpanded(it.pr.id == expandedPrId) }
        revalidate()
        repaint()
    }

    override fun currentBranchChanged(branchName: String) {
        currentBranch = branchName
        forEachPRComponent { it.currentBranchChanged(branchName) }
    }

    // Not every child is a PRComponent — the footer holds the show/hide button.
    private fun forEachPRComponent(action: (PRComponent) -> Unit) {
        synchronized(treeLock) {
            components.filterIsInstance<PRComponent>().forEach(action)
        }
    }
}
