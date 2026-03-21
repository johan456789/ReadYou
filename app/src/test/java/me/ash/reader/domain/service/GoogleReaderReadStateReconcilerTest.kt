package me.ash.reader.domain.service

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleReaderReadStateReconcilerTest {
    @Test
    fun marksLocallyUnreadItemsAsReadWhenServerNoLongerListsThemAsUnread() {
        val reconciliation =
            GoogleReaderReadStateReconciler.reconcile(
                localUnreadIds = setOf("old-article"),
                localReadIds = emptySet(),
                remoteUnreadIds = emptySet(),
                remoteReadIds = emptySet(),
            )

        assertEquals(setOf("old-article"), reconciliation.markReadIds)
        assertEquals(emptySet<String>(), reconciliation.markUnreadIds)
    }

    @Test
    fun marksLocallyReadItemsAsUnreadWhenServerListsThemAsUnread() {
        val reconciliation =
            GoogleReaderReadStateReconciler.reconcile(
                localUnreadIds = emptySet(),
                localReadIds = setOf("server-unread"),
                remoteUnreadIds = setOf("server-unread"),
                remoteReadIds = emptySet(),
            )

        assertEquals(emptySet<String>(), reconciliation.markReadIds)
        assertEquals(setOf("server-unread"), reconciliation.markUnreadIds)
    }
}
