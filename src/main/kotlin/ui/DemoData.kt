package ui

import domain.PR
import domain.Participant
import domain.ReviewStatus
import java.time.ZoneId
import java.time.ZonedDateTime

// Fake pull requests for DemoModeAction — no real server, no real names. See CLAUDE.md "Demo mode".
object DemoData {

    fun samplePRs(): List<PR> = listOf(
            samplePR(id = 900001, title = "Add pagination to the results table",
                    fromBranch = "feature/PROJ-1042-results-pagination", commentCount = 3,
                    description = "Paginates the results table instead of loading everything at once.\n\n" +
                            "- 50 rows per page\n- page size is not yet configurable",
                    reviewers = listOf(
                            reviewer("Ana Garcia", ReviewStatus.APPROVED, approved = true),
                            reviewer("Chen Wei", ReviewStatus.UNAPPROVED, approved = false))),
            samplePR(id = 900002, title = "Fix null pointer when a project has no members",
                    fromBranch = "bugfix/PROJ-988-empty-project-npe", commentCount = 0,
                    reviewers = listOf(reviewer("Sofia Rossi", ReviewStatus.NEEDS_WORK, approved = false))),
            samplePR(id = 900003, title = "Refactor the authentication module",
                    fromBranch = "feature/PROJ-1101-auth-refactor", commentCount = 8,
                    // 7 reviewers so the "+2" overflow badge shows up in the screenshot too.
                    reviewers = listOf(
                            reviewer("Liam O'Connor", ReviewStatus.APPROVED, approved = true),
                            reviewer("Priya Nair", ReviewStatus.APPROVED, approved = true),
                            reviewer("Jonas Berg", ReviewStatus.UNAPPROVED, approved = false),
                            reviewer("Mateus Silva", ReviewStatus.NEEDS_WORK, approved = false),
                            reviewer("Emma Johansson", ReviewStatus.UNAPPROVED, approved = false),
                            reviewer("Yuki Tanaka", ReviewStatus.APPROVED, approved = true),
                            reviewer("Omar Haddad", ReviewStatus.UNAPPROVED, approved = false))),
            samplePR(id = 900004, title = "Update dependency versions for the latest security patch",
                    fromBranch = "chore/PROJ-1150-dependency-bump", commentCount = 1,
                    description = "Bumps the usual suspects to their latest patch versions. No behavior changes.",
                    reviewers = listOf(
                            reviewer("Ana Garcia", ReviewStatus.APPROVED, approved = true),
                            reviewer("Sofia Rossi", ReviewStatus.APPROVED, approved = true))),
            samplePR(id = 900005, title = "Improve wording on the error message shown after a failed checkout",
                    fromBranch = "feature/PROJ-1172-error-wording", commentCount = 5, reviewers = emptyList())
    )

    private fun samplePR(id: Long, title: String, fromBranch: String, commentCount: Int,
                         reviewers: List<Participant>, description: String? = null): PR {
        val now = ZonedDateTime.now(ZoneId.of("UTC"))
        return PR(
                id = id,
                title = title,
                description = description ?: "",
                fromBranch = fromBranch,
                toBranch = "develop",
                author = reviewer("Mira Costa", ReviewStatus.UNAPPROVED, approved = false),
                reviewers = reviewers.toSet(),
                createdAt = now.minusDays(3),
                updatedAt = now.minusHours(1),
                commentCount = commentCount,
                webUrl = "https://bitbucket.example.com/projects/DEMO/repos/sample-repo/pull-requests/$id",
                revision = 1,
                closed = false
        )
    }

    private fun reviewer(displayName: String, status: ReviewStatus, approved: Boolean): Participant {
        val userName = displayName.lowercase().replace(" ", ".").replace("'", "")
        return Participant(userName, displayName, approved, status)
    }
}
