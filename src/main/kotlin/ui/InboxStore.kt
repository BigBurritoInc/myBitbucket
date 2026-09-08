package ui

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.util.xmlb.XmlSerializerUtil
import domain.InboxEvent

// docs: Inbox
private const val MAX_STORED_EVENTS = 200

data class InboxState(
        var lastCheckedRevision: MutableMap<Long, Long> = mutableMapOf(),
        var lastSeenCommentId: MutableMap<Long, Long> = mutableMapOf(),
        var lastApprovals: MutableMap<Long, MutableMap<String, Boolean>> = mutableMapOf(),
        var myCommentIds: MutableMap<Long, MutableSet<Long>> = mutableMapOf(),
        var events: MutableList<InboxEvent> = mutableListOf()
)

@State(name = "BitbucketHelper4IdeaInbox", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class InboxStore : PersistentStateComponent<InboxState> {
    // Not "state" - docs: Known incidents
    var inboxState = InboxState()

    override fun getState(): InboxState = inboxState

    override fun loadState(s: InboxState) {
        XmlSerializerUtil.copyBean(s, inboxState)
    }

    @Synchronized
    fun recordEvents(events: List<InboxEvent>) {
        if (events.isEmpty()) return
        val existingIds = inboxState.events.map { it.id }.toSet()
        val fresh = events.filter { it.id !in existingIds }
        inboxState.events.addAll(0, fresh)
        while (inboxState.events.size > MAX_STORED_EVENTS) {
            inboxState.events.removeAt(inboxState.events.size - 1)
        }
    }

    @Synchronized
    fun markRead(eventId: String) {
        inboxState.events.firstOrNull { it.id == eventId }?.read = true
    }

    @Synchronized
    fun markAllRead() {
        inboxState.events.forEach { it.read = true }
    }

    @Synchronized
    fun unreadCount(): Int = inboxState.events.count { !it.read }
}
