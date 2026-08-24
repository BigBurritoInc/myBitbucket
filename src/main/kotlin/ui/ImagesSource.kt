package ui

import com.intellij.util.concurrency.AppExecutorUtil
import util.LOG
import java.io.IOException
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.function.Supplier
import javax.imageio.ImageIO
import javax.swing.Icon

class ImagesSource: MediaSource<Icon> {

    //The key is String, not java.net.URL, because URL's hashCode() and equals() are blocking operations
    private val cachedImages: MutableMap<String /* url */, CompletableFuture<Icon>> = ConcurrentHashMap()
    private val executor: ExecutorService = AppExecutorUtil.getAppExecutorService()

    override fun retrieve(url: URL): CompletableFuture<Icon> {
        return cachedImages.computeIfAbsent(url.toString()) {
            CompletableFuture.supplyAsync<Icon>(Supplier {
                try {
                    ReviewerComponentFactory.createIconForPrParticipant(ImageIO.read(url))
                } catch (e: IOException) {
                    LOG.warn("Cannot read image by URL: $url")
                    ReviewerComponentFactory.defaultAvatarIcon
                }
            }, executor)
        }
    }
}