package me.ash.reader.ui.page.home.reading

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleVisibilityTest {

    @Test
    fun `divider hidden at top`() {
        assertFalse(shouldShowTopDivider(isAtTop = true))
    }

    @Test
    fun `divider shown once scrolled`() {
        assertTrue(shouldShowTopDivider(isAtTop = false))
    }

    @Test
    fun `title hidden before headline measured`() {
        assertFalse(shouldShowTitleInTopBar(scrollY = 5000, headlineHeightPx = 0))
    }

    @Test
    fun `title hidden while headline visible`() {
        assertFalse(shouldShowTitleInTopBar(scrollY = 100, headlineHeightPx = 600))
    }

    @Test
    fun `title shown once headline occluded`() {
        assertTrue(shouldShowTitleInTopBar(scrollY = 600, headlineHeightPx = 600))
        assertTrue(shouldShowTitleInTopBar(scrollY = 5000, headlineHeightPx = 600))
    }
}
