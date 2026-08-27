import bitbucket.BitbucketClient
import bitbucket.BitbucketClientFactory
import bitbucket.ClientListener
import bitbucket.CurrentUser
import bitbucket.MergeStatusCache
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import http.HttpResponseHandler
import ui.Model
import util.LOG
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

// One instance per open project (light service) — each project polls its own configured
// Bitbucket repo independently, instead of one global poll loop shared by every open project.
@Service(Service.Level.PROJECT)
class UpdateTaskHolder(private val project: Project) {
    companion object {
        // The poll paces itself between these two: every cycle that finds nothing new doubles the
        // wait, anything that changes drops it straight back to the floor. A repository nobody is
        // touching settles at one request every two minutes instead of 240 an hour.
        const val MIN_DELAY_SECONDS = 15L
        const val MAX_DELAY_SECONDS = 120L
    }

    private val lock = Object()
    var task: CancellableTask = DummyTask()

    fun reschedule() {
        createAndRun { task.createToReschedule(it) }
    }

    fun scheduleNew() {
        createAndRun { task.createNew(it) }
    }

    fun stop() {
        synchronized(lock) {
            task.cancel()
            task = DummyTask()
        }
    }

    private fun createAndRun(factory: (BitbucketClient) -> CancellableTask) {
        synchronized(lock) {
            //this lock is needed to make task initialization atomic
            //if you want to cancel this task from another thread, you will have to wait until this block completes
            task.cancel()
            // A rescheduled poll may be running against freshly entered credentials, so the
            // identity learned from the old ones must not carry over.
            project.getService(CurrentUser::class.java).clear()
            val client = BitbucketClientFactory.createClient(project, NotifyingClientListener())
            task = factory.invoke(client)
            // One-shot, not scheduleWithFixedDelay: UpdateTask re-arms itself with a delay that
            // depends on whether the last cycle found anything.
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
                    // Not the same as "no pull requests": leave the lists alone rather than empty
                    // them and then report every PR as new again on the next successful cycle.
                    LOG.warn("UpdateTask: skipping this cycle, pull requests could not be fetched")
                    recordCycle(changed = false)
                    return
                }
                val reviewingChanged = model.updateReviewingPRs(prs.reviewing)
                // own and reviewing are filtered from the same list, so a self-reviewed PR is the
                // same object in both and gets its merge status set once. That's intentional —
                // both tabs then agree, and PR.equals() excludes mergeStatus anyway.
                mergeStatusCache.applyTo(prs.own) { client.retrieveMergeStatus(it) }
                val ownChanged = model.updateOwnPRs(prs.own)
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

        override fun setFuture(future: ScheduledFuture<*>) {
            this.taskFuture = future
        }

        override fun cancel() {
            synchronized(lock) {
                // Set before cancelling the future: run()'s finally block re-arms unless this is set,
                // and it may already be executing on another thread.
                cancelled = true
                taskFuture?.cancel(true)
            }
        }
    }

    //This class does nothing
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
        // These two are configuration problems, not transient failures — they'd otherwise repeat
        // every 15 seconds until the user fixes Settings, which is when the poll is rescheduled and
        // this listener replaced anyway.
        private val notFoundReported = AtomicBoolean(false)
        private val unknownUserReported = AtomicBoolean(false)
        private val model get() = project.getService(Model::class.java)

        override fun invalidCredentials() {
            model.showNotification("Invalid BitBucket credentials! \n" +
                    "Or it could be required to enter captcha in the web-interface.", NotificationType.WARNING)

        }

        override fun actionForbidden() {
            model.showNotification("Action you are trying to perform is forbidden by Bitbucket",
                    NotificationType.WARNING)
        }

        override fun requestFailed(e: Exception) {
            LOG.error("Request failed", e)
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
            // Not routed through requestFailed(): that logs at ERROR, which raises the IDE's
            // "internal error" balloon, and counts towards the budget that cancels the poll.
            LOG.warn(message)
            if (notFoundReported.compareAndSet(false, true)) {
                model.showNotification(message, NotificationType.WARNING)
            }
        }

        override fun currentUserUnknown() {
            if (unknownUserReported.compareAndSet(false, true)) {
                model.showNotification("myBitbucket could not determine which Bitbucket user your " +
                        "access token belongs to, so pull requests can't be listed. " +
                        "Check the token in myBitbucket settings.", NotificationType.WARNING)
            }
        }
    }
}
