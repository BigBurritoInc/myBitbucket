package ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

// One instance per open project, like Model/UpdateTaskHolder. docs: Demo mode
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
        // Same guard MainWindow.showConfiguredContent() is gated behind - don't restart polling
        // into a project that was never configured.
        if (project.getService(Storer::class.java)!!.settings.isConfigured())
            project.getService(UpdateTaskHolder::class.java).scheduleNew()
    }
}
