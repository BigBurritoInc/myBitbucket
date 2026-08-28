package bitbucket

import domain.MergeStatus
import domain.PR
import util.LOG

/**
 * Remembers each pull request's merge status between polls, so the poll doesn't ask the server for
 * all of them every cycle — that loop, not the pull request list itself, was the bulk of the
 * plugin's request volume. See CLAUDE.md "Fetching pull requests".
 *
 * A status is re-fetched only when the pull request's [PR.revision] has increased. Both backends
 * move that on every change to the pull request itself, so anything the user does to it refreshes
 * the status; a steady repository costs nothing at all.
 *
 * The deliberate gap: merge status also changes when the **target branch** moves, which doesn't
 * touch the pull request and so doesn't bump its version. That can leave a "can be merged" answer
 * stale until the pull request changes for some other reason. Trading that for the request budget
 * is the point — Bitbucket Cloud's hourly ceiling doesn't leave room for polling it speculatively.
 *
 * Not thread-safe: one instance belongs to one UpdateTask, which runs one cycle at a time.
 */
class MergeStatusCache {

    companion object {
        // Ceiling on how many statuses one cycle may fetch, so the first poll against a repository
        // with hundreds of open PRs doesn't turn into hundreds of requests. Whatever doesn't fit
        // stays uncached and is picked up by the next cycle.
        private const val MAX_FETCHES_PER_CYCLE = 20
    }

    private data class Entry(val revision: Long, val status: MergeStatus)

    private val byPrId = HashMap<Long, Entry>()

    /**
     * Fills in [PR.mergeStatus] for every pull request in [prs], fetching only the ones that need it.
     *
     * Assigning the cached status back is not an optimisation — each poll parses brand new PR
     * objects whose merge status is `unknown`, and Diff.mergeStatusChanged ignores unknown ones, so
     * skipping it would silently stop the "your pull request can be merged" notification.
     */
    fun applyTo(prs: List<PR>, fetch: (PR) -> MergeStatus) {
        byPrId.keys.retainAll(prs.map { it.id }.toSet())
        var fetched = 0
        for (pr in prs) {
            val cached = byPrId[pr.id]
            if (cached != null && pr.revision <= cached.revision) {
                pr.mergeStatus = cached.status
                continue
            }
            if (fetched >= MAX_FETCHES_PER_CYCLE) {
                // Keep showing the previous answer rather than nothing; it'll refresh next cycle.
                cached?.let { pr.mergeStatus = it.status }
                continue
            }
            fetched++
            val status = fetch(pr)
            pr.mergeStatus = status
            byPrId[pr.id] = Entry(pr.revision, status)
        }
        LOG.debug("Merge status: ${prs.size} PR(s), $fetched fetched, ${prs.size - fetched} from cache")
    }
}
