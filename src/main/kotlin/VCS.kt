import com.intellij.openapi.project.Project

interface VCS {
    fun checkoutBranch(project: Project, branch: String, listener: Runnable)
    fun currentBranch(project: Project): String
    fun updateProject()
}
