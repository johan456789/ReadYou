package me.ash.reader.ui.page.home.reading

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
class ArticleSwipePagerTest {

    @Test
    fun `half swipe does not settle`() {
        val direction =
            resolveArticleSwipeSettleDirection(
                dragOffset = -95f,
                threshold = 96f,
                layoutDirection = LayoutDirection.Ltr,
                canLoadPrevious = true,
                canLoadNext = true,
            )

        assertNull(direction)
    }

    @Test
    fun `ltr swipe left settles to next article`() {
        val direction =
            resolveArticleSwipeSettleDirection(
                dragOffset = -120f,
                threshold = 96f,
                layoutDirection = LayoutDirection.Ltr,
                canLoadPrevious = true,
                canLoadNext = true,
            )

        assertEquals(ArticleSwipeDirection.Next, direction)
    }

    @Test
    fun `ltr swipe right settles to previous article`() {
        val direction =
            resolveArticleSwipeSettleDirection(
                dragOffset = 120f,
                threshold = 96f,
                layoutDirection = LayoutDirection.Ltr,
                canLoadPrevious = true,
                canLoadNext = true,
            )

        assertEquals(ArticleSwipeDirection.Previous, direction)
    }

    @Test
    fun `rtl reverses article direction`() {
        val direction =
            resolveArticleSwipeSettleDirection(
                dragOffset = -120f,
                threshold = 96f,
                layoutDirection = LayoutDirection.Rtl,
                canLoadPrevious = true,
                canLoadNext = true,
            )

        assertEquals(ArticleSwipeDirection.Previous, direction)
    }

    @Test
    fun `unavailable target does not settle`() {
        val direction =
            resolveArticleSwipeSettleDirection(
                dragOffset = -120f,
                threshold = 96f,
                layoutDirection = LayoutDirection.Ltr,
                canLoadPrevious = true,
                canLoadNext = false,
            )

        assertNull(direction)
    }

    @Test
    fun `ltr places previous left and next right`() {
        assertEquals(
            -300f,
            articleSwipePageOffset(
                direction = ArticleSwipeDirection.Previous,
                widthPx = 300f,
                layoutDirection = LayoutDirection.Ltr,
            ),
        )
        assertEquals(
            300f,
            articleSwipePageOffset(
                direction = ArticleSwipeDirection.Next,
                widthPx = 300f,
                layoutDirection = LayoutDirection.Ltr,
            ),
        )
    }

    @Test
    fun `rtl places previous right and next left`() {
        assertEquals(
            300f,
            articleSwipePageOffset(
                direction = ArticleSwipeDirection.Previous,
                widthPx = 300f,
                layoutDirection = LayoutDirection.Rtl,
            ),
        )
        assertEquals(
            -300f,
            articleSwipePageOffset(
                direction = ArticleSwipeDirection.Next,
                widthPx = 300f,
                layoutDirection = LayoutDirection.Rtl,
            ),
        )
    }

    @Test
    fun `settle offset mirrors page offset`() {
        assertEquals(
            -300f,
            articleSwipeSettleOffset(
                direction = ArticleSwipeDirection.Previous,
                widthPx = 300f,
                layoutDirection = LayoutDirection.Rtl,
            ),
        )
        assertEquals(
            300f,
            articleSwipeSettleOffset(
                direction = ArticleSwipeDirection.Next,
                widthPx = 300f,
                layoutDirection = LayoutDirection.Rtl,
            ),
        )
    }

    @Test
    fun `headline measurement accepted for matching article`() {
        assertEquals(
            600,
            resolveSlotHeadlineHeightPx(
                slotArticleId = "article-a",
                measuredArticleId = "article-a",
                measuredPx = 600,
            ),
        )
    }

    @Test
    fun `headline measurement rejected when slot moved on`() {
        assertNull(
            resolveSlotHeadlineHeightPx(
                slotArticleId = "article-b",
                measuredArticleId = "article-a",
                measuredPx = 600,
            ),
        )
    }

    @Test
    fun `headline measurement rejected without slot article`() {
        assertNull(
            resolveSlotHeadlineHeightPx(
                slotArticleId = null,
                measuredArticleId = "article-a",
                measuredPx = 600,
            ),
        )
    }

    @Test
    fun `non positive headline measurement rejected`() {
        assertNull(
            resolveSlotHeadlineHeightPx(
                slotArticleId = "article-a",
                measuredArticleId = "article-a",
                measuredPx = 0,
            ),
        )
    }

    @Test
    fun `drag direction is null at rest`() {
        assertNull(articleSwipeDragDirection(dragOffset = 0f, layoutDirection = LayoutDirection.Ltr))
    }

    @Test
    fun `ltr drag left pulls in next article`() {
        assertEquals(
            ArticleSwipeDirection.Next,
            articleSwipeDragDirection(dragOffset = -120f, layoutDirection = LayoutDirection.Ltr),
        )
    }

    @Test
    fun `ltr drag right pulls in previous article`() {
        assertEquals(
            ArticleSwipeDirection.Previous,
            articleSwipeDragDirection(dragOffset = 120f, layoutDirection = LayoutDirection.Ltr),
        )
    }

    @Test
    fun `rtl drag reverses pull direction`() {
        assertEquals(
            ArticleSwipeDirection.Previous,
            articleSwipeDragDirection(dragOffset = -120f, layoutDirection = LayoutDirection.Rtl),
        )
        assertEquals(
            ArticleSwipeDirection.Next,
            articleSwipeDragDirection(dragOffset = 120f, layoutDirection = LayoutDirection.Rtl),
        )
    }

    @Test
    fun `settle to next pauses outgoing and stale slots, resumes incoming`() {
        val update =
            resolveArticleSwipeMediaUpdate(
                oldCurrentSlotIndex = 0,
                newCurrentSlotIndex = 2,
                slotIndices = listOf(0, 1, 2),
            )

        assertEquals(2, update.resumeSlotIndex)
        assertEquals(listOf(0, 1), update.pauseSlotIndices)
    }

    @Test
    fun `settle to previous pauses outgoing and stale slots, resumes incoming`() {
        val update =
            resolveArticleSwipeMediaUpdate(
                oldCurrentSlotIndex = 0,
                newCurrentSlotIndex = 1,
                slotIndices = listOf(0, 1, 2),
            )

        assertEquals(1, update.resumeSlotIndex)
        assertEquals(listOf(0, 2), update.pauseSlotIndices)
    }

    @Test
    fun `outgoing slot is always among paused slots`() {
        listOf(1, 2).forEach { newCurrent ->
            val update =
                resolveArticleSwipeMediaUpdate(
                    oldCurrentSlotIndex = 0,
                    newCurrentSlotIndex = newCurrent,
                    slotIndices = listOf(0, 1, 2),
                )

            assertEquals(newCurrent, update.resumeSlotIndex)
            assert(update.pauseSlotIndices.contains(0))
            assert(!update.pauseSlotIndices.contains(newCurrent))
        }
    }
}
