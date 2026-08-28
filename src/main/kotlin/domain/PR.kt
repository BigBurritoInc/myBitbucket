package domain

import java.time.ZonedDateTime

/**
 * A pull request, as the rest of the plugin understands one — no Bitbucket flavour, no Jackson.
 *
 * The wire formats live in `bitbucket.server.dto` (and, later, a Cloud equivalent) and are mapped
 * into this on the way out of the client. Nothing above the client layer should ever see a DTO:
 * Bitbucket Server and Bitbucket Cloud disagree on almost every field name, and on some concepts
 * outright. See CLAUDE.md "Domain model vs wire format".
 */
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
        /** Where the pull request lives in the browser. */
        val webUrl: String,
        /**
         * Monotonic "has this changed" token. Bitbucket Server has a real `version` counter;
         * Bitbucket Cloud has none, so it uses the update timestamp. Only ever compared, never
         * displayed or sent back — except by the Server client, whose merge endpoint wants the
         * version for optimistic locking.
         */
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
