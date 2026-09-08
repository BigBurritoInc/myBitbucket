package bitbucket.server

import bitbucket.BitbucketClient
import bitbucket.ClientListener
import bitbucket.CurrentUser
import bitbucket.RepositoryPRs
import bitbucket.partitionPRs
import bitbucket.pullRequestsUrl
import bitbucket.server.dto.ActivityDto
import bitbucket.server.dto.ParticipantStatusRequestDto
import bitbucket.server.dto.MergeStatusDto
import bitbucket.server.dto.PageDto
import bitbucket.server.dto.PullRequestDto
import bitbucket.server.dto.SimpleUserDto
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectReader
import com.fasterxml.jackson.databind.ObjectWriter
import domain.Comment
import domain.MergeStatus
import domain.PR
import domain.Veto
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

/** Server/Data Center implementation of [BitbucketClient] - the only class that sees both DTO and
 * domain types.
 * docs: Domain model vs wire format */
class BitbucketServerClient(
        private val httpClient: HttpClient,
        private val httpRequestFactory: RequestFactory,
        private val settings: Settings,
        private val currentUser: CurrentUser,
        objReader: ObjectReader,
        private val objWriter: ObjectWriter,
        private val listener: ClientListener
    ) : BitbucketClient {
    companion object {
        // Asking for a size makes Bitbucket add avatarUrl to every User it returns - see
        // UserDto.avatarHref().
        private const val AVATAR_SIZE = 64
        // Bitbucket's own default is 25; 100 makes one page cover most repos' whole poll.
        private const val PAGE_SIZE = 100
    }

    private val mergeStatusResponseHandler = HttpResponseHandler(
            objReader, object : TypeReference<MergeStatusDto>() {}, listener)
    private val pagedResponseHandler = HttpResponseHandler(
            objReader, object : TypeReference<PageDto<PullRequestDto>>() {}, listener)
    private val pullRequestResponseHandler = HttpResponseHandler(
            objReader, object : TypeReference<PullRequestDto>() {}, listener)
    private val activitiesPagedResponseHandler = HttpResponseHandler(
            objReader, object : TypeReference<PageDto<ActivityDto>>() {}, listener)

    // One probe per client instance - recreated each reschedule, so this doesn't need to reset itself.
    @Volatile
    private var probedForCurrentUser = false

    override fun openPRs(): RepositoryPRs? {
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
            LOG.warn(e)
            listener.repositoryNotFound("Repository ${settings.project}/${settings.slug} was not found " +
                    "on ${settings.url} - check the repository URL in myBitbucket settings.")
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

    /** Mapped to the domain here, at the edge - nothing above this sees Server's wire format. */
    private fun fetchOpenPRs(): List<PR> = fetchPage(0, PAGE_SIZE).map { it.toDomain() }

    /** One page, then the rest via paging replay. [limit] must travel through that replay - omit
     * it and every page after the first silently falls back to Bitbucket's default of 25 instead
     * of PAGE_SIZE. */
    private fun fetchPage(start: Int, limit: Int): List<PullRequestDto> {
        val url = pullRequestsUrl(settings, start, limit, AVATAR_SIZE)
        LOG.debug("Requesting open PRs: $url")
        return replayPageRequest(httpRequestFactory.createGet(url)) { fetchPage(it, limit) }
    }

    /** The username the credentials belong to - normally already known via `X-AUSERNAME` on the
     * pull request response.
     * docs: Current user */
    private fun currentUserName(): String? =
            currentUser.name
                    ?: probeCurrentUser()
                    ?: settings.login.trim().ifEmpty { null }

    /** Asks the server for something cheap purely to read its `X-AUSERNAME` header; the body is
     * discarded. */
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

    // Reuses openPRs()'s own resolution, no extra request. docs: Inbox
    override fun currentUserIdentifier(): String? = currentUserName()

    override fun approve(pr: PR) = setOwnParticipantStatus(pr, approved = true)

    /** Withdraws the current user's own approval. Same endpoint as [approve] - PUT .../participants/{userSlug}
     * is the documented way to change *either* direction of the current user's own status, and it
     * leaves the user as a participant/reviewer rather than removing them (unlike the deprecated
     * DELETE .../participants/{userSlug}, which drops the participant entirely).
     * docs: Known incidents */
    override fun unapprove(pr: PR) = setOwnParticipantStatus(pr, approved = false)

    private fun setOwnParticipantStatus(pr: PR, approved: Boolean) {
        val user = currentUserName()
        if (user == null) {
            listener.currentUserUnknown()
            throw IllegalStateException("Cannot change approval: the current Bitbucket user is unknown")
        }
        try {
            val urlBuilder = urlBuilder().pathSegments(
                    "projects", settings.project, "repos", settings.slug, "pull-requests", pr.id.toString(), "participants", user)
            val status = if (approved) "APPROVED" else "UNAPPROVED"
            LOG.debug("Setting PR ${pr.id} participant status to $status: PUT ${urlBuilder.toUrlString()}")
            val request = httpRequestFactory.createPut(urlBuilder.toUrlString())
            val body = objWriter.writeValueAsBytes(
                    ParticipantStatusRequestDto(SimpleUserDto(user), status, approved.toString()))
            val entity = ByteArrayEntity(body)
            request.entity = entity
            HttpResponseHandler.handle(httpClient.execute(request))
        } catch (e: Exception) {
            listener.requestFailed(e)
            throw e
        }
    }

    override fun merge(pr: PR): PR {
        return try {
            val urlBuilder = mergeUrl(pr)
            LOG.debug("Merging PR ${pr.id}: POST ${urlBuilder.toUrlString()}")
            val request = httpRequestFactory.createPost(urlBuilder.toUrlString())
            sendRequest(request, pullRequestResponseHandler).toDomain()
        } catch (e: Exception) {
            listener.requestFailed(e)
            pr
        }
    }

    override fun retrieveMergeStatus(pr: PR): MergeStatus {
        return try {
            val urlBuilder = mergeUrl(pr)
            val request = httpRequestFactory.createGet(urlBuilder.toUrlString())
            sendRequest(request, mergeStatusResponseHandler).toDomain()
        } catch (e: Exception) {
            listener.requestFailed(e)
            // known=true, not UNKNOWN - the request happened; MergeStatusCache treats this like
            // any answer until the PR changes.
            MergeStatus(canMerge = false, conflicted = false,
                    vetoes = listOf(Veto("Could not read the merge status", "")), known = true)
        }
    }

    private fun mergeUrl(pr: PR): UrlBuilder {
        return urlBuilder().pathSegments(
                "projects", settings.project, "repos", settings.slug, "pull-requests", pr.id.toString(), "merge")
                // Server's optimistic locking. The domain revision is exactly that version.
                .queryParam("version", pr.revision.toString())
    }

    private fun urlBuilder() = UrlBuilder.fromUrl(URL(settings.url)).pathSegments("rest", "api", "1.0")

    private fun <T> sendRequest(request : HttpUriRequest, responseHandler: HttpResponseHandler<T>): T {
        val response = httpClient.execute(request)
        // Doesn't consume the entity; capturing before handle() means even a rejected request
        // still tells us who we are.
        currentUser.captureFrom(response)
        return responseHandler.handle(response)
    }

    private fun replayPageRequest(request: HttpUriRequest,
                                  replay: (Int) -> List<PullRequestDto>): List<PullRequestDto> {
        val pagedResponse = sendRequest(request, pagedResponseHandler)
        val prs = ArrayList(pagedResponse.values)
        if (!pagedResponse.isLastPage)
            prs.addAll(replay.invoke(pagedResponse.nextPageStart))
        return prs
    }

    // Not the plain "comments" endpoint - that's file/line comment search on Server, not this.
    // docs: Inbox, Known incidents
    override fun fetchComments(pr: PR, sinceCommentId: Long?): List<Comment> {
        return try {
            fetchActivityPage(pr, 0)
                    .filter { it.action == "COMMENTED" && it.comment != null }
                    .flatMap { it.comment!!.toDomainTree() }
                    .filter { sinceCommentId == null || it.id > sinceCommentId }
        } catch (e: Exception) {
            listener.requestFailed(e)
            emptyList()
        }
    }

    private fun fetchActivityPage(pr: PR, start: Int): List<ActivityDto> {
        val url = urlBuilder().pathSegments(
                "projects", settings.project, "repos", settings.slug,
                "pull-requests", pr.id.toString(), "activities")
                .queryParam("start", start.toString())
                .toUrlString()
        val request = httpRequestFactory.createGet(url)
        val pagedResponse = sendRequest(request, activitiesPagedResponseHandler)
        val activities = ArrayList(pagedResponse.values)
        if (!pagedResponse.isLastPage)
            activities.addAll(fetchActivityPage(pr, pagedResponse.nextPageStart))
        return activities
    }
}
