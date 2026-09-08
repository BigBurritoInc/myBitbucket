package domain

import java.time.ZonedDateTime

/** A pull request comment as the rest of the plugin understands one - no Bitbucket flavour. */
data class Comment(
        val id: Long,
        val authorUserName: String,
        val authorDisplayName: String,
        val body: String,
        /** Null for a top-level comment. */
        val parentCommentId: Long?,
        val createdAt: ZonedDateTime
)
