package domain

import java.time.ZonedDateTime

/** A pull request as the rest of the plugin understands one — no Bitbucket flavour, no Jackson;
 * exists to hide the wire-format split.
 * docs: Domain model vs wire format */
data class PR(
        val id: Long,
        val title: String,
        val description: String,
        val fromBranch: String,
        val toBranch: String,
        val author: Participant,
        val reviewers: Set<Participant>,
        val createdAt: ZonedDateTime,
        val updatedAt: ZonedDateTime,
        val commentCount: Int,
        val webUrl: String,
        /** Monotonic "has this changed" token — Server's `version`, Cloud's update timestamp.
         * docs: Domain model vs wire format */
        val revision: Long,
        val closed: Boolean
) {
    // Outside the constructor, so it stays out of equals(): a merge status arriving later must not
    // make a pull request look "updated" to PRState's diffing.
    @Volatile
    var mergeStatus: MergeStatus = MergeStatus.UNKNOWN
}

/** Someone attached to a pull request — its author, or one of its reviewers. */
data class Participant(
        /** The login Bitbucket knows them by; compared against the current user, never displayed. */
        val userName: String,
        val displayName: String,
        val approved: Boolean,
        val status: ReviewStatus
)

// Declaration order is the sort order in ReviewersPanel: whoever still needs to act comes first.
enum class ReviewStatus {
    NEEDS_WORK,
    APPROVED,
    UNAPPROVED
}
