package bitbucket

import domain.MergeStatus
import domain.PR
import util.LOG

/** Caches each PR's merge status between polls. Not thread-safe: one instance belongs to one
 * UpdateTask.
 * docs: Fetching pull requests */
class MergeStatusCache {

    companion object {
        // Ceiling on how many statuses one cycle may fetch, so the first poll against a repository
        // with hundreds of open PRs doesn't turn into hundreds of requests. Whatever doesn't fit
        // stays uncached and is picked up by the next cycle.
        private const val MAX_FETCHES_PER_CYCLE = 20
    }

    private data class Entry(val revision: Long, val status: MergeStatus)

    private val byPrId = HashMap<Long, Entry>()

    /** Fills in [PR.mergeStatus] for every [prs], fetching only what needs it. Writing the cached
     * status back is not optional.
     * docs: Fetching pull requests */
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
