package me.ash.reader.ui.page.home.reading

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderToolbarStateTest {

    // ==================== shouldShowToolbar tests ====================

    @Test
    fun `toolbar visible when auto-hide is disabled`() {
        // Even when scrolling down in the middle, toolbar should show
        val result = ReaderToolbarState.shouldShowToolbar(
            isAutoHideEnabled = false,
            isScrollable = true,
            isAtTop = false,
            isAtBottom = false,
            isScrollingDown = true
        )
        assertTrue("Toolbar should be visible when auto-hide is disabled", result)
    }

    @Test
    fun `toolbar visible when content is not scrollable`() {
        // Short article that doesn't need scrolling - always show toolbar
        val result = ReaderToolbarState.shouldShowToolbar(
            isAutoHideEnabled = true,
            isScrollable = false,
            isAtTop = true,
            isAtBottom = true,
            isScrollingDown = true // Even if trying to scroll down
        )
        assertTrue("Toolbar should be visible for non-scrollable content", result)
    }

    @Test
    fun `toolbar visible when at top of scrollable content`() {
        val result = ReaderToolbarState.shouldShowToolbar(
            isAutoHideEnabled = true,
            isScrollable = true,
            isAtTop = true,
            isAtBottom = false,
            isScrollingDown = true // Even if scrolling down
        )
        assertTrue("Toolbar should be visible at top of content", result)
    }

    @Test
    fun `toolbar visible when at bottom of scrollable content`() {
        val result = ReaderToolbarState.shouldShowToolbar(
            isAutoHideEnabled = true,
            isScrollable = true,
            isAtTop = false,
            isAtBottom = true,
            isScrollingDown = true // Even if scrolling down
        )
        assertTrue("Toolbar should be visible at bottom of content", result)
    }

    @Test
    fun `toolbar hidden when scrolling down in the middle`() {
        val result = ReaderToolbarState.shouldShowToolbar(
            isAutoHideEnabled = true,
            isScrollable = true,
            isAtTop = false,
            isAtBottom = false,
            isScrollingDown = true
        )
        assertFalse("Toolbar should be hidden when scrolling down in the middle", result)
    }

    @Test
    fun `toolbar visible when scrolling up in the middle`() {
        val result = ReaderToolbarState.shouldShowToolbar(
            isAutoHideEnabled = true,
            isScrollable = true,
            isAtTop = false,
            isAtBottom = false,
            isScrollingDown = false
        )
        assertTrue("Toolbar should be visible when scrolling up in the middle", result)
    }

    // ==================== isAtTop tests ====================

    @Test
    fun `isAtTop returns true when scroll position is 0`() {
        assertTrue(ReaderToolbarState.isAtTop(scrollPosition = 0))
    }

    @Test
    fun `isAtTop returns true when scroll position is within threshold`() {
        assertTrue(ReaderToolbarState.isAtTop(scrollPosition = 5, threshold = 10))
        assertTrue(ReaderToolbarState.isAtTop(scrollPosition = 10, threshold = 10))
    }

    @Test
    fun `isAtTop returns false when scroll position exceeds threshold`() {
        assertFalse(ReaderToolbarState.isAtTop(scrollPosition = 15, threshold = 10))
        assertFalse(ReaderToolbarState.isAtTop(scrollPosition = 100, threshold = 10))
    }

    // ==================== isAtBottom tests ====================

    @Test
    fun `isAtBottom returns true when scroll position equals maxScroll`() {
        assertTrue(ReaderToolbarState.isAtBottom(scrollPosition = 1000, maxScroll = 1000))
    }

    @Test
    fun `isAtBottom returns true when scroll position is within threshold of maxScroll`() {
        assertTrue(ReaderToolbarState.isAtBottom(scrollPosition = 995, maxScroll = 1000, threshold = 10))
        assertTrue(ReaderToolbarState.isAtBottom(scrollPosition = 990, maxScroll = 1000, threshold = 10))
    }

    @Test
    fun `isAtBottom returns false when scroll position is far from maxScroll`() {
        assertFalse(ReaderToolbarState.isAtBottom(scrollPosition = 500, maxScroll = 1000, threshold = 10))
        assertFalse(ReaderToolbarState.isAtBottom(scrollPosition = 980, maxScroll = 1000, threshold = 10))
    }

    @Test
    fun `isAtBottom returns true when content is not scrollable`() {
        // maxScroll <= 0 means not scrollable, so always at bottom
        assertTrue(ReaderToolbarState.isAtBottom(scrollPosition = 0, maxScroll = 0))
        assertTrue(ReaderToolbarState.isAtBottom(scrollPosition = 0, maxScroll = -1))
    }

    // ==================== isScrollable tests ====================

    @Test
    fun `isScrollable returns false when maxScroll is 0`() {
        assertFalse(ReaderToolbarState.isScrollable(maxScroll = 0))
    }

    @Test
    fun `isScrollable returns false when maxScroll is negative`() {
        assertFalse(ReaderToolbarState.isScrollable(maxScroll = -1))
    }

    @Test
    fun `isScrollable returns true when maxScroll is positive`() {
        assertTrue(ReaderToolbarState.isScrollable(maxScroll = 1))
        assertTrue(ReaderToolbarState.isScrollable(maxScroll = 1000))
    }

    // ==================== Integration scenario tests ====================

    @Test
    fun `short article scenario - toolbar always visible regardless of scroll attempts`() {
        // Simulate a short article that fits on screen
        val maxScroll = 0
        val isScrollable = ReaderToolbarState.isScrollable(maxScroll)

        // User tries to scroll down
        val showToolbar = ReaderToolbarState.shouldShowToolbar(
            isAutoHideEnabled = true,
            isScrollable = isScrollable,
            isAtTop = true,
            isAtBottom = true,
            isScrollingDown = true
        )
        assertTrue("Short article should always show toolbar", showToolbar)
    }

    @Test
    fun `long article scenario - toolbar hides when scrolling down in middle`() {
        // Simulate a long article
        val maxScroll = 2000
        val scrollPosition = 500 // Somewhere in the middle

        val isScrollable = ReaderToolbarState.isScrollable(maxScroll)
        val isAtTop = ReaderToolbarState.isAtTop(scrollPosition)
        val isAtBottom = ReaderToolbarState.isAtBottom(scrollPosition, maxScroll)

        assertTrue("Long article should be scrollable", isScrollable)
        assertFalse("Position 500 should not be at top", isAtTop)
        assertFalse("Position 500 should not be at bottom", isAtBottom)

        val showToolbar = ReaderToolbarState.shouldShowToolbar(
            isAutoHideEnabled = true,
            isScrollable = isScrollable,
            isAtTop = isAtTop,
            isAtBottom = isAtBottom,
            isScrollingDown = true
        )
        assertFalse("Toolbar should hide when scrolling down in middle", showToolbar)
    }

    @Test
    fun `long article scenario - toolbar shows when scrolling to bottom`() {
        val maxScroll = 2000
        val scrollPosition = 1995 // At bottom

        val isScrollable = ReaderToolbarState.isScrollable(maxScroll)
        val isAtTop = ReaderToolbarState.isAtTop(scrollPosition)
        val isAtBottom = ReaderToolbarState.isAtBottom(scrollPosition, maxScroll)

        assertTrue("Position 1995 should be at bottom", isAtBottom)

        val showToolbar = ReaderToolbarState.shouldShowToolbar(
            isAutoHideEnabled = true,
            isScrollable = isScrollable,
            isAtTop = isAtTop,
            isAtBottom = isAtBottom,
            isScrollingDown = true
        )
        assertTrue("Toolbar should show at bottom", showToolbar)
    }

    @Test
    fun `long article scenario - toolbar shows when scrolling back to top`() {
        val maxScroll = 2000
        val scrollPosition = 5 // At top

        val isScrollable = ReaderToolbarState.isScrollable(maxScroll)
        val isAtTop = ReaderToolbarState.isAtTop(scrollPosition)
        val isAtBottom = ReaderToolbarState.isAtBottom(scrollPosition, maxScroll)

        assertTrue("Position 5 should be at top", isAtTop)

        val showToolbar = ReaderToolbarState.shouldShowToolbar(
            isAutoHideEnabled = true,
            isScrollable = isScrollable,
            isAtTop = isAtTop,
            isAtBottom = isAtBottom,
            isScrollingDown = false
        )
        assertTrue("Toolbar should show at top", showToolbar)
    }
}
