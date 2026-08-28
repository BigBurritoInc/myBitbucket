import domain.PR
import domain.Participant
import domain.ReviewStatus
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PRTest {
    private val bob = Participant("bob", "Bobby", false, ReviewStatus.UNAPPROVED)
    private val aaron = Participant("aaron", "Aa", false, ReviewStatus.NEEDS_WORK)
    private val pr1 = pr(author = bob, reviewers = setOf(bob, aaron))

    @Test
    fun testEquality() {
        //same as pr1, but Aaron and Bob are swapped
        assertEquals(pr1, pr(author = bob, reviewers = setOf(aaron, bob)))
    }

    @Test
    fun testInequalityIfBobApproved() {
        val bobApproved = Participant("bob", "Bobby", true, ReviewStatus.APPROVED)
        assertNotEquals(pr1, pr(author = bobApproved, reviewers = setOf(bob, aaron)))
    }

    /** mergeStatus lives outside the constructor so it can't make a PR look changed. */
    @Test
    fun mergeStatusIsNotPartOfEquality() {
        val withStatus = pr(author = bob, reviewers = setOf(bob, aaron))
        withStatus.mergeStatus = domain.MergeStatus(true, false, emptyList(), known = true)
        assertEquals(pr1, withStatus)
    }

    private fun pr(author: Participant, reviewers: Set<Participant>): PR {
        val epoch = ZonedDateTime.ofInstant(java.time.Instant.EPOCH, ZoneId.of("UTC"))
        return PR(1, "PR#1", "", "br1", "br2", author, reviewers,
                epoch, epoch, 1, "href0", 0, false)
    }
}
