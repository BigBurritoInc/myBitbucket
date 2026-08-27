package ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

// One instance per open project (light service), like Model/UpdateTaskHolder — a project's demo
// PRs stay in that project's own window. See DemoModeAction and CLAUDE.md "Demo mode".
@Service(Service.Level.PROJECT)
class DemoMode(private val project: Project) {
    var enabled: Boolean = false
        private set

    fun enable() {
        enabled = true
        project.getService(UpdateTaskHolder::class.java).stop()
        val model = project.getService(Model::class.java)
        model.updateOwnPRs(emptyList())
        model.updateReviewingPRs(DemoData.samplePRs())
    }

    fun disable() {
        enabled = false
        project.getService(Model::class.java).updateReviewingPRs(emptyList())
        // Same guard MainWindow.runUpdateTaskLater() uses — don't restart polling into settings
        // that were never configured.
        if (project.getService(Storer::class.java)!!.settings.url.isNotBlank())
            project.getService(UpdateTaskHolder::class.java).scheduleNew()
    }
}
