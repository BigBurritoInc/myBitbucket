package bitbucket.server

import bitbucket.server.dto.ActivityCommentDto
import bitbucket.server.dto.MergeStatusDto
import bitbucket.server.dto.ParticipantDto
import bitbucket.server.dto.ParticipantStatusDto
import bitbucket.server.dto.PullRequestDto
import domain.Comment
import domain.MergeStatus
import domain.PR
import domain.Participant
import domain.ReviewStatus
import domain.Veto
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

/** Bitbucket Server wire format to domain model — the only place that knows Server's JSON shape.
 * docs: Domain model vs wire format */
fun PullRequestDto.toDomain(): PR = PR(
        id = id,
        title = title,
        description = description ?: "",
        fromBranch = fromRef.name,
        toBranch = toRef.name,
        author = author.toDomain(),
        reviewers = reviewers.map { it.toDomain() }.toSet(),
        createdAt = createdDate.toUtc(),
        updatedAt = updatedDate.toUtc(),
        commentCount = properties.commentCount,
        webUrl = links.getSelfHref(),
        // Server keeps a real version counter, so the domain revision is just that.
        revision = version.toLong(),
        closed = closed
)

fun ParticipantDto.toDomain(): Participant = Participant(
        userName = user.name,
        displayName = user.displayName,
        approved = approved,
        status = status.toDomain()
)

fun ParticipantStatusDto.toDomain(): ReviewStatus = when (this) {
    ParticipantStatusDto.NEEDS_WORK -> ReviewStatus.NEEDS_WORK
    ParticipantStatusDto.APPROVED -> ReviewStatus.APPROVED
    ParticipantStatusDto.UNAPPROVED -> ReviewStatus.UNAPPROVED
}

fun MergeStatusDto.toDomain(): MergeStatus = MergeStatus(
        canMerge = canMerge,
        conflicted = conflicted,
        vetoes = vetoes.map { Veto(it.summaryMessage, it.detailedMessage) },
        // It was fetched, so whatever it says is the real answer.
        known = true
)

/** Flattens this comment and every reply nested under it (recursively) into one list, deriving
 * each reply's [Comment.parentCommentId] from the nesting itself — Server's wire format has no
 * flat "parent" field the way Cloud's does. [parentCommentId] is the id of the comment this one is
 * nested under, or null at the top of the recursion.
 * docs: Inbox */
fun ActivityCommentDto.toDomainTree(parentCommentId: Long? = null): List<Comment> {
    val self = Comment(
            id = id,
            authorUserName = author.name,
            authorDisplayName = author.displayName,
            body = text,
            parentCommentId = parentCommentId,
            createdAt = createdDate.toUtc()
    )
    return listOf(self) + comments.flatMap { it.toDomainTree(id) }
}

private fun Date.toUtc(): ZonedDateTime = ZonedDateTime.ofInstant(toInstant(), ZoneId.of("UTC"))
