package domain

/**
 * Whether a pull request can be merged right now.
 *
 * [known] is false until the answer has actually been fetched — a freshly parsed pull request has
 * no merge status, and reporting that as "cannot merge" would grey out the Merge button and fire a
 * spurious "can be merged" notification the moment the real answer arrived. PRState's diffing skips
 * unknown statuses for exactly that reason.
 */
data class MergeStatus(
        val canMerge: Boolean,
        val conflicted: Boolean,
        val vetoes: List<Veto>,
        val known: Boolean
) {
    fun vetoesSummaries(): String = vetoes.joinToString { it.summary }

    companion object {
        val UNKNOWN = MergeStatus(canMerge = false, conflicted = false, vetoes = emptyList(), known = false)
    }
}

data class Veto(val summary: String, val detail: String)
