package ui

import bitbucket.data.*
import java.util.Date

// Fake pull requests for DemoModeAction — no real server, no real names. See CLAUDE.md "Demo mode".
object DemoData {

    fun samplePRs(): List<PR> = listOf(
            samplePR(id = 900001, title = "Add pagination to the results table",
                    fromBranch = "feature/PROJ-1042-results-pagination", commentCount = 3,
                    description = "Paginates the results table instead of loading everything at once.\n\n" +
                            "- 50 rows per page\n- page size is not yet configurable",
                    reviewers = listOf(
                            reviewer("Ana Garcia", ParticipantStatus.APPROVED, approved = true),
                            reviewer("Chen Wei", ParticipantStatus.UNAPPROVED, approved = false))),
            samplePR(id = 900002, title = "Fix null pointer when a project has no members",
                    fromBranch = "bugfix/PROJ-988-empty-project-npe", commentCount = 0,
                    reviewers = listOf(reviewer("Sofia Rossi", ParticipantStatus.NEEDS_WORK, approved = false))),
            samplePR(id = 900003, title = "Refactor the authentication module",
                    fromBranch = "feature/PROJ-1101-auth-refactor", commentCount = 8,
                    // 7 reviewers so the "+2" overflow badge shows up in the screenshot too.
                    reviewers = listOf(
                            reviewer("Liam O'Connor", ParticipantStatus.APPROVED, approved = true),
                            reviewer("Priya Nair", ParticipantStatus.APPROVED, approved = true),
                            reviewer("Jonas Berg", ParticipantStatus.UNAPPROVED, approved = false),
                            reviewer("Mateus Silva", ParticipantStatus.NEEDS_WORK, approved = false),
                            reviewer("Emma Johansson", ParticipantStatus.UNAPPROVED, approved = false),
                            reviewer("Yuki Tanaka", ParticipantStatus.APPROVED, approved = true),
                            reviewer("Omar Haddad", ParticipantStatus.UNAPPROVED, approved = false))),
            samplePR(id = 900004, title = "Update dependency versions for the latest security patch",
                    fromBranch = "chore/PROJ-1150-dependency-bump", commentCount = 1,
                    description = "Bumps the usual suspects to their latest patch versions. No behavior changes.",
                    reviewers = listOf(
                            reviewer("Ana Garcia", ParticipantStatus.APPROVED, approved = true),
                            reviewer("Sofia Rossi", ParticipantStatus.APPROVED, approved = true))),
            samplePR(id = 900005, title = "Improve wording on the error message shown after a failed checkout",
                    fromBranch = "feature/PROJ-1172-error-wording", commentCount = 5, reviewers = emptyList())
    )

    private fun samplePR(id: Long, title: String, fromBranch: String, commentCount: Int,
                          reviewers: List<PRParticipant>, description: String? = null): PR {
        val repo = Repository("sample-repo", Project("DEMO"))
        val selfHref = "https://bitbucket.example.com/projects/DEMO/repos/sample-repo/pull-requests/$id"
        return PR(id, title,
                reviewer("John Bob", ParticipantStatus.UNAPPROVED, approved = false),
                false,
                Branch(fromBranch, repo),
                Branch("release/v202", repo),
                reviewers.toSet(),
                Date(System.currentTimeMillis() - 3 * 24 * 60 * 60 * 1000),
                Date(System.currentTimeMillis() - 60 * 60 * 1000),
                PRProperties(commentCount),
                Links(listOf(Links.Link(selfHref))), 1,
                description)
    }

    private fun reviewer(displayName: String, status: ParticipantStatus, approved: Boolean): PRParticipant {
        val emailLocalPart = displayName.lowercase().replace(" ", ".").replace("'", "")
        val user = User(emailLocalPart, "$emailLocalPart@example.com", displayName.hashCode().toLong(),
                displayName, Links(listOf(Links.Link("https://bitbucket.example.com/users/$emailLocalPart"))))
        return PRParticipant(user, approved, status)
    }
}
