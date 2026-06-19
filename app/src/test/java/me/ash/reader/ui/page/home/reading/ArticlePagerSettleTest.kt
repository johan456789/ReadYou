package me.ash.reader.ui.page.home.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArticlePagerSettleTest {

    @Test
    fun `halfway canceled swipe does not settle to another article`() {
        assertNull(
            settleArticleSwipeTarget(
                offsetPx = 199f,
                pageWidthPx = 800f,
                thresholdFraction = 0.25f,
            )
        )
    }

    @Test
    fun `swipe past threshold settles by drag direction`() {
        assertEquals(
            ArticleSwipeSettleTarget.Positive,
            settleArticleSwipeTarget(
                offsetPx = 200f,
                pageWidthPx = 800f,
                thresholdFraction = 0.25f,
            )
        )
        assertEquals(
            ArticleSwipeSettleTarget.Negative,
            settleArticleSwipeTarget(
                offsetPx = -200f,
                pageWidthPx = 800f,
                thresholdFraction = 0.25f,
            )
        )
    }
}
