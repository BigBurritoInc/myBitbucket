package bitbucket.cloud

import bitbucket.BitbucketClient
import bitbucket.ClientListener
import bitbucket.RepositoryPRs
import bitbucket.partitionPRs
import bitbucket.cloud.dto.CommentDto
import bitbucket.cloud.dto.CurrentUserDto
import bitbucket.cloud.dto.PageDto
import bitbucket.cloud.dto.PullRequestDto
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectReader
import domain.Comment
import domain.MergeStatus
import domain.PR
import http.HttpResponseHandler
import http.RequestFactory
import http.UrlBuilder
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpUriRequest
import ui.Settings
import util.LOG
import java.net.URL

/** Bitbucket Cloud implementation of [BitbucketClient] — the only class that sees both Cloud DTO
 * and domain types. [settings.project]/[settings.slug] hold the workspace/repo slug.
 * docs: Domain model vs wire format, Adding Bitbucket Cloud support */
class BitbucketCloudClient(
        private val httpClient: HttpClient,
        private val httpRequestFactory: RequestFactory,
        private val settings: Settings,
        objReader: ObjectReader,
        private val listener: ClientListener
) : BitbucketClient {
    companion object {
        // Bitbucket's own default is 10; Cloud's hard ceiling per page is 50.
        private const val PAGE_SIZE = 50
        private const val API_ROOT = "https://api.bitbucket.org/2.0"
    }

    private val pagedResponseHandler = HttpResponseHandler(
            objReader, object : TypeReference<PageDto<PullRequestDto>>() {}, listener)
    private val pullRequestResponseHandler = HttpResponseHandler(
            objReader, object : TypeReference<PullRequestDto>() {}, listener)
    // Its own no-op listener, not the shared one: this is a background probe retried every poll
    // (see currentUserUuid()) — a 403/401 here already surfaces once, deliberately, via
    // openPRs()'s currentUserUnknown(); routing it through actionForbidden()/invalidCredentials()
    // too would balloon-spam the user every single poll cycle while it's failing.
    // docs: Current user, Known incidents
    private val currentUserResponseHandler = HttpResponseHandler(
            objReader, object : TypeReference<CurrentUserDto>() {}, object : ClientListener {})
    private val commentsPagedResponseHandler = HttpResponseHandler(
            objReader, object : TypeReference<PageDto<CommentDto>>() {}, listener)

    // Success is cached for the client's whole lifetime — a Cloud uuid never changes. Failure is
    // NOT cached: the old code latched a boolean on the first attempt, so one transient/config
    // failure (e.g. the OAuth token missing the "account" scope) silently disabled user resolution
    // — and therefore every poll ("Cannot tell which Bitbucket user...") — for as long as this
    // client lived, often for the whole IDE session. Retrying every call self-heals once whatever
    // was wrong is fixed, and costs at most one extra request per poll cycle (backed off up to
    // MAX_DELAY_SECONDS by UpdateTaskHolder while it keeps failing). docs: Current user, Known incidents
    @Volatile private var cachedUuid: String? = null

    override fun openPRs(): RepositoryPRs? {
        return try {
            val prs = fetchOpenPRs()
            val user = currentUserUuid()
            if (user == null) {
                LOG.warn("Cannot tell which Bitbucket user the credentials belong to; skipping this update")
                listener.currentUserUnknown()
                return null
            }
            val partitioned = partitionPRs(prs, user)
            LOG.debug("Fetched ${prs.size} open PR(s) in ${settings.project}/${settings.slug}: " +
                    "${partitioned.own.size} own, ${partitioned.reviewing.size} reviewing")
            partitioned
        } catch (e: HttpResponseHandler.NotFoundException) {
            LOG.warn(e)
            listener.repositoryNotFound("Repository ${settings.project}/${settings.slug} was not found " +
                    "on Bitbucket Cloud — check the workspace and repository in myBitbucket settings.")
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

    /** Mapped to the domain here, at the edge — nothing above this sees Cloud's wire format. */
    private fun fetchOpenPRs(): List<PR> = fetchAllPages().map { it.toDomain() }

    // Cloud's `next` is already a complete URL for the following page, so — unlike Server's
    // start/limit replay — no query-param reconstruction is needed between pages.
    private fun fetchAllPages(): List<PullRequestDto> {
        val result = ArrayList<PullRequestDto>()
        var url: String? = firstPageUrl()
        while (url != null) {
            val page = sendRequest(httpRequestFactory.createGet(url), pagedResponseHandler)
            result.addAll(page.values)
            url = page.next
        }
        return result
    }

    private fun firstPageUrl(): String =
            repoUrlBuilder().pathSegments("pullrequests")
                    .queryParam("q", "state=\"OPEN\"")
                    .queryParam("pagelen", PAGE_SIZE.toString())
                    .toUrlString()

    /** The current user's uuid, needed to tell "own" from "reviewing" — Cloud has no equivalent
     * of Server's `X-AUSERNAME` header, so this is one explicit `GET /2.0/user` call instead.
     * docs: Adding Bitbucket Cloud support */
    private fun currentUserUuid(): String? {
        cachedUuid?.let { return it }
        return try {
            val url = UrlBuilder.fromUrl(URL(API_ROOT)).pathSegments("user").toUrlString()
            val dto = sendRequest(httpRequestFactory.createGet(url), currentUserResponseHandler)
            cachedUuid = dto.uuid
            dto.uuid
        } catch (e: Exception) {
            // WARN, not debug: this is the actual reason every poll logs "Cannot tell which
            // Bitbucket user..." — HttpResponseHandler already logged the response body above for
            // a 401/403. docs: Known incidents
            LOG.warn("Current user probe (GET /2.0/user) failed", e)
            null
        }
    }

    // Reuses openPRs()'s own resolution (uuid cached after first call), no extra request. docs: Inbox
    override fun currentUserIdentifier(): String? = currentUserUuid()

    override fun approve(pr: PR) {
        try {
            val url = repoUrlBuilder().pathSegments(pr.id.toString(), "approve").toUrlString()
            HttpResponseHandler.handle(httpClient.execute(httpRequestFactory.createPost(url)))
        } catch (e: Exception) {
            listener.requestFailed(e)
            throw e
        }
    }

    override fun merge(pr: PR): PR {
        return try {
            val url = repoUrlBuilder().pathSegments(pr.id.toString(), "merge").toUrlString()
            sendRequest(httpRequestFactory.createPost(url), pullRequestResponseHandler).toDomain()
        } catch (e: Exception) {
            listener.requestFailed(e)
            pr
        }
    }

    override fun retrieveMergeStatus(pr: PR): MergeStatus = CLOUD_MERGE_STATUS

    // Order isn't documented — pages through fully and filters by id, correct either way. docs: Inbox
    override fun fetchComments(pr: PR, sinceCommentId: Long?): List<Comment> {
        return try {
            fetchAllCommentPages(pr).filter { sinceCommentId == null || it.id > sinceCommentId }
                    .map { it.toDomain() }
        } catch (e: Exception) {
            listener.requestFailed(e)
            emptyList()
        }
    }

    private fun fetchAllCommentPages(pr: PR): List<CommentDto> {
        val result = ArrayList<CommentDto>()
        var url: String? = repoUrlBuilder().pathSegments(pr.id.toString(), "comments").toUrlString()
        while (url != null) {
            val page = sendRequest(httpRequestFactory.createGet(url), commentsPagedResponseHandler)
            result.addAll(page.values)
            url = page.next
        }
        return result
    }

    private fun repoUrlBuilder() = UrlBuilder.fromUrl(URL(API_ROOT))
            .pathSegments("repositories", settings.project, settings.slug)

    private fun <T> sendRequest(request: HttpUriRequest, handler: HttpResponseHandler<T>): T =
            handler.handle(httpClient.execute(request))
}
