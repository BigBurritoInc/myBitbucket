package domain

/** Whether a PR can be merged right now. [known] stays false until actually fetched, so a fresh
 * PR isn't read as "cannot merge". docs: Fetching pull requests */
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
