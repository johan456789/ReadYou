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
    ): ReadStateReconciliation {
        return ReadStateReconciliation(
            markReadIds = remoteReadIds.intersect(localUnreadIds),
            markUnreadIds = localReadIds.intersect(remoteUnreadIds),
        )
    }
}
