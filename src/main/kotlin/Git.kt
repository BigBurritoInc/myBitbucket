import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
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
import git4idea.repo.GitRepository
import git4idea.update.GitFetcher
import org.jetbrains.annotations.Nls
import util.LOG


//todo: get rid of null-checks
object Git: VCS {
    private const val updateActionId = "Vcs.UpdateProject"

    override fun checkoutBranch(branch: String, listener: Runnable) {
        LOG.debug("Checking out $branch")
        val currentProject = currentProject()
        val currentRepository = currentRepository()
        if (currentProject != null && currentRepository != null) {
            val branchController = GitBrancher.getInstance(currentProject)
            val branchExists = currentRepository.branches.findBranchByName(branch) != null
            val repos = listOf(currentRepository)
            if (branchExists) {
                branchController.checkout(branch, false, repos) {
                    listener.run()
                    updateProject()
                }
            } else {
                AsyncFetchAndCheckout(currentProject, "MyBitbucket: Fetching", GitRepositoryAction.getGitRoots(
                        currentProject, GitVcs.getInstance(currentProject))!!, currentRepository, branch, listener)
                        .queue()
            }

        } else {
            LOG.warn("prj or repo is null $currentProject $currentRepository")
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

    override fun currentBranch(): String {
        val repository = currentRepository()
        if (repository != null)
            return GitBranchUtil.getDisplayableBranchText(repository)
        return ""
    }

    fun currentProject(): Project? {
        return CommonDataKeys.PROJECT.getData(DataManager.getInstance().dataContext)
    }

    fun currentRepository(): GitRepository? {
        val prj = currentProject()
        if (prj != null && gitVcs() != null)
            return GitBranchUtil.getCurrentRepository(prj)
        return null
    }

    fun gitVcs(): GitVcs? {
        val prj = currentProject()
        val baseDir = prj?.guessProjectDir()
        if (prj != null && baseDir != null) {
            val vcs = VcsUtil.getVcsFor(prj, baseDir)
            //Initial problem: git4idea.GitVcs cannot be cast to git4idea.GitVcs
            //Lessons learned: do not add runtime dependencies for a module if it is a plugin.
            //Use plugin.xml to describe them
            if (vcs is GitVcs)
                return vcs
        }
        return null
    }
}

// Constructor takes a non-null Project/GitRepository (both call sites already null-checked them)
// rather than relying on the base Task class's @Nullable `myProject` field, which is what was
// actually rejected below once the Kotlin compiler started enforcing that platform nullability
// annotation strictly.
internal class AsyncFetchAndCheckout(private val project: Project, @Nls title: String, var gitRoots: List<VirtualFile>,
                                     var repo: GitRepository, var branch: String, var listener: Runnable) :
        Task.Backgroundable(project, title) {

    override fun run(indicator: ProgressIndicator) {
        val repositoryManager = GitUtil.getRepositoryManager(project)
        GitFetcher(project, indicator, true).fetchRootsAndNotify(GitUtil.getRepositoriesFromRoots(repositoryManager,
                gitRoots), null, true)

        val branchController = GitBrancher.getInstance(project)
        branchController.checkout(branch, false, listOf(repo)) { listener.run() }
    }
}