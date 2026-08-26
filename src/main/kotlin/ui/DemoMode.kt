package ui

// Hidden feature, deliberately not a Settings toggle — see DemoModeAction and CLAUDE.md "Demo mode".
object DemoMode {
    var enabled: Boolean = false
        private set

    fun enable() {
        enabled = true
        UpdateTaskHolder.stop()
        Model.updateOwnPRs(emptyList())
        Model.updateReviewingPRs(DemoData.samplePRs())
    }

    fun disable() {
        enabled = false
        Model.updateReviewingPRs(emptyList())
        // Same guard MainWindow.runUpdateTaskLater() uses — don't restart polling into settings
        // that were never configured.
        if (getStorerService().settings.url.isNotBlank()) UpdateTaskHolder.scheduleNew()
    }
}
