package me.ash.reader.ui.page.home.reading

/**
 * Determines toolbar visibility based on scroll state.
 *
 * Rules:
 * 1. If content is not scrollable (maxScroll == 0), always show toolbar
 * 2. If content is scrollable:
 *    - Show toolbar when at top (scrollPosition == 0)
 *    - Show toolbar when at bottom (scrollPosition >= maxScroll)
 *    - Hide toolbar when scrolling down in the middle
 *    - Show toolbar when scrolling up in the middle
 * 3. If auto-hide is disabled, always show toolbar
 */
object ReaderToolbarState {

    /**
     * Determines if the toolbar should be visible.
     *
     * @param isAutoHideEnabled Whether the auto-hide toolbar preference is enabled
     * @param isScrollable Whether the content is scrollable (maxScroll > 0)
     * @param isAtTop Whether scroll position is at the top (scrollPosition == 0 or close to it)
     * @param isAtBottom Whether scroll position is at the bottom (scrollPosition >= maxScroll)
     * @param isScrollingDown Whether user is currently scrolling down (negative y delta)
     * @return true if toolbar should be visible
     */
    fun shouldShowToolbar(
        isAutoHideEnabled: Boolean,
        isScrollable: Boolean,
        isAtTop: Boolean,
        isAtBottom: Boolean,
        isScrollingDown: Boolean
    ): Boolean {
        // If auto-hide is disabled, always show
        if (!isAutoHideEnabled) return true

        // If content is not scrollable, always show
        if (!isScrollable) return true

        // If at top or bottom, always show
        if (isAtTop || isAtBottom) return true

        // In the middle: hide when scrolling down, show when scrolling up
        return !isScrollingDown
    }

    /**
     * Checks if scroll position is at the top.
     * Uses a small threshold to account for minor scroll offsets.
     */
    fun isAtTop(scrollPosition: Int, threshold: Int = 10): Boolean {
        return scrollPosition <= threshold
    }

    /**
     * Checks if scroll position is at the bottom.
     * Uses a small threshold to account for minor scroll offsets.
     */
    fun isAtBottom(scrollPosition: Int, maxScroll: Int, threshold: Int = 10): Boolean {
        if (maxScroll <= 0) return true // Not scrollable = always at top/bottom
        return scrollPosition >= maxScroll - threshold
    }

    /**
     * Checks if the content is scrollable.
     */
    fun isScrollable(maxScroll: Int): Boolean {
        return maxScroll > 0
    }
}
