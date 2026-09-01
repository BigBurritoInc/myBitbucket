package bitbucket

import domain.Comment
import domain.InboxEvent
import domain.InboxEventKind
import domain.PR
import ui.InboxStore

private const val SNIPPET_MAX_LENGTH = 140

// docs: Inbox
class InboxTracker {

    fun detect(client: BitbucketClient, own: List<PR>, reviewing: List<PR>,
               currentUserName: String, store: InboxStore): List<InboxEvent> {
        val events = ArrayList<InboxEvent>()
        val processedPrIds = HashSet<Long>()
        for (pr in own) {
            if (!processedPrIds.add(pr.id)) continue
            events.addAll(checkOwnPR(client, pr, currentUserName, store))
        }
        for (pr in reviewing) {
            if (!processedPrIds.add(pr.id)) continue
            events.addAll(checkReviewingPR(client, pr, currentUserName, store))
        }
        pruneClosedPRs(own, reviewing, store)
        return events
    }

    private fun checkOwnPR(client: BitbucketClient, pr: PR, currentUserName: String,
                            store: InboxStore): List<InboxEvent> {
        val events = ArrayList<InboxEvent>()
        val firstSeen = !store.inboxState.lastCheckedRevision.containsKey(pr.id)
        if (firstSeen || pr.revision > store.inboxState.lastCheckedRevision.getValue(pr.id)) {
            val comments = fetchAndRecordComments(client, pr, currentUserName, store)
            if (!firstSeen) {
                comments.filter { !it.authorUserName.sameUserAs(currentUserName) }.forEach {
                    events.add(newCommentEvent(pr, it, InboxEventKind.NEW_COMMENT))
                }
            }
        }
        events.addAll(checkApprovals(pr, currentUserName, store, firstSeen))
        store.inboxState.lastCheckedRevision[pr.id] = pr.revision
        return events
    }

    private fun checkReviewingPR(client: BitbucketClient, pr: PR, currentUserName: String,
                                  store: InboxStore): List<InboxEvent> {
        val events = ArrayList<InboxEvent>()
        val firstSeen = !store.inboxState.lastCheckedRevision.containsKey(pr.id)
        if (firstSeen || pr.revision > store.inboxState.lastCheckedRevision.getValue(pr.id)) {
            val comments = fetchAndRecordComments(client, pr, currentUserName, store)
            if (!firstSeen) {
                val myCommentIds = store.inboxState.myCommentIds[pr.id] ?: emptySet()
                comments.filter {
                    !it.authorUserName.sameUserAs(currentUserName)
                            && it.parentCommentId != null && it.parentCommentId in myCommentIds
                }.forEach { events.add(newCommentEvent(pr, it, InboxEventKind.REPLY)) }
            }
        }
        store.inboxState.lastCheckedRevision[pr.id] = pr.revision
        return events
    }

    private fun newCommentEvent(pr: PR, comment: Comment, kind: InboxEventKind) = InboxEvent(
            id = "${pr.id}:comment:${comment.id}",
            kind = kind,
            prId = pr.id,
            prTitle = pr.title,
            prWebUrl = pr.webUrl,
            authorDisplayName = comment.authorDisplayName,
            snippet = comment.body.take(SNIPPET_MAX_LENGTH),
            timestamp = comment.createdAt)

    private fun fetchAndRecordComments(client: BitbucketClient, pr: PR, currentUserName: String,
                                        store: InboxStore): List<Comment> {
        val sinceId = store.inboxState.lastSeenCommentId[pr.id]
        val comments = client.fetchComments(pr, sinceId)
        if (comments.isEmpty()) return comments
        val mine = store.inboxState.myCommentIds.getOrPut(pr.id) { mutableSetOf() }
        comments.forEach { if (it.authorUserName.sameUserAs(currentUserName)) mine.add(it.id) }
        val maxId = comments.maxOf { it.id }
        val previous = store.inboxState.lastSeenCommentId[pr.id]
        store.inboxState.lastSeenCommentId[pr.id] = if (previous == null) maxId else maxOf(previous, maxId)
        return comments
    }

    private fun checkApprovals(pr: PR, currentUserName: String, store: InboxStore,
                                firstSeen: Boolean): List<InboxEvent> {
        val previous = store.inboxState.lastApprovals[pr.id]
        store.inboxState.lastApprovals[pr.id] = pr.reviewers.associate { it.userName to it.approved }.toMutableMap()
        if (firstSeen || previous == null) return emptyList()
        return pr.reviewers.filter {
            !it.userName.sameUserAs(currentUserName) && it.approved && previous[it.userName] != true
        }.map {
            InboxEvent(
                    id = "${pr.id}:approval:${it.userName}",
                    kind = InboxEventKind.APPROVAL,
                    prId = pr.id,
                    prTitle = pr.title,
                    prWebUrl = pr.webUrl,
                    authorDisplayName = it.displayName,
                    snippet = null,
                    timestamp = pr.updatedAt)
        }
    }

    private fun pruneClosedPRs(own: List<PR>, reviewing: List<PR>, store: InboxStore) {
        val trackedIds = (own.map { it.id } + reviewing.map { it.id }).toSet()
        store.inboxState.lastCheckedRevision.keys.retainAll(trackedIds)
        store.inboxState.lastSeenCommentId.keys.retainAll(trackedIds)
        store.inboxState.lastApprovals.keys.retainAll(trackedIds)
        store.inboxState.myCommentIds.keys.retainAll(trackedIds)
    }
}
