package bitbucket.server.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.Date

/** Bitbucket Server / Data Center wire format - mapped into `domain` by ServerDtoMapper and never
 * leaves the client layer. */
data class PullRequestDto(
        @JsonProperty("id") val id: Long,
        @JsonProperty("title") val title: String,
        @JsonProperty("author") val author: ParticipantDto,
        @JsonProperty("closed") val closed: Boolean,
        @JsonProperty("fromRef") val fromRef: BranchDto,
        @JsonProperty("toRef") val toRef: BranchDto,
        @JsonProperty("reviewers") val reviewers: Set<ParticipantDto>,
        @JsonProperty("createdDate") val createdDate: Date,
        @JsonProperty("updatedDate") val updatedDate: Date,
        @JsonProperty("properties") val properties: PropertiesDto,
        @JsonProperty("links") val links: LinksDto,
        @JsonProperty("version") val version: Int,
        // Bitbucket Server omits this key entirely for a pull request with no description, rather
        // than sending an empty string, so it has to be nullable here.
        @JsonProperty("description") val description: String? = null
)

data class ParticipantDto(
        @JsonProperty("user") val user: UserDto,
        @JsonProperty("approved") val approved: Boolean,
        @JsonProperty("status") val status: ParticipantStatusDto
)

enum class ParticipantStatusDto {
    NEEDS_WORK,
    APPROVED,
    UNAPPROVED
}

data class UserDto(
        @JsonProperty("name") val name: String,
        // under some circumstances email can be null
        @JsonProperty("emailAddress") val emailAddress: String?,
        @JsonProperty("id") val id: Long,
        @JsonProperty("displayName") val displayName: String,
        @JsonProperty("links") val links: LinksDto,
        // Only populated when the request asked for it - see ServerBitbucketClient.AVATAR_SIZE.
        @JsonProperty("avatarUrl") val avatarUrl: String? = null) {

    /** The avatar URL to fetch - [avatarUrl] must win.
     * docs: Known incidents */
    fun avatarHref(): String {
        val url = avatarUrl ?: return links.getIconHref()
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val base = serverBaseUrl() ?: return links.getIconHref()
        return base + if (url.startsWith("/")) url else "/$url"
    }

    private fun serverBaseUrl(): String? {
        val self = links.getSelfHref()
        val schemeEnd = self.indexOf("://")
        if (schemeEnd < 0) return null
        val pathStart = self.indexOf('/', schemeEnd + 3)
        return if (pathStart < 0) self else self.substring(0, pathStart)
    }
}

data class BranchDto(
        @JsonProperty("displayId") val name: String,
        @JsonProperty("repository") val repository: RepositoryDto
)

data class RepositoryDto(
        @JsonProperty("slug") val slug: String,
        @JsonProperty("project") val project: ProjectDto
)

data class ProjectDto(@JsonProperty("key") val key: String)

data class PropertiesDto(@JsonProperty("commentCount") val commentCount: Int)

data class LinksDto(@JsonProperty("self") private val selfLink: List<LinkDto>) {
    fun getSelfHref(): String = selfLink.first().href

    // Fallback only - this is the web-UI path, which ignores access-token auth and answers with
    // Bitbucket's generic default avatar. Prefer UserDto.avatarHref().
    // docs: Avatars
    fun getIconHref(): String = "${selfLink.first().href}/avatar.png?s=24"
}

data class LinkDto(@JsonProperty("href") val href: String)

data class PageDto<T>(
        @JsonProperty("start") val start: Int,
        @JsonProperty("size") val size: Int,
        @JsonProperty("limit") val limit: Int,
        @JsonProperty("isLastPage") val isLastPage: Boolean,
        @JsonProperty("nextPageStart") val nextPageStart: Int,
        @JsonProperty("values") val values: List<T>)

data class MergeStatusDto(
        @JsonProperty("canMerge") val canMerge: Boolean,
        @JsonProperty("conflicted") val conflicted: Boolean,
        @JsonProperty("vetoes") val vetoes: List<VetoDto>
)

data class VetoDto(
        @JsonProperty("summaryMessage") val summaryMessage: String,
        @JsonProperty("detailedMessage") val detailedMessage: String
)

/** Request body for PUT .../participants/{userSlug} - the documented, non-deprecated way to set
 * the current user's own review status (APPROVED or UNAPPROVED here; NEEDS_WORK is Bitbucket's,
 * not surfaced by this plugin). Used for both approving and withdrawing an approval.
 * docs: Known incidents */
data class ParticipantStatusRequestDto(@JsonProperty("user") val user: SimpleUserDto,
                             @JsonProperty("status") val status: String,
                             @JsonProperty("approved") val approved: String)

data class SimpleUserDto(@JsonProperty("name") val name: String)

/** GET .../pull-requests/{id}/activities - the real general-comment feed (not "comments"). Only
 * [comment] matters here; other activity types (APPROVED, MERGED, RESCOPED, ...) are ignored.
 * docs: Inbox */
data class ActivityDto(
        @JsonProperty("id") val id: Long,
        @JsonProperty("action") val action: String,
        @JsonProperty("comment") val comment: ActivityCommentDto? = null
)

/** Unlike Cloud, no flat `parent`/`parent.id` - a reply is nested inside its parent's own
 * [comments] instead; ServerDtoMapper derives a reply's parent while walking this tree.
 * docs: Inbox */
data class ActivityCommentDto(
        @JsonProperty("id") val id: Long,
        @JsonProperty("text") val text: String,
        @JsonProperty("author") val author: SimpleAuthorDto,
        @JsonProperty("createdDate") val createdDate: Date,
        @JsonProperty("comments") val comments: List<ActivityCommentDto> = emptyList()
)

data class SimpleAuthorDto(
        @JsonProperty("name") val name: String,
        @JsonProperty("displayName") val displayName: String
)
