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
}
