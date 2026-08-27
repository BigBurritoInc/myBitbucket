package bitbucket

import bitbucket.data.Approve
import bitbucket.data.PR
import bitbucket.data.PagedResponse
import bitbucket.data.SimpleUser
import bitbucket.data.merge.MergeStatus
import bitbucket.data.merge.Veto
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectReader
import com.fasterxml.jackson.databind.ObjectWriter
import http.HttpResponseHandler
import http.RequestFactory
import http.UrlBuilder
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpUriRequest
import org.apache.http.entity.ByteArrayEntity
import org.apache.http.util.EntityUtils
import ui.Settings
import util.LOG
import java.net.URL

class BitbucketClient(
        private val httpClient: HttpClient,
        private val httpRequestFactory: RequestFactory,
        private val settings: Settings,
        private val currentUser: CurrentUser,
        objReader: ObjectReader,
        private val objWriter: ObjectWriter,
        private val listener: ClientListener
    ) {
    companion object {
        // Asking for a size is what makes Bitbucket add `avatarUrl` to every User it returns —
        // see User.avatarHref(). 64 so the icon stays crisp when scaled down on a HiDPI display.
        private const val AVATAR_SIZE = 64
        // Bitbucket's own default is 25. A repository rarely has more open PRs than this, so one
        // page is usually the whole poll.
        private const val PAGE_SIZE = 100
    }

    private val mergeStatusResponseHandler = HttpResponseHandler(
            objReader, object : TypeReference<MergeStatus>() {}, listener)
    private val pagedResponseHandler = HttpResponseHandler(
            objReader, object : TypeReference<PagedResponse<PR>>() {}, listener)
    private val pullRequestResponseHandler = HttpResponseHandler(
            objReader, object : TypeReference<PR>() {}, listener)

    // One probe per client instance is enough: a server that strips X-AUSERNAME won't start
    // sending it. The client is recreated whenever the poll is rescheduled.
    @Volatile
    private var probedForCurrentUser = false

    /**
     * Every open pull request of the configured repository, split into the ones the current user
     * authored and the ones they review.
     *
     * Returns `null` when the pull requests could not be fetched at all — callers must leave their
     * lists untouched in that case. An empty [RepositoryPRs] means the repository genuinely has no
     * open pull requests and the UI should clear. Conflating the two is what used to make a
     * momentary network failure look like every pull request being closed and then reopened.
     */
    fun openPRs(): RepositoryPRs? {
        return try {
            val prs = fetchOpenPRs()
            val user = currentUserName()
            if (user == null) {
                LOG.warn("Cannot tell which Bitbucket user the credentials belong to; skipping this update")
                listener.currentUserUnknown()
                return null
            }
            val partitioned = partitionPRs(prs, user)
            LOG.debug("Fetched ${prs.size} open PR(s) in ${settings.project}/${settings.slug}: " +
                    "${partitioned.own.size} own, ${partitioned.reviewing.size} reviewing (user=$user)")
            partitioned
        } catch (e: HttpResponseHandler.NotFoundException) {
            listener.repositoryNotFound("Repository ${settings.project}/${settings.slug} was not found " +
                    "on ${settings.url} — check the repository URL in myBitbucket settings.")
            null
        } catch (e: HttpResponseHandler.UnauthorizedException) {
            // Deliberately not swallowed: UpdateTask stops polling on this rather than retrying
            // with credentials the server has already rejected.
            throw e
        } catch (e: Exception) {
            listener.requestFailed(e)
            null
        }
    }

    /**
     * One page of the repository's open pull requests, then the rest of them.
     *
     * [limit] has to travel through the paging replay: leave it out and only the first page uses
     * PAGE_SIZE while every later page silently falls back to Bitbucket's default of 25.
     */
    private fun fetchOpenPRs(start: Int = 0, limit: Int = PAGE_SIZE): List<PR> {
        val url = pullRequestsUrl(settings, start, limit, AVATAR_SIZE)
        LOG.debug("Requesting open PRs: $url")
        val request = httpRequestFactory.createGet(url)
        return replayPageRequest(request) { fetchOpenPRs(it, limit) }
    }

    /**
     * The username the configured credentials belong to. Normally already known — the pull request
     * request itself carries `X-AUSERNAME`. See CLAUDE.md "Current user".
     */
    private fun currentUserName(): String? =
            currentUser.name
                    ?: probeCurrentUser()
                    ?: settings.login.trim().ifEmpty { null }

    /**
     * Asks the server for something cheap and unauthenticated-friendly purely to read its
     * `X-AUSERNAME` header; the body is discarded.
     */
    private fun probeCurrentUser(): String? {
        if (probedForCurrentUser) return null
        probedForCurrentUser = true
        return try {
            val url = UrlBuilder.fromUrl(URL(settings.url))
                    .pathSegments("rest", "api", "1.0", "application-properties")
                    .toUrlString()
            LOG.debug("Probing for the current Bitbucket user: $url")
            val response = httpClient.execute(httpRequestFactory.createGet(url))
            currentUser.captureFrom(response)
            EntityUtils.consumeQuietly(response.entity)
            currentUser.name
        } catch (e: Exception) {
            LOG.debug("Current user probe failed", e)
            null
        }
    }

    // /rest/api/1.0/projects/{projectKey}/repos/{repositorySlug}/pull-requests/{pullRequestId}/participants/{userSlug}
    fun approve(pr: PR) {
        val user = currentUserName()
        if (user == null) {
            listener.currentUserUnknown()
            throw IllegalStateException("Cannot approve: the current Bitbucket user is unknown")
        }
        try {
            val urlBuilder = urlBuilder().pathSegments(
                    "projects", settings.project, "repos", settings.slug, "pull-requests", pr.id.toString(), "participants", user)
            LOG.debug("Approving PR ${pr.id}: PUT ${urlBuilder.toUrlString()}")
            val request = httpRequestFactory.createPut(urlBuilder.toUrlString())
            val body = objWriter.writeValueAsBytes(Approve(SimpleUser(user)))
            val entity = ByteArrayEntity(body)
            request.entity = entity
            HttpResponseHandler.handle(httpClient.execute(request))
        } catch (e: Exception) {
            listener.requestFailed(e)
            throw e
        }
    }

    fun merge(pr: PR): PR {
        return try {
            val urlBuilder = mergeUrl(pr)
            LOG.debug("Merging PR ${pr.id}: POST ${urlBuilder.toUrlString()}")
            val request = httpRequestFactory.createPost(urlBuilder.toUrlString())
            sendRequest(request, pullRequestResponseHandler)
        } catch (e: Exception) {
            listener.requestFailed(e)
            pr
        }
    }

    fun retrieveMergeStatus(pr: PR): MergeStatus {
        return try {
            val urlBuilder = mergeUrl(pr)
            val request = httpRequestFactory.createGet(urlBuilder.toUrlString())
            val mergeStatus = sendRequest(request, mergeStatusResponseHandler)
            mergeStatus.unknown = false
            mergeStatus
        } catch (e: Exception) {
            listener.requestFailed(e)
            MergeStatus(false, false, listOf(Veto("Request Error", "")))
        }
    }

    private fun mergeUrl(pr: PR): UrlBuilder {
        return urlBuilder().pathSegments(
                "projects", settings.project, "repos", settings.slug, "pull-requests", pr.id.toString(), "merge")
                .queryParam("version", pr.version.toString())
    }

    private fun urlBuilder() = UrlBuilder.fromUrl(URL(settings.url)).pathSegments("rest", "api", "1.0")

    private fun <T> sendRequest(request : HttpUriRequest, responseHandler: HttpResponseHandler<T>): T {
        val response = httpClient.execute(request)
        // Reading a header doesn't consume the entity, and doing it before handle() means even a
        // rejected request still tells us who we are.
        currentUser.captureFrom(response)
        return responseHandler.handle(response)
    }

    private fun replayPageRequest(request: HttpUriRequest, replay: (Int) -> List<PR>): List<PR> {
        val pagedResponse = sendRequest(request, pagedResponseHandler)
        val prs = ArrayList(pagedResponse.values)
        if (!pagedResponse.isLastPage)
            prs.addAll(replay.invoke(pagedResponse.nextPageStart))
        return prs
    }
}
