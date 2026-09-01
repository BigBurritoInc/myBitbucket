package domain

import java.time.ZonedDateTime

enum class InboxEventKind {
    NEW_COMMENT,
    REPLY,
    APPROVAL
}

data class InboxEvent(
        val id: String,
        val kind: InboxEventKind,
        val prId: Long,
        val prTitle: String,
        val prWebUrl: String,
        val authorDisplayName: String,
        val snippet: String?,
        val timestamp: ZonedDateTime,
        var read: Boolean = false
)
