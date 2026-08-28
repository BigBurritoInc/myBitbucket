package bitbucket

import domain.PR
import domain.Participant
import domain.ReviewStatus
import org.junit.Test
import ui.Settings
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PRQueryTest {

    private val settings = Settings(project = "PROJ", slug = "my-repo", url = "https://bitbucket.example.com")

    @Test
    fun urlIsRepositoryScopedAndCarriesEveryParameter() {
        assertEquals(
                "https://bitbucket.example.com/rest/api/1.0/projects/PROJ/repos/my-repo/pull-requests" +
                        "?state=OPEN&order=NEWEST&start=0&limit=100&avatarSize=64",
                pullRequestsUrl(settings, 0, 100, 64))
    }

    /** The page size has to survive into later pages, or they silently fall back to Bitbucket's 25. */
    @Test
    fun laterPagesKeepTheSameLimit() {
        assertEquals(
                "https://bitbucket.example.com/rest/api/1.0/projects/PROJ/repos/my-repo/pull-requests" +
                        "?state=OPEN&order=NEWEST&start=100&limit=100&avatarSize=64",
                pullRequestsUrl(settings, 100, 100, 64))
    }

    @Test
    fun urlKeepsWorkingWhenTheContextPathHasSegments() {
        val withContextPath = settings.copy(url = "https://example.com/bitbucket")
        assertTrue(pullRequestsUrl(withContextPath, 0, 100, 64)
                .startsWith("https://example.com/bitbucket/rest/api/1.0/projects/PROJ/repos/my-repo/pull-requests"))
    }

    @Test
    fun projectAndSlugAreEncoded() {
        val odd = settings.copy(project = "a b", slug = "c%d")
        assertTrue(pullRequestsUrl(odd, 0, 100, 64).contains("/projects/a%20b/repos/c%25d/pull-requests"))
    }

    @Test
    fun authorGoesToOwn() {
        val prs = listOf(pr(1, author = "alice", reviewers = listOf("bob")))
        val result = partitionPRs(prs, "alice")
        assertEquals(1, result.own.size)
        assertTrue(result.reviewing.isEmpty())
    }

    @Test
    fun reviewerGoesToReviewing() {
        val prs = listOf(pr(1, author = "bob", reviewers = listOf("alice")))
        val result = partitionPRs(prs, "alice")
        assertTrue(result.own.isEmpty())
        assertEquals(1, result.reviewing.size)
    }

    /** A self-reviewed PR belongs in both lists — the two role-filtered requests used to return it twice. */
    @Test
    fun authorWhoIsAlsoReviewerGoesToBoth() {
        val prs = listOf(pr(1, author = "alice", reviewers = listOf("alice", "bob")))
        val result = partitionPRs(prs, "alice")
        assertEquals(1, result.own.size)
        assertEquals(1, result.reviewing.size)
    }

    @Test
    fun unrelatedPRsAreDropped() {
        val prs = listOf(pr(1, author = "bob", reviewers = listOf("carol")))
        val result = partitionPRs(prs, "alice")
        assertTrue(result.own.isEmpty())
        assertTrue(result.reviewing.isEmpty())
    }

    @Test
    fun usernameComparisonIgnoresCaseAndSurroundingSpace() {
        val prs = listOf(pr(1, author = "Alice", reviewers = listOf("BOB")))
        val result = partitionPRs(prs, " alice ")
        assertEquals(1, result.own.size)
        assertEquals(1, partitionPRs(prs, "bob").reviewing.size)
    }

    /** A blank username must not match everyone — it would put every PR in both lists. */
    @Test
    fun blankUsernameMatchesNobody() {
        val prs = listOf(pr(1, author = "alice", reviewers = listOf("bob")))
        val result = partitionPRs(prs, "   ")
        assertTrue(result.own.isEmpty())
        assertTrue(result.reviewing.isEmpty())
        assertFalse("".sameUserAs(""))
    }

    @Test
    fun notAReviewerNeedsAttention() {
        assertEquals(ReviewAttention.NEEDS_ATTENTION,
                pr(1, author = "bob", reviewers = listOf("carol")).attentionFor("alice"))
    }

    @Test
    fun reviewerWhoHasNotActedNeedsAttention() {
        assertEquals(ReviewAttention.NEEDS_ATTENTION,
                pr(1, author = "bob", reviewers = listOf("alice")).attentionFor("alice"))
    }

    @Test
    fun reviewerWhoRequestedChangesIsWaiting() {
        val pr = prWith(reviewer("alice", approved = false, status = ReviewStatus.NEEDS_WORK))
        assertEquals(ReviewAttention.CHANGES_REQUESTED, pr.attentionFor("alice"))
    }

    @Test
    fun reviewerWhoApprovedIsDone() {
        val pr = prWith(reviewer("alice", approved = true, status = ReviewStatus.APPROVED))
        assertEquals(ReviewAttention.APPROVED, pr.attentionFor("alice"))
    }

    /** Someone else's approval says nothing about whether this user still has to look. */
    @Test
    fun anotherReviewersApprovalDoesNotCount() {
        val pr = prWith(reviewer("bob", approved = true, status = ReviewStatus.APPROVED),
                reviewer("alice", approved = false, status = ReviewStatus.UNAPPROVED))
        assertEquals(ReviewAttention.NEEDS_ATTENTION, pr.attentionFor("alice"))
    }

    /** Before the first poll resolves the username nothing may be hidden or reordered. */
    @Test
    fun unknownUserNeedsAttention() {
        val pr = prWith(reviewer("alice", approved = true, status = ReviewStatus.APPROVED))
        assertEquals(ReviewAttention.NEEDS_ATTENTION, pr.attentionFor(null))
    }

    /** Declaration order is display order: attention first, then waiting, then done. */
    @Test
    fun attentionOrdersBeforeChangesRequestedOrdersBeforeApproved() {
        assertTrue(ReviewAttention.NEEDS_ATTENTION.ordinal < ReviewAttention.CHANGES_REQUESTED.ordinal)
        assertTrue(ReviewAttention.CHANGES_REQUESTED.ordinal < ReviewAttention.APPROVED.ordinal)
    }

    private fun prWith(vararg reviewers: Participant): PR {
        val epoch = ZonedDateTime.ofInstant(Instant.EPOCH, ZoneId.of("UTC"))
        return PR(1, "PR#1", "", "from", "to", participant("bob"), reviewers.toSet(),
                epoch, epoch, 0, "href", 0, false)
    }

    private fun reviewer(name: String, approved: Boolean, status: ReviewStatus) =
            Participant(name, name, approved, status)

    private fun pr(id: Long, author: String, reviewers: List<String>): PR {
        val epoch = ZonedDateTime.ofInstant(Instant.EPOCH, ZoneId.of("UTC"))
        return PR(id, "PR#$id", "", "from", "to", participant(author),
                reviewers.map { participant(it) }.toSet(),
                epoch, epoch, 0, "href$id", 0, false)
    }

    private fun participant(name: String) =
            Participant(name, name, false, ReviewStatus.UNAPPROVED)
}
