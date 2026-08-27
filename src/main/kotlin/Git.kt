import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.playback.commands.ActionCommand
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import git4idea.GitUtil
import git4idea.GitVcs
import git4idea.actions.GitRepositoryAction
import git4idea.branch.GitBranchUtil
import git4idea.branch.GitBrancher
import git4idea.fetch.GitFetchSupport
import git4idea.repo.GitRepository
import org.jetbrains.annotations.Nls
import util.LOG


//todo: get rid of null-checks
object Git: VCS {
    private const val updateActionId = "Vcs.UpdateProject"

    // Every lookup below takes `project` explicitly instead of guessing it (ambient DataManager
    // focus, ProjectManager.openProjects, etc.) — with more than one project window open, an
    // ambient guess can resolve to the wrong one. See CLAUDE.md "Per-project state".
    override fun checkoutBranch(project: Project, branch: String, listener: Runnable) {
        LOG.debug("Checking out $branch")
        val currentRepository = currentRepository(project)
        if (currentRepository != null) {
            val branchController = GitBrancher.getInstance(project)
            val branchExists = currentRepository.branches.findBranchByName(branch) != null
            val repos = listOf(currentRepository)
            if (branchExists) {
                branchController.checkout(branch, false, repos) {
                    listener.run()
                    updateProject()
                }
            } else {
                AsyncFetchAndCheckout(project, "MyBitbucket: Fetching", GitRepositoryAction.getGitRoots(
                        project, GitVcs.getInstance(project))!!, currentRepository, branch, listener)
                        .queue()
            }

        } else {
            LOG.warn("repo is null for project ${project.name}")
        }
    }

    override fun updateProject() {
        val updateAction = ActionManager.getInstance().getAction(updateActionId)
        if (updateAction != null) {
            ActionManager.getInstance().tryToExecute(
                    updateAction, ActionCommand.getInputEvent(updateActionId), null,
                    ActionPlaces.UNKNOWN, false)
        } else {
            LOG.warn("Cannot find action by id: $updateActionId")
        }
    }

    override fun currentBranch(project: Project): String {
        val repository = currentRepository(project)
        if (repository != null)
            return GitBranchUtil.getDisplayableBranchText(repository)
        return ""
    }

    fun currentRepository(project: Project): GitRepository? {
        if (gitVcs(project) != null)
            return GitBranchUtil.getCurrentRepository(project)
        return null
    }

    fun gitVcs(project: Project): GitVcs? {
        val baseDir = project.guessProjectDir()
        if (baseDir != null) {
            val vcs = VcsUtil.getVcsFor(project, baseDir)
            //Initial problem: git4idea.GitVcs cannot be cast to git4idea.GitVcs
            //Lessons learned: do not add runtime dependencies for a module if it is a plugin.
            //Use plugin.xml to describe them
            if (vcs is GitVcs)
                return vcs
        }
        return null
    }
}

internal class AsyncFetchAndCheckout(private val project: Project, @Nls title: String, var gitRoots: List<VirtualFile>,
                                     var repo: GitRepository, var branch: String, var listener: Runnable) :
        Task.Backgroundable(project, title) {

    override fun run(indicator: ProgressIndicator) {
        val repositoryManager = GitUtil.getRepositoryManager(project)
        GitFetchSupport.fetchSupport(project)
                .fetchAllRemotes(GitUtil.getRepositoriesFromRoots(repositoryManager, gitRoots))
                .showNotification()

        val branchController = GitBrancher.getInstance(project)
        branchController.checkout(branch, false, listOf(repo)) { listener.run() }
    }
}
