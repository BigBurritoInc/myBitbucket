package ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

// Not added to any menu or toolbar in plugin.xml — reachable only via Find Action
// (Cmd/Ctrl+Shift+A), search "myBitbucket" or "demo". See CLAUDE.md "Demo mode".
class DemoModeAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
        val enabled = e.project?.getService(DemoMode::class.java)?.enabled ?: false
        e.presentation.text = if (enabled) "myBitbucket: Turn Off Demo Pull Requests"
        else "myBitbucket: Show Demo Pull Requests"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val demoMode = e.project?.getService(DemoMode::class.java) ?: return
        if (demoMode.enabled) demoMode.disable() else demoMode.enable()
    }
}
