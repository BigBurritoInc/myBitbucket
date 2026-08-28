package ui

import domain.PR

class PRState(private val prsMap: Map<Long, PR> = HashMap()) {
    fun createDiff(prs: List<PR>): Diff {
        val newMap = prs.map { it.id to it }.toMap()
        val removed = prsMap.filterKeys { !newMap.containsKey(it) }
        val added = newMap.filterKeys { !prsMap.containsKey(it) }
        val updated = newMap.filterKeys { prsMap.containsKey(it) && prsMap[it] != newMap[it]
        //It is not clear if any change leads to version change so if versions are equal consider it as a change
                && prsMap.getValue(it).revision <= newMap.getValue(it).revision }
        val mergeStatusChanged = newMap.filter { it.value.mergeStatus.known }
                .filterKeys { prsMap.containsKey(it)
                    && prsMap[it]?.mergeStatus?.canMerge != newMap[it]?.mergeStatus?.canMerge }
        return Diff(added, updated, removed, mergeStatusChanged)
    }

    fun createNew(prs: List<PR>): PRState {
        return PRState(prs.map { it.id to it }.toMap())
    }

    fun update(prs: List<PR>): PRState {
        val newPRsMap = prs.map { it.id to it }.toMap()
        return PRState(
            prsMap.map { if (hasNewerVersion(newPRsMap, it)) { newPRsMap[it.key] } else { it.value } }
                .filterNotNull() // we know no nulls there, but have to explain to Kotlin compiler explicitly
                .map { it.id to it }.toMap()
        )
    }

    private fun hasNewerVersion(newPRsMap: Map<Long, PR>, entry: Map.Entry<Long, PR>) =
            (newPRsMap.containsKey(entry.key) && (entry.value.revision < newPRsMap.getValue(entry.key).revision))

    fun size() = prsMap.size
}