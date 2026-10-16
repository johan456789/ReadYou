package me.ash.reader.ui.component.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageRefererRetryTest {

    @Test
    fun unmarkStripsMarkerAddedAsFirstOrLaterParam() {
        assertEquals(
            "https://a.example/x.jpg",
            ImageRefererRetry.unmark("https://a.example/x.jpg?${ImageRefererRetry.MARKER}"),
        )
        assertEquals(
            "https://a.example/x.jpg?w=1",
            ImageRefererRetry.unmark("https://a.example/x.jpg?w=1&${ImageRefererRetry.MARKER}"),
        )
    }

    @Test
    fun unmarkIgnoresUnmarkedUrls() {
        assertNull(ImageRefererRetry.unmark("https://a.example/x.jpg"))
        assertNull(ImageRefererRetry.unmark("https://a.example/x.jpg?${ImageRefererRetry.MARKER}&w=1"))
        assertNull(ImageRefererRetry.unmark("https://a.example/x${ImageRefererRetry.MARKER}"))
    }

    @Test
    fun remembersAndForgetsHosts() {
        val host = "remember.test"
        assertFalse(ImageRefererRetry.isKnown(host))
        ImageRefererRetry.remember(host)
        assertTrue(ImageRefererRetry.isKnown(host))
        ImageRefererRetry.forget(host)
        assertFalse(ImageRefererRetry.isKnown(host))
    }
}
