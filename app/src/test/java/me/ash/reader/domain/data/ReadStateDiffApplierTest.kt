package me.ash.reader.domain.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadStateDiffApplierTest {
    @Test
    fun buildsReadAndUnreadBatchesFromDiffs() {
        val diffs =
            mapOf(
                "mark-read" to Diff(isRead = true, articleId = "mark-read", feedId = "feed"),
                "mark-unread" to Diff(isRead = false, articleId = "mark-unread", feedId = "feed"),
            )

        val batch = ReadStateDiffApplier.toBatch(diffs)

        assertEquals(setOf("mark-read"), batch.markReadIds)
        assertEquals(setOf("mark-unread"), batch.markUnreadIds)
    }

    @Test
    fun removesOnlyMatchingCommittedDiffs() {
        val committedReadDiff = Diff(isRead = true, articleId = "article", feedId = "feed")
        val newerUnreadDiff = Diff(isRead = false, articleId = "article", feedId = "feed")
        val currentDiffs = mutableMapOf("article" to newerUnreadDiff)

        ReadStateDiffApplier.removeMatchingDiffs(
            currentDiffs = currentDiffs,
            appliedDiffs = mapOf("article" to committedReadDiff),
        )

        assertEquals(mapOf("article" to newerUnreadDiff), currentDiffs)
    }
}
