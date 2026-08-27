import bitbucket.data.*
import org.junit.Ignore
import org.junit.Test
import ui.PRState
import java.util.*
import kotlin.test.assertEquals

@Ignore
class PRStateTest {
    private val bob = PRParticipant(User("Bob", "bob@address.com",
            1, "Bobby", Links(listOf(Links.Link("self_1")))), false, ParticipantStatus.UNAPPROVED)
    private val aaron = PRParticipant(User("Aaron", "aaron@address.com",
            1, "Aa", Links(listOf(Links.Link("self_2")))), false, ParticipantStatus.NEEDS_WORK)
    private val repo = Repository("slug1", Project("key1"))
    private val from = Branch("br1", repo)
    private val to = Branch("br2", repo)
    private val props = PRProperties(1)

    @Test
    fun testCreateDiff() {
        val pr1 = PR(1, "PR#1", bob, false, from, to, setOf(bob, aaron),
                Date(1), Date(2), props, Links(listOf(Links.Link("href1"))), 0)
        val pr2 = PR(2, "PR#2", aaron, false, from, to, setOf(bob),
                Date(1), Date(2), props, Links(listOf(Links.Link("href2"))), 0)

        val initialState = PRState().createNew(listOf(pr1, pr2))

        //Bob understood that it is strange to be a reviewer of own pull request and removed himself
        val pr1NewState = PR(1, "PR#1", bob, false, from, to, setOf(aaron),
                Date(1), Date(2), props, Links(listOf(Links.Link("href1"))), 1)
        //PR#2 was merged so Aaron created a new one
        val pr3 = PR(3, "PR#3", aaron, false, from, to, setOf(bob),
                Date(1), Date(2), props, Links(listOf(Links.Link("href3"))), 0)

        val diff = initialState.createDiff(listOf(pr1NewState, pr3))
        assertEquals(1, diff.added.size)
        assertEquals(1, diff.updated.size)
        assertEquals(1, diff.removed.size)
        assertEquals(pr3, diff.added[3L])
        assertEquals(pr1NewState, diff.updated[1L])
        assertEquals(pr2, diff.removed[2L])

    }

}