package me.ash.reader.domain.data

internal data class ReadStateDiffBatch(
    val markReadIds: Set<String>,
    val markUnreadIds: Set<String>,
)

internal object ReadStateDiffApplier {
    fun toBatch(diffs: Map<String, Diff>): ReadStateDiffBatch =
        ReadStateDiffBatch(
            markReadIds = diffs.filter { it.value.isRead }.keys,
            markUnreadIds = diffs.filter { !it.value.isRead }.keys,
        )

    fun removeMatchingDiffs(
        currentDiffs: MutableMap<String, Diff>,
        appliedDiffs: Map<String, Diff>,
    ) {
        appliedDiffs.forEach { (articleId, appliedDiff) ->
            if (currentDiffs[articleId] == appliedDiff) {
                currentDiffs.remove(articleId)
            }
        }
    }
}
