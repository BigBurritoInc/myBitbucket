package bitbucket.server

import bitbucket.server.dto.MergeStatusDto
import bitbucket.server.dto.ParticipantDto
import bitbucket.server.dto.ParticipantStatusDto
import bitbucket.server.dto.PullRequestDto
import domain.MergeStatus
import domain.PR
import domain.Participant
import domain.ReviewStatus
import domain.Veto
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

/**
 * Bitbucket Server wire format to domain model. The only place that knows what Server's JSON looks
 * like — everything above the client works in `domain` types. See CLAUDE.md "Domain model vs wire
 * format".
 */
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

private fun Date.toUtc(): ZonedDateTime = ZonedDateTime.ofInstant(toInstant(), ZoneId.of("UTC"))
