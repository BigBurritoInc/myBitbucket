package ui

import Git
import VCS
import bitbucket.BitbucketClientFactory
import domain.InboxEvent
import domain.PR
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import util.LOG
import util.doInAppExecutor
import util.invokeLater
import java.util.function.Consumer

// docs: Per-project state
@Service(Service.Level.PROJECT)
class Model(private val project: Project) : PRActions {
    private val vcs: VCS = Git
    private var own: PRState = PRState()
    private var reviewing: PRState = PRState()
    private val listeners: MutableList<Listener> = ArrayList()

    // The group is now declared in plugin.xml (<notificationGroup id="MyBitbucket group" .../>);
    // constructing it in code is deprecated in favor of that registration.
    private val notificationGroup
        get() = NotificationGroupManager.getInstance().getNotificationGroup("MyBitbucket group")

    /** @return whether anything actually changed - UpdateTask paces its next poll on this. */
    fun updateOwnPRs(prs: List<PR>): Boolean {
        var changed: Boolean
        synchronized(this) {
            LOG.debug("Model.updateOwnPRs: ${prs.size} PR(s)")
            val diff = own.createDiff(prs)
            changed = diff.hasAnyUpdates()
            if (changed) {
                own = own.createNew(prs)
                invokeLater { ownUpdated(diff); }
            }
            if (diff.mergeStatusChanged.isNotEmpty()) changed = true
            notifyMergeStatusChanged(diff)
        }
        branchChanged()
        return changed
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

    /** @return whether anything actually changed - UpdateTask paces its next poll on this. */
    fun updateReviewingPRs(prs: List<PR>): Boolean {
        var changed: Boolean
        synchronized(this) {
            LOG.debug("Model.updateReviewingPRs: ${prs.size} PR(s)")
            val diff = reviewing.createDiff(prs)
            changed = diff.hasAnyUpdates()
            if (changed) {
                notifyNewPR(diff)
                reviewing = reviewing.createNew(prs)
                invokeLater { reviewingUpdated(diff) }
            }
        }
        branchChanged()
        return changed
    }

    private fun notifyNewPR(diff: Diff) {
        invokeLater {
            if (diff.added.isNotEmpty()) {
                val message = if (diff.added.size == 1) {
                    val pr = diff.added.values.iterator().next()
                    "New Pull Request is available: \n ${pr.title} \n By: <b>${pr.author.displayName}</b>"
                } else {
                    "${diff.added.size} pull requests are available"
                }
                showNotification(message)
            }
        }
    }

    private fun reviewingUpdated(diff: Diff) {
        // Counts every PR under review, including already-approved ones - "how many am I on",
        // not "how many are left".
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

    override fun checkout(pr: PR) {
        vcs.checkoutBranch(project, pr.fromBranch, Runnable { branchChanged() })
    }

    override fun approve(pr: PR, callback: Consumer<Boolean>) {
        doInAppExecutor {
            try {
                BitbucketClientFactory.createClient(project).approve(pr)
                showNotification("PR ${pr.title} is approved")
                // Poll immediately rather than waiting out the backoff, so the user sees the PR drop out now.
                project.getService(UpdateTaskHolder::class.java).reschedule()
                invokeLater { callback.accept(true) }
            } catch (e: Exception) {
                LOG.warn("Failed to approve PR ${pr.id}", e)
                // Built without a listener, so nothing else would tell the user. docs: Known incidents
                showNotification("Could not approve the pull request: ${pr.title}", NotificationType.WARNING)
            }
        }
    }

    override fun unapprove(pr: PR, callback: Consumer<Boolean>) {
        doInAppExecutor {
            try {
                BitbucketClientFactory.createClient(project).unapprove(pr)
                showNotification("Approval removed from PR ${pr.title}")
                project.getService(UpdateTaskHolder::class.java).reschedule()
                invokeLater { callback.accept(true) }
            } catch (e: Exception) {
                LOG.warn("Failed to unapprove PR ${pr.id}", e)
                showNotification("Could not remove approval from the pull request: ${pr.title}", NotificationType.WARNING)
            }
        }
    }

    override fun merge(pr: PR, callback: Consumer<Boolean>) {
        doInAppExecutor {
            try {
                val newPRState = BitbucketClientFactory.createClient(project).merge(pr)
                if (newPRState.closed) {
                    own = own.update(listOf(newPRState))
                    showNotification("PR ${pr.title} is merged")
                    project.getService(UpdateTaskHolder::class.java).reschedule()
                    invokeLater { callback.accept(true) }
                }
            } catch (e: Exception) {
                LOG.warn("Failed to merge PR ${pr.id}", e)
                showNotification("Could not merge the pull request: ${pr.title}", NotificationType.WARNING)
            }
        }
    }

    fun showNotification(message: String, type: NotificationType = NotificationType.INFORMATION) {
        invokeLater {
            val notification = notificationGroup.createNotification(message, type)
            Notifications.Bus.notify(notification, project)
        }
    }

    // Public: also called by MainWindow's GitRepositoryChangeListener, so an outside branch
    // switch re-highlights immediately instead of waiting for the next poll.
    fun branchChanged() {
        invokeLater {
            listeners.forEach { it.currentBranchChanged(currentBranch()) }
        }
    }

    private fun currentBranch(): String {
        return vcs.currentBranch(project)
    }

    /** Only balloons if [Settings.inboxNotificationsEnabled] - off by default. docs: Inbox */
    fun publishInboxEvents(events: List<InboxEvent>, notificationsEnabled: Boolean, unreadCount: Int) {
        if (events.isEmpty()) return
        invokeLater {
            listeners.forEach {
                it.inboxUpdated(events)
                it.inboxUnreadCountChanged(unreadCount)
            }
        }
        if (notificationsEnabled) {
            invokeLater {
                val message = if (events.size == 1) {
                    val event = events.first()
                    "${event.authorDisplayName}: ${event.prTitle}"
                } else {
                    "${events.size} new Inbox events"
                }
                showNotification(message)
            }
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }
}
