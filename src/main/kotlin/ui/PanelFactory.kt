package ui

import bitbucket.data.PR
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

// The project this plugin's toolwindow was built for — see getStorerService() in Configurable.kt
// for why callers should prefer this over any ambient/ProjectManager-guessed Project.
lateinit var currentProject: Project

// ::currentProject.isInitialized is only usable here — Kotlin only allows that check on a
// lateinit var from the same file it's declared in (or the same class). getStorerService() in
// Configurable.kt needs the check from another file, so it goes through this instead.
fun currentProjectOrNull(): Project? = if (::currentProject.isInitialized) currentProject else null

fun createReviewPanel(): Panel {
    return object : Panel() {
        override fun createPRComponent(pr: PR): PRComponent {
            return PRComponent(pr)
        }

        override fun reviewedUpdated(diff: Diff) {
            dataUpdated(diff)
        }
    }
}

fun createOwnPanel(): Panel {
    return object : Panel() {
        override fun createPRComponent(pr: PR): PRComponent {
            return OwnPRComponent(pr)
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
