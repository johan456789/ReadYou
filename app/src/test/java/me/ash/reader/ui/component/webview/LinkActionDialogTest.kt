package me.ash.reader.ui.component.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkActionDialogTest {

    @Test
    fun `shouldShowClipboardToast returns true before Android 13`() {
        assertTrue(shouldShowClipboardToast(32))
    }

    @Test
    fun `shouldShowClipboardToast returns false on Android 13`() {
        assertFalse(shouldShowClipboardToast(33))
    }

    @Test
    fun `shouldShowClipboardToast returns false after Android 13`() {
        assertFalse(shouldShowClipboardToast(34))
    }
}
