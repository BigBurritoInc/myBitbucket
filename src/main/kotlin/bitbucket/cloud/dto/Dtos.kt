package bitbucket.cloud.dto

import com.fasterxml.jackson.annotation.JsonProperty

/** Bitbucket Cloud (bitbucket.org) 2.0 REST wire format - mapped into `domain` by CloudDtoMapper
 * and never leaves the client layer.
 * docs: Domain model vs wire format */
data class PullRequestDto(
        @JsonProperty("id") val id: Long,
        @JsonProperty("title") val title: String,
        @JsonProperty("author") val author: CloudUserDto,
        @JsonProperty("state") val state: String,
        @JsonProperty("source") val source: CloudBranchRefDto,
        @JsonProperty("destination") val destination: CloudBranchRefDto,
        // Nullable, not just defaulted: a PR nobody has ever added as a reviewer/participant on
        // can come back with this key missing (or, on at least one real Cloud response, present
        // as JSON null) rather than "[]" - a plain `= emptyList()` default only covers a missing
        // key, not an explicit null, and Jackson threw constructing this DTO either way. Read via
        // `participants` below rather than this raw field. docs: Known incidents
        @JsonProperty("participants") private val participantsOrNull: List<ParticipantDto>? = null,
        @JsonProperty("created_on") val createdOn: String,
        @JsonProperty("updated_on") val updatedOn: String,
        @JsonProperty("comment_count") val commentCount: Int,
        @JsonProperty("links") val links: LinksDto,
        @JsonProperty("description") val description: String? = null
) {
    val participants: List<ParticipantDto> get() = participantsOrNull ?: emptyList()
}

data class CloudUserDto(
        @JsonProperty("uuid") val uuid: String,
        @JsonProperty("display_name") val displayName: String
)

data class CloudBranchRefDto(@JsonProperty("branch") val branch: CloudBranchNameDto)

data class CloudBranchNameDto(@JsonProperty("name") val name: String)

data class ParticipantDto(
        @JsonProperty("user") val user: CloudUserDto,
        // "REVIEWER" or "PARTICIPANT" - only REVIEWER entries map onto domain.PR.reviewers,
        // matching what Server's separate `reviewers` list already means.
        @JsonProperty("role") val role: String,
        @JsonProperty("approved") val approved: Boolean,
        // null = hasn't weighed in yet; "approved" / "changes_requested" otherwise.
        @JsonProperty("state") val state: String? = null
)

data class LinksDto(@JsonProperty("html") val html: LinkDto)

data class LinkDto(@JsonProperty("href") val href: String)

/** Cloud's cursor-based pagination - [next] is an already-complete URL for the following page,
 * absent on the last one. Deliberately not unified with Server's `PageDto` (start/limit/
 * isLastPage/nextPageStart).
 * docs: Domain model vs wire format */
data class PageDto<T>(
        @JsonProperty("page") val page: Int,
        @JsonProperty("pagelen") val pagelen: Int,
        @JsonProperty("size") val size: Int? = null,
        @JsonProperty("next") val next: String? = null,
        @JsonProperty("values") val values: List<T>
)

/** `GET /2.0/user` response - just enough to resolve the current user's uuid for `q=` filtering.
 * docs: Adding Bitbucket Cloud support */
data class CurrentUserDto(@JsonProperty("uuid") val uuid: String)

/** GET .../pullrequests/{id}/comments. [parent] is present only on a reply.
 * docs: Inbox */
data class CommentDto(
        @JsonProperty("id") val id: Long,
        @JsonProperty("content") val content: CommentContentDto,
        @JsonProperty("user") val user: CloudUserDto,
        @JsonProperty("created_on") val createdOn: String,
        @JsonProperty("parent") val parent: CommentParentDto? = null
)

data class CommentContentDto(@JsonProperty("raw") val raw: String)

data class CommentParentDto(@JsonProperty("id") val id: Long)
