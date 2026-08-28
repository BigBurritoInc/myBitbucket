package bitbucket

import domain.MergeStatus
import domain.PR
import domain.Participant
import domain.ReviewStatus
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MergeStatusCacheTest {

    private var fetchCount = 0

    @Test
    fun firstSightFetches() {
        val cache = MergeStatusCache()
        cache.applyTo(listOf(pr(1, version = 0))) { status() }
        assertEquals(1, fetchCount)
    }

    /**
     * The point of the cache. Also asserts the status lands on the new PR object: every poll
     * parses fresh ones, and Diff.mergeStatusChanged ignores any whose status is still unknown.
     */
    @Test
    fun unchangedPRIsServedFromCache() {
        val cache = MergeStatusCache()
        val cached = status()
        cache.applyTo(listOf(pr(1, version = 0))) { cached }

        val secondPoll = pr(1, version = 0)
        cache.applyTo(listOf(secondPoll)) { status() }

        assertEquals(1, fetchCount)
        assertSame(cached, secondPoll.mergeStatus)
    }

    /** Time alone must not trigger a request — only the pull request actually changing does. */
    @Test
    fun nothingIsRefetchedWhileVersionsHold() {
        val cache = MergeStatusCache()
        cache.applyTo(listOf(pr(1, version = 3))) { status() }
        repeat(100) { cache.applyTo(listOf(pr(1, version = 3))) { status() } }
        assertEquals(1, fetchCount)
    }

    @Test
    fun higherVersionRefetches() {
        val cache = MergeStatusCache()
        cache.applyTo(listOf(pr(1, version = 0))) { status() }
        cache.applyTo(listOf(pr(1, version = 1))) { status() }
        assertEquals(2, fetchCount)
    }

    /** A version going backwards is a server oddity, not a change worth spending a request on. */
    @Test
    fun lowerVersionDoesNotRefetch() {
        val cache = MergeStatusCache()
        cache.applyTo(listOf(pr(1, version = 5))) { status() }
        cache.applyTo(listOf(pr(1, version = 4))) { status() }
        assertEquals(1, fetchCount)
    }

    @Test
    fun vanishedPRsAreEvicted() {
        val cache = MergeStatusCache()
        cache.applyTo(listOf(pr(1, version = 0))) { status() }
        cache.applyTo(listOf(pr(2, version = 0))) { status() }
        // PR 1 is gone; when it comes back its cached entry must not be reused.
        cache.applyTo(listOf(pr(1, version = 0))) { status() }
        assertEquals(3, fetchCount)
    }

    @Test
    fun perCycleFetchBudgetIsRespected() {
        val cache = MergeStatusCache()
        cache.applyTo((1L..50L).map { pr(it, version = 0) }) { status() }
        assertEquals(20, fetchCount)
    }

    /** Whatever the budget cut off must still be picked up, or it would never get a status at all. */
    @Test
    fun budgetLeftoversAreFetchedOnLaterCycles() {
        val cache = MergeStatusCache()
        val prs = (1L..50L).map { pr(it, version = 0) }
        cache.applyTo(prs) { status() }
        cache.applyTo((1L..50L).map { pr(it, version = 0) }) { status() }
        cache.applyTo((1L..50L).map { pr(it, version = 0) }) { status() }
        assertEquals(50, fetchCount)
    }

    private fun status(): MergeStatus {
        fetchCount++
        return MergeStatus(canMerge = true, conflicted = false, vetoes = emptyList(), known = true)
    }

    private fun pr(id: Long, version: Long): PR {
        val epoch = ZonedDateTime.ofInstant(Instant.EPOCH, ZoneId.of("UTC"))
        val user = Participant("alice", "Alice", false, ReviewStatus.UNAPPROVED)
        return PR(id, "PR#$id", "", "from", "to", user, setOf(user),
                epoch, epoch, 0, "href$id", version, false)
    }
}
