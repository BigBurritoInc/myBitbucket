package ui

import Git
import VCS
import bitbucket.BitbucketClientFactory
import bitbucket.data.PR
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import util.LOG
import util.doInAppExecutor
import util.invokeLater
import java.util.function.Consumer

object Model {
    private val vcs: VCS = Git
    private var own: PRState = PRState()
    private var reviewing: PRState = PRState()
    private val listeners: MutableList<Listener> = ArrayList()

    // The group is now declared in plugin.xml (<notificationGroup id="MyBitbucket group" .../>);
    // constructing it in code is deprecated in favor of that registration.
    private val notificationGroup
        get() = NotificationGroupManager.getInstance().getNotificationGroup("MyBitbucket group")

    fun updateOwnPRs(prs: List<PR>) {
        synchronized(this) {
            LOG.debug("Model.updateOwnPRs: ${prs.size} PR(s)")
            val diff = own.createDiff(prs)
            if (diff.hasAnyUpdates()) {
                own = own.createNew(prs)
                invokeLater { ownUpdated(diff); }
            }
            notifyMergeStatusChanged(diff)
        }
        branchChanged()
    }

    private fun notifyMergeStatusChanged(diff: Diff) {
        if (diff.mergeStatusChanged.isNotEmpty()) {
            val availableForMerge = diff.mergeStatusChanged.filter { it.value.mergeStatus.canMerge }
            if (availableForMerge.size == 1) {
                val title = availableForMerge.values.first().title
                showNotification("Your pull request can be merged: $title")
            } else if (availableForMerge.size > 1) {
                showNotification("${availableForMerge.size} pull requests can be merged")
            }
            invokeLater { ownUpdated(Diff(emptyMap(), diff.mergeStatusChanged, emptyMap())) }
        }
    }

    fun updateReviewingPRs(prs: List<PR>) {
        synchronized(this) {
            LOG.debug("Model.updateReviewingPRs: ${prs.size} PR(s)")
            val diff = reviewing.createDiff(prs)
            if (diff.hasAnyUpdates()) {
                notifyNewPR(diff)
                reviewing = reviewing.createNew(prs)
                invokeLater { reviewingUpdated(diff) }
            }
        }
        branchChanged()
    }

    private fun notifyNewPR(diff: Diff) {
        invokeLater {
            if (diff.added.isNotEmpty()) {
                val message = if (diff.added.size == 1) {
                    val pr = diff.added.values.iterator().next()
                    "New Pull Request is available: \n ${pr.title} \n By: <b>${pr.author.user.displayName}</b>"
                } else {
                    "${diff.added.size} pull requests are available"
                }
                showNotification(message)
            }
        }
    }

    private fun reviewingUpdated(diff: Diff) {
        listeners.forEach{
            it.reviewedUpdated(diff);
            it.reviewedCountChanged(reviewing.size())
        }
    }

    private fun ownUpdated(diff: Diff) {
        listeners.forEach{
            it.ownUpdated(diff);
            it.ownCountChanged(own.size())
        }
    }

    fun checkout(pr: PR) {
        vcs.checkoutBranch(pr.fromBranch, Runnable { branchChanged() })
    }

    fun approve(pr: PR, callback: Consumer<Boolean>) {
        doInAppExecutor {
            try {
                BitbucketClientFactory.createClient().approve(pr)
                showNotification("PR ${pr.title} is approved")
                invokeLater { callback.accept(true) }
            } catch (e: Exception) {
                LOG.warn(e)
            }
        }
    }

    fun merge(pr: PR, callback: Consumer<Boolean>) {
        doInAppExecutor {
            try {
                val newPRState = BitbucketClientFactory.createClient().merge(pr)
                if (newPRState.closed) {
                    own = own.update(listOf(newPRState))
                    showNotification("PR ${pr.title} is merged")
                    invokeLater { callback.accept(true) }
                }
            } catch (e: Exception) {
                LOG.warn(e)
            }
        }
    }

    fun showNotification(message: String, type: NotificationType = NotificationType.INFORMATION) {
        invokeLater {
            val notification = notificationGroup.createNotification(message, type)
            Notifications.Bus.notify(notification, Git.currentProject())
        }
    }

    // Public: also called directly by MainWindow's GitRepositoryChangeListener, so a branch
    // switch made outside the plugin re-highlights immediately instead of waiting for the next
    // 15s poll.
    fun branchChanged() {
        invokeLater {
            listeners.forEach { it.currentBranchChanged(currentBranch()) }
        }
    }

    private fun currentBranch(): String {
        return vcs.currentBranch()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }
}