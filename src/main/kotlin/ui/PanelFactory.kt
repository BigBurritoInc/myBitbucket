package ui

import bitbucket.CurrentUser
import bitbucket.ReviewAttention
import bitbucket.attentionFor
import domain.PR
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

// docs: Per-project state
fun createReviewPanel(project: Project): Panel {
    val model = project.getService(Model::class.java)
    // Read lazily, not captured - the username can arrive after this panel is built; until then
    // every PR reads as needing attention.
    val currentUser = project.getService(CurrentUser::class.java)
    return object : Panel(attentionOf = { pr -> pr.attentionFor(currentUser.name) }) {
        override fun createPRComponent(pr: PR): PRComponent {
            // Same attentionFor() the "Show already approved" collapse already relies on - one
            // source of truth for "have I approved this", so the button and the collapse can
            // never disagree. docs: Known incidents
            val alreadyApproved = pr.attentionFor(currentUser.name) == ReviewAttention.APPROVED
            return PRComponent(pr, model, alreadyApproved)
        }

        override fun reviewedUpdated(diff: Diff) {
            dataUpdated(diff)
        }
    }
}

fun createOwnPanel(project: Project): Panel {
    val model = project.getService(Model::class.java)
    return object : Panel() {
        override fun createPRComponent(pr: PR): PRComponent {
            return OwnPRComponent(pr, model)
        }

        override fun ownUpdated(diff: Diff) {
            dataUpdated(diff)
        }
    }
}

fun wrapIntoJBScroll(panel: JPanel): JScrollPane {
    val scroll = JBScrollPane(panel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
    scroll.verticalScrollBar.unitIncrement = 14
    return scroll
}
