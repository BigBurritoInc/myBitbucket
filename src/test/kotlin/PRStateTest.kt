import domain.PR
import domain.Participant
import domain.ReviewStatus
import org.junit.Test
import ui.PRState
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals

class PRStateTest {
    private val bob = Participant("bob", "Bobby", false, ReviewStatus.UNAPPROVED)
    private val aaron = Participant("aaron", "Aa", false, ReviewStatus.NEEDS_WORK)

    @Test
    fun testCreateDiff() {
        val pr1 = pr(1, "PR#1", bob, setOf(bob, aaron), revision = 0)
        val pr2 = pr(2, "PR#2", aaron, setOf(bob), revision = 0)

        val initialState = PRState().createNew(listOf(pr1, pr2))

        //Bob understood that it is strange to be a reviewer of own pull request and removed himself
        val pr1NewState = pr(1, "PR#1", bob, setOf(aaron), revision = 1)
        //PR#2 was merged so Aaron created a new one
        val pr3 = pr(3, "PR#3", aaron, setOf(bob), revision = 0)

        val diff = initialState.createDiff(listOf(pr1NewState, pr3))
        assertEquals(1, diff.added.size)
        assertEquals(1, diff.updated.size)
        assertEquals(1, diff.removed.size)
        assertEquals(pr3, diff.added[3L])
        assertEquals(pr1NewState, diff.updated[1L])
        assertEquals(pr2, diff.removed[2L])
    }

    private fun pr(id: Long, title: String, author: Participant,
                   reviewers: Set<Participant>, revision: Long): PR {
        val epoch = ZonedDateTime.ofInstant(Instant.EPOCH, ZoneId.of("UTC"))
        return PR(id, title, "", "br1", "br2", author, reviewers,
                epoch, epoch, 1, "href$id", revision, false)
    }
}
