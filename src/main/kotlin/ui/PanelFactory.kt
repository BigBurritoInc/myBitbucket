package ui

import bitbucket.CurrentUser
import bitbucket.data.PR
import bitbucket.isApprovedBy
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

// Model is a per-project service (see Model.kt) — fetched once here and closed over, so every
// PRComponent this panel creates acts on the right project's data, never an ambient guess.
fun createReviewPanel(project: Project): Panel {
    val model = project.getService(Model::class.java)
    // Read lazily, not captured: the username arrives with the first poll's response, which can be
    // after this panel is built. Until then isApprovedBy() is false and nothing is hidden.
    val currentUser = project.getService(CurrentUser::class.java)
    return object : Panel(isHidden = { pr -> pr.isApprovedBy(currentUser.name) }) {
        override fun createPRComponent(pr: PR): PRComponent {
            return PRComponent(pr, model)
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
