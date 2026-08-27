
import bitbucket.data.*
import com.intellij.ui.scale.JBUIScale
import ui.*
import java.util.*
import javax.swing.JFrame
import javax.swing.JScrollPane
import kotlin.collections.HashMap
import kotlin.collections.HashSet

object PanelRunner {

    val br = "feature/PROJ-1980-it-is-a-feature-that-has-a-workitem-branch"

    // Model is a per-project service and needs a real Project — PanelRunner has neither, so it
    // skips PanelFactory.createReviewPanel() and builds its own Panel with a no-op stub instead.
    // See CLAUDE.md "PanelRunner".
    private val noopActions = object : PRActions {
        override fun checkout(pr: PR) {}
        override fun approve(pr: PR, callback: java.util.function.Consumer<Boolean>) {}
        override fun merge(pr: PR, callback: java.util.function.Consumer<Boolean>) {}
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // Must precede any PRComponent construction — see CLAUDE.md "PanelRunner".
        JBUIScale.setSystemScaleFactor(1f)
        val frame = JFrame()
        val panel = object : Panel() {
            override fun createPRComponent(pr: PR): PRComponent = PRComponent(pr, noopActions)
            override fun reviewedUpdated(diff: Diff) = dataUpdated(diff)
        }
        val map = HashMap<Long, PR>()
        for (i in 0..20) {
            map[i.toLong()] = createPR(i.toLong(), i % 10)
        }
        panel.dataUpdated(Diff(map, emptyMap(), emptyMap(), emptyMap()))
        panel.currentBranchChanged("feature/PROJ-1980-it-is-a-feature-that-has-a-workitem-branch3")
        // Plain JScrollPane, not PanelFactory.wrapIntoJBScroll() — see CLAUDE.md "PanelRunner".
        frame.contentPane.add(JScrollPane(panel))

        frame.pack()
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.isVisible = true
    }

    fun createPR(id: Long, reviewersCount: Int): PR {
        var title = "This is a pull request submitted by a programmer here with # $id"
        for (p in 0..id % 3)
            title += " more info"


        var to = "feature/PROJ-1955-it-is-a-feature-that-has-a-story-branch"

        for (k in 0..id % 4)
            to += "8984"
        val repo = Repository("slug", Project("project_key"))
        val props = PRProperties(1)
        val reviewers = HashSet<PRParticipant>()
        if (reviewersCount != 0) {
            for (userId in 0..reviewersCount) {
                reviewers.add(PRParticipant(
                        User("UserName$userId", "username$userId@email.com", userId.toLong(), "FirstName$userId LastName$userId",
                                Links(listOf(Links.Link("https://www.atlassian.com/software/bitbucket")))),
                        userId % 2 == 0,
                        ParticipantStatus.entries[(userId % ParticipantStatus.entries.size)]
                ))
            }
        }

        return PR(id, title,
                PRParticipant(User("har993", "billybobharley.is.here@tdameritrade.com", 2, "Billy Bob Harley",
                        Links(listOf(Links.Link("https://developer.atlassian.com/bitbucket/api/2/reference/")))), false, ParticipantStatus.UNAPPROVED),
                false,
                Branch("$br$id", repo),
                Branch(to, repo),
                reviewers,
                Date(System.currentTimeMillis()), Date(System.currentTimeMillis()),
                props,
                Links(listOf(Links.Link("https://developer.atlassian.com/bitbucket/api/2/reference/"))), 0,
                // Every third PR gets a description, to preview both card variants.
                if (id % 3 == 0L) descriptionSample(id) else null
        )
    }

    private fun descriptionSample(id: Long) = """
        ## Summary
        This fixes the retry logic in `RequestExecutor#$id` and updates the *timeout* handling.

        - covers the flaky case from PROJ-1980
        - adds a regression test
        - see [the original report](https://example.com/issue/$id) for context
    """.trimIndent()
}