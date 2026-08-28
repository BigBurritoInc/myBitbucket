package ui

import domain.PR

data class Diff(
    val added: Map<Long, PR>,
    val updated: Map<Long, PR>,
    val removed: Map<Long, PR>,
    val mergeStatusChanged: Map<Long, PR> = emptyMap()
) {
    fun hasAnyUpdates(): Boolean {
        return added.isNotEmpty() || updated.isNotEmpty() || removed.isNotEmpty()
    }
}