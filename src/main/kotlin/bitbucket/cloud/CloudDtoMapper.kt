package bitbucket.cloud

import bitbucket.cloud.dto.CommentDto
import bitbucket.cloud.dto.ParticipantDto
import bitbucket.cloud.dto.PullRequestDto
import domain.Comment
import domain.MergeStatus
import domain.PR
import domain.Participant
import domain.ReviewStatus
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Bitbucket Cloud wire format to domain model - the only place that knows Cloud's JSON shape.
 * docs: Domain model vs wire format */
fun PullRequestDto.toDomain(): PR {
    // The author often also appears in `participants` (with their own approved/state) - look it
    // up for a real answer instead of guessing UNAPPROVED for every author.
    val authorParticipant = participants.firstOrNull { it.user.uuid == author.uuid }
    return PR(
            id = id,
            title = title,
            description = description ?: "",
            fromBranch = source.branch.name,
            toBranch = destination.branch.name,
            author = Participant(
                    userName = author.uuid,
                    displayName = author.displayName,
                    approved = authorParticipant?.approved ?: false,
                    status = authorParticipant?.toDomain()?.status ?: ReviewStatus.UNAPPROVED),
            reviewers = participants.filter { it.role == "REVIEWER" }.map { it.toDomain() }.toSet(),
            createdAt = createdOn.toUtc(),
            updatedAt = updatedOn.toUtc(),
            commentCount = commentCount,
            webUrl = links.html.href,
            // Cloud keeps no version counter, so the domain revision is the update timestamp
            // instead.
            // docs: Domain model vs wire format
            revision = updatedOn.toUtc().toInstant().toEpochMilli(),
            closed = state != "OPEN"
    )
}

fun ParticipantDto.toDomain(): Participant = Participant(
        userName = user.uuid,
        displayName = user.displayName,
        approved = approved,
        status = when (state) {
            "approved" -> ReviewStatus.APPROVED
            "changes_requested" -> ReviewStatus.NEEDS_WORK
            else -> ReviewStatus.UNAPPROVED
        }
)

// No public merge-conflict-status endpoint on Cloud - always optimistic; a real conflict
// surfaces as a failed merge attempt instead. docs: Adding Bitbucket Cloud support
val CLOUD_MERGE_STATUS = MergeStatus(canMerge = true, conflicted = false, vetoes = emptyList(), known = true)

fun CommentDto.toDomain(): Comment = Comment(
        id = id,
        authorUserName = user.uuid,
        authorDisplayName = user.displayName,
        body = content.raw,
        parentCommentId = parent?.id,
        createdAt = createdOn.toUtc()
)

// Cloud's timestamps carry microsecond precision ("...000000+00:00"), which java.util.Date +
// Jackson's default StdDateFormat isn't guaranteed to accept - parsed as a plain String instead.
private fun String.toUtc(): ZonedDateTime =
        OffsetDateTime.parse(this).toZonedDateTime().withZoneSameInstant(ZoneId.of("UTC"))
