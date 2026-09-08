import bitbucket.BitbucketClient
import bitbucket.BitbucketClientFactory
import bitbucket.ClientListener
import bitbucket.CurrentUser
import bitbucket.InboxTracker
import bitbucket.MergeStatusCache
import bitbucket.RepositoryPRs
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import credentials.CloudOAuth
import credentials.CredentialStore
import http.HttpResponseHandler
import ui.InboxStore
import ui.Model
import ui.Storer
import util.LOG
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

// docs: Per-project state
@Service(Service.Level.PROJECT)
class UpdateTaskHolder(private val project: Project) {
    companion object {
        const val MIN_DELAY_SECONDS = 15L
        const val MAX_DELAY_SECONDS = 120L
        // Inbox tab is implemented but disabled - its UX needs a rework before it ships to users.
        // Flip this back on once that's done; don't delete the feature code. docs: Inbox
        const val INBOX_FEATURE_ENABLED = false
    }

    private val lock = Any()
    var task: CancellableTask = DummyTask()

    // Dispatches off-thread (blocking PasswordSafe read) - makes stop() racy against a pending
    // schedule. docs: Basic Auth
    fun reschedule() {
        AppExecutorUtil.getAppScheduledExecutorService().execute {
            createAndRun { task.createToReschedule(it) }
        }
    }

    fun scheduleNew() {
        AppExecutorUtil.getAppScheduledExecutorService().execute {
            createAndRun { task.createNew(it) }
        }
    }

    fun stop() {
        synchronized(lock) {
            task.cancel()
            task = DummyTask()
        }
    }

    private fun createAndRun(factory: (BitbucketClient) -> CancellableTask) {
        synchronized(lock) {
            // Makes task initialization atomic; cancelling from another thread waits for this block.
            task.cancel()
            // Clears CurrentUser so a newly configured credential can't inherit the old identity.
            project.getService(CurrentUser::class.java).clear()
            val client = BitbucketClientFactory.createClient(project, NotifyingClientListener())
            task = factory.invoke(client)
            // One-shot, not scheduleWithFixedDelay - UpdateTask re-arms itself with a delay based
            // on the last cycle.
            task.setFuture(AppExecutorUtil.getAppScheduledExecutorService().schedule(task, 0, TimeUnit.SECONDS))
        }
    }

    // inner, not a plain nested class: needs this project's Model and UpdateTaskHolder's own lock.
    inner class UpdateTask(private val client: BitbucketClient) : CancellableTask {
        override fun createNew(client: BitbucketClient): CancellableTask = UpdateTask(client)
        override fun createToReschedule(client: BitbucketClient): CancellableTask = UpdateTask(client)

        @Volatile
        var taskFuture: ScheduledFuture<*>? = null

        @Volatile
        private var cancelled = false

        @Volatile
        private var delaySeconds = MIN_DELAY_SECONDS

        private val mergeStatusCache = MergeStatusCache()
        private val inboxTracker = InboxTracker()

        override fun run() {
            try {
                pollOnce()
            } finally {
                scheduleNextRun()
            }
        }

        private fun scheduleNextRun() {
            synchronized(lock) {
                if (cancelled) return
                LOG.debug("Next pull request poll in ${delaySeconds}s")
                taskFuture = AppExecutorUtil.getAppScheduledExecutorService()
                        .schedule(this, delaySeconds, TimeUnit.SECONDS)
            }
        }

        /** Back off while nothing is happening; snap back to the floor the moment something does. */
        private fun recordCycle(changed: Boolean) {
            delaySeconds = if (changed) MIN_DELAY_SECONDS
            else Math.min(delaySeconds * 2, MAX_DELAY_SECONDS)
        }

        private fun pollOnce() {
            val model = project.getService(Model::class.java)
            try {
                LOG.debug("Running UpdateTask...")
                val prs = client.openPRs()
                if (prs == null) {
                    // Not "no pull requests" - leave lists alone rather than report every PR as
                    // new next cycle. docs: Known incidents
                    LOG.warn("UpdateTask: skipping this cycle, pull requests could not be fetched")
                    recordCycle(changed = false)
                    return
                }
                val reviewingChanged = model.updateReviewingPRs(prs.reviewing)
                // own/reviewing share the same object for a self-reviewed PR, so merge status is
                // set once; PR.equals() excludes it anyway.
                mergeStatusCache.applyTo(prs.own) { client.retrieveMergeStatus(it) }
                val ownChanged = model.updateOwnPRs(prs.own)
                if (INBOX_FEATURE_ENABLED) checkInbox(client, prs)
                recordCycle(reviewingChanged || ownChanged)
            } catch (e: HttpResponseHandler.UnauthorizedException) {
                LOG.warn("UpdateTask stopped: unauthorized")
                cancel()
            } catch (e: IOException) {
                LOG.warn("UpdateTask: connection error", e)
                recordCycle(changed = false)
                model.showNotification("Error while trying to connect to a remote host: ${e.message} \n" +
                        "Either myBitbucket settings are invalid or the host is unreachable",
                        NotificationType.WARNING)
            } catch (e: Exception) {
                LOG.warn("UpdateTask failed", e)
                recordCycle(changed = false)
            }
        }

        // Defensive: openPRs() already returns null and skips the cycle when there's no user
        // identifier, so this is never actually reached without one.
        private fun checkInbox(client: BitbucketClient, prs: RepositoryPRs) {
            val currentUser = client.currentUserIdentifier() ?: return
            val inboxStore = project.getService(InboxStore::class.java)
            val events = inboxTracker.detect(client, prs.own, prs.reviewing, currentUser, inboxStore)
            if (events.isEmpty()) return
            inboxStore.recordEvents(events)
            val settings = project.getService(Storer::class.java)!!.settings
            project.getService(Model::class.java)
                    .publishInboxEvents(events, settings.inboxNotificationsEnabled, inboxStore.unreadCount())
        }

        override fun setFuture(future: ScheduledFuture<*>) {
            this.taskFuture = future
        }

        override fun cancel() {
            synchronized(lock) {
                // Set before cancelling the future - run() may already be executing on another
                // thread and would otherwise re-arm itself.
                cancelled = true
                taskFuture?.cancel(true)
            }
        }
    }

    inner class DummyTask: CancellableTask {
        override fun createNew(client: BitbucketClient): CancellableTask = UpdateTask(client)
        override fun createToReschedule(client: BitbucketClient): CancellableTask = DummyTask()
        override fun setFuture(future: ScheduledFuture<*>) {}
        override fun cancel() {}
        override fun run() {}
    }

    interface CancellableTask: Runnable {
        fun setFuture(future: ScheduledFuture<*>)
        fun createNew(client: BitbucketClient): CancellableTask
        fun createToReschedule(client: BitbucketClient): CancellableTask
        fun cancel()
    }

    // inner: cancels the enclosing UpdateTaskHolder's own task after too many consecutive errors.
    inner class NotifyingClientListener: ClientListener {
        private val errorCounter: AtomicInteger = AtomicInteger(0)
        // Configuration problems, not transient failures - they'd otherwise repeat every 15s
        // until Settings is fixed, which replaces this listener anyway.
        private val notFoundReported = AtomicBoolean(false)
        private val unknownUserReported = AtomicBoolean(false)
        private val model get() = project.getService(Model::class.java)

        override fun invalidCredentials() {
            // Clears the invalid secret so reconfigure doesn't silently reuse it; always off the
            // EDT already. docs: Basic Auth
            val settings = project.getService(Storer::class.java)!!.settings
            if (settings.cloud) {
                CloudOAuth.clear()
            } else {
                CredentialStore.hostAuthorityFor(settings.url)?.let { CredentialStore.clear(it) }
            }
            model.showNotification("Invalid Bitbucket credentials! Use \"Reconfigure Bitbucket repo…\" " +
                    "to sign in again. \n" +
                    "Or it could be required to enter captcha in the web-interface.", NotificationType.WARNING)

        }

        override fun actionForbidden() {
            model.showNotification("Action you are trying to perform is forbidden by Bitbucket",
                    NotificationType.WARNING)
        }

        override fun requestFailed(e: Exception) {
            // LOG.warn, not LOG.error: a connect timeout/DNS failure/handshake failure is an
            // expected, actionable network condition (wrong URL, host unreachable, VPN off), not
            // a plugin bug - LOG.error makes the IDE raise its own "internal error" report for it,
            // repeating every poll cycle until the host is reachable again. docs: Known incidents
            LOG.warn("Request failed", e)
            val message = when (e) {
                is UnknownHostException -> "BitBucket host can't be reached. Check url settings."
                is SSLHandshakeException -> "SSL handshake with BitBucket server failed. Details: " + e.message
                else -> "Request to BitBucket failed, it may be unreachable or the settings are incorrect." +
                        " Details: " + e.message
            }
            model.showNotification(message, NotificationType.ERROR)
            val errors = errorCounter.incrementAndGet()
            if (errors > 5) {
                task.cancel()
                LOG.warn("UpdateTask is cancelled due to the high request error rate")
            }
        }

        override fun repositoryNotFound(message: String) {
            // Not routed through requestFailed(): that logs at ERROR (raises the IDE's "internal
            // error" balloon) and counts towards the budget that cancels the poll.
            LOG.warn(message)
            if (notFoundReported.compareAndSet(false, true)) {
                model.showNotification(message, NotificationType.WARNING)
            }
        }

        override fun currentUserUnknown() {
            if (unknownUserReported.compareAndSet(false, true)) {
                model.showNotification("myBitbucket could not determine which Bitbucket user your " +
                        "credentials belong to, so pull requests can't be listed. " +
                        "Use \"Reconfigure Bitbucket repo…\" to check it.", NotificationType.WARNING)
            }
        }
    }
}
