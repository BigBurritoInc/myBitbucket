import bitbucket.BitbucketClient
import bitbucket.BitbucketClientFactory
import bitbucket.ClientListener
import com.intellij.notification.NotificationType
import com.intellij.util.concurrency.AppExecutorUtil
import http.HttpResponseHandler
import ui.Model
import util.LOG
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

object UpdateTaskHolder {
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
            val client = BitbucketClientFactory.createClient(NotifyingClientListener())
            task = factory.invoke(client)
            val future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                    task, 0, 15, TimeUnit.SECONDS)
            task.setFuture(future)
        }
    }

    class UpdateTask(private val client: BitbucketClient) : CancellableTask {
        override fun createToReschedule(client: BitbucketClient): CancellableTask {
            return UpdateTask(client)
        }

        @Volatile
        var taskFuture: ScheduledFuture<*>? = null

        override fun run() {
            try {
                LOG.debug("Running UpdateTask...")
                val reviewingPRs = client.reviewedPRs()
                Model.updateReviewingPRs(reviewingPRs)
                val ownPRs = client.ownPRs()
                LOG.debug("UpdateTask: fetched ${reviewingPRs.size} reviewing PR(s), ${ownPRs.size} own PR(s)")
                //If user has too many open pull request, we will not retrieve merge status for every of them
                ownPRs.subList(0, Math.min(ownPRs.size, 20)).forEach { it.mergeStatus = client.retrieveMergeStatus(it) }
                Model.updateOwnPRs(ownPRs)
            } catch (e: HttpResponseHandler.UnauthorizedException) {
                LOG.warn("UpdateTask stopped: unauthorized")
                cancel()
            } catch (e: IOException) {
                LOG.warn("UpdateTask: connection error", e)
                Model.showNotification("Error while trying to connect to a remote host: ${e.message} \n" +
                        "Either myBitbucket settings are invalid or the host is unreachable",
                        NotificationType.WARNING)
            } catch (e: Exception) {
                LOG.warn("UpdateTask failed", e)
            }
        }

        override fun setFuture(future: ScheduledFuture<*>) {
            this.taskFuture = future
        }

        override fun cancel() {
            synchronized(lock) {
                taskFuture?.cancel(true)
            }
        }
    }

    //This class does nothing
    class DummyTask: CancellableTask {
        override fun createToReschedule(client: BitbucketClient): CancellableTask {
            return DummyTask()
        }
        override fun setFuture(future: ScheduledFuture<*>) {}
        override fun cancel() {}
        override fun run() {}
    }

    interface CancellableTask: Runnable {
        fun setFuture(future: ScheduledFuture<*>)
        fun createNew(client: BitbucketClient): CancellableTask {
            return UpdateTask(client)
        }
        fun createToReschedule(client: BitbucketClient): CancellableTask
        fun cancel()
    }

    class NotifyingClientListener: ClientListener {
        private val errorCounter: AtomicInteger = AtomicInteger(0)

        override fun invalidCredentials() {
            Model.showNotification("Invalid BitBucket credentials! \n" +
                    "Or it could be required to enter captcha in the web-interface.", NotificationType.WARNING)

        }

        override fun actionForbidden() {
            Model.showNotification("Action you are trying to perform is forbidden by Bitbucket",
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
            Model.showNotification(message, NotificationType.ERROR)
            val errors = errorCounter.incrementAndGet()
            if (errors > 5) {
                task.cancel()
                LOG.warn("UpdateTask is cancelled due to the high request error rate")
            }
        }
    }
}
