package me.ash.reader.ui.page.home.reading

import androidx.compose.ui.unit.LayoutDirection
import me.ash.reader.ui.page.adaptive.ReaderState
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
    fun `swipe target requires article id and list index`() {
        assertNull(ReaderState(articleId = "article-a").toArticleSwipeTarget())
        assertNull(ReaderState(listIndex = 0).toArticleSwipeTarget())

        assertEquals(
            ReaderState.PrefetchResult(articleId = "article-a", index = 12),
            ReaderState(articleId = "article-a", listIndex = 12).toArticleSwipeTarget(),
        )
    }
}
