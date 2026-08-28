
import com.intellij.ui.scale.JBUIScale
import domain.PR
import domain.Participant
import domain.ReviewStatus
import ui.*
import java.time.ZoneId
import java.time.ZonedDateTime
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

        val reviewers = HashSet<Participant>()
        if (reviewersCount != 0) {
            for (userId in 0..reviewersCount) {
                reviewers.add(Participant(
                        "UserName$userId",
                        "FirstName$userId LastName$userId",
                        userId % 2 == 0,
                        ReviewStatus.entries[(userId % ReviewStatus.entries.size)]))
            }
        }

        val now = ZonedDateTime.now(ZoneId.of("UTC"))
        return PR(
                id = id,
                title = title,
                // Every third PR gets a description, to preview both card variants.
                description = if (id % 3 == 0L) descriptionSample(id) else "",
                fromBranch = "$br$id",
                toBranch = to,
                author = Participant("har993", "Billy Bob Harley", false, ReviewStatus.UNAPPROVED),
                reviewers = reviewers,
                createdAt = now,
                updatedAt = now,
                commentCount = 1,
                webUrl = "https://developer.atlassian.com/bitbucket/api/2/reference/",
                revision = 0,
                closed = false)
    }

    private fun descriptionSample(id: Long) = """
        ## Summary
        This fixes the retry logic in `RequestExecutor#$id` and updates the *timeout* handling.

        - covers the flaky case from PROJ-1980
        - adds a regression test
        - see [the original report](https://example.com/issue/$id) for context
    """.trimIndent()
}