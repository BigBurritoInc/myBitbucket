package ui

import bitbucket.data.PR
import com.intellij.ui.components.panels.VerticalLayout
import java.awt.Component
import javax.swing.JPanel




abstract class Panel : JPanel(), Listener {
    companion object {
        const val GAP_BETWEEN_PR_COMPONENTS = 5
    }

    // Tracked at the list level, not on PRComponent: components get discarded/recreated on refresh.
    private var expandedPrId: Long? = null

    init {
        layout = VerticalLayout(GAP_BETWEEN_PR_COMPONENTS)
    }

    fun dataUpdated(diff: Diff) {
        diff.added.values.sortedBy { it.updatedAt }
                .forEach{ add(newComponent(it), 0) }

        val toRemove = mutableListOf<Component>()
        for (i in 0 until componentCount) {
            val component = getComponent(i) as PRComponent
            if (diff.removed.containsKey(component.pr.id))
                toRemove.add(component)
        }
        toRemove.forEach {
            if ((it as PRComponent).pr.id == expandedPrId) expandedPrId = null
            remove(it)
        }
        for (i in 0 until componentCount) {
            val component = getComponent(i) as PRComponent
            if (diff.updated.containsKey(component.pr.id)) {
                remove(component)
                add(newComponent(diff.updated[component.pr.id]!!), i)
            }
        }
        repaint()
    }

    abstract fun createPRComponent(pr: PR): PRComponent

    // Wraps createPRComponent() with the expand/collapse wiring every PRComponent needs,
    // regardless of which Panel subclass (Reviewing vs. Created) is creating it.
    private fun newComponent(pr: PR): PRComponent {
        val component = createPRComponent(pr)
        component.setOnToggleExpand { toggleExpanded(component.pr.id) }
        component.setExpanded(component.pr.id == expandedPrId)
        return component
    }

    // Only one PR's description is expanded at a time within this list: expanding one collapses
    // whichever other one was open, clicking the already-expanded one just collapses it.
    private fun toggleExpanded(prId: Long) {
        expandedPrId = if (expandedPrId == prId) null else prId
        synchronized(treeLock) {
            for (i in 0 until componentCount) {
                val component = getComponent(i) as PRComponent
                component.setExpanded(component.pr.id == expandedPrId)
            }
        }
        revalidate()
        repaint()
    }

    override fun currentBranchChanged(branchName: String) {
        synchronized(treeLock) {
            for (i in 0 until componentCount) {
                val component = getComponent(i) as PRComponent
                component.currentBranchChanged(branchName)
            }
        }
    }
}