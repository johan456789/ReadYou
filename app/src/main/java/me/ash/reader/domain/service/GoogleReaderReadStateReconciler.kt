package me.ash.reader.domain.service

internal data class ReadStateReconciliation(
    val markReadIds: Set<String>,
    val markUnreadIds: Set<String>,
)

internal object GoogleReaderReadStateReconciler {
    fun reconcile(
        localUnreadIds: Set<String>,
        localReadIds: Set<String>,
        remoteUnreadIds: Set<String>,
        remoteReadIds: Set<String>,
        excludedIds: Set<String> = emptySet(),
    ): ReadStateReconciliation {
        return ReadStateReconciliation(
            // Anything we still think is unread locally but no longer appears in the
            // server unread snapshot should be marked read locally.
            markReadIds = (localUnreadIds - remoteUnreadIds) - excludedIds,
            markUnreadIds = localReadIds.intersect(remoteUnreadIds) - excludedIds,
        )
    }
}
