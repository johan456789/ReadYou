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
    fun `axis undecided while both axes stay under touch slop`() {
        assertNull(resolveArticleSwipeAxis(totalDx = 10f, totalDy = 10f, touchSlop = 24f))
    }

    @Test
    fun `horizontal axis locked when horizontal passes slop alone`() {
        assertEquals(
            ArticleSwipeAxis.Horizontal,
            resolveArticleSwipeAxis(totalDx = 30f, totalDy = 8f, touchSlop = 24f),
        )
    }

    @Test
    fun `vertical axis locked when vertical passes slop alone`() {
        assertEquals(
            ArticleSwipeAxis.Vertical,
            resolveArticleSwipeAxis(totalDx = 8f, totalDy = 30f, touchSlop = 24f),
        )
    }

    @Test
    fun `l shaped scroll locks vertical at the first slop crossing`() {
        // Vertical first (the WebView already scrolled): at the moment the
        // first axis passes the slop the decision is vertical, and the gesture
        // loop never re-asks - so a later sideways curve cannot win the
        // gesture back for the article swipe.
        assertEquals(
            ArticleSwipeAxis.Vertical,
            resolveArticleSwipeAxis(totalDx = 28f, totalDy = 240f, touchSlop = 24f),
        )
    }

    @Test
    fun `perfect diagonal locks vertical like the webview`() {
        // The WebView treats dx == dy as a vertical gesture (dx > dy is false),
        // so the swipe must agree.
        assertEquals(
            ArticleSwipeAxis.Vertical,
            resolveArticleSwipeAxis(totalDx = 30f, totalDy = 30f, touchSlop = 24f),
        )
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

    @Test
    fun `axis lock stays undecided while both axes stay under slop`() {
        val lock = SwipeAxisLock(touchSlop = 24f)
        assertNull(lock.feed(deltaX = 10f, deltaY = 10f))
        assertNull(lock.decidedAxis)
    }

    @Test
    fun `axis lock decides the first time an axis passes slop`() {
        val lock = SwipeAxisLock(touchSlop = 24f)
        assertNull(lock.feed(deltaX = 2f, deltaY = -24f)) // |dy| == slop is not past it
        assertEquals(ArticleSwipeAxis.Vertical, lock.feed(deltaX = 2f, deltaY = -24f))
        assertEquals(4f, lock.totalDx, 0.001f)
        assertEquals(-48f, lock.totalDy, 0.001f)
    }

    @Test
    fun `vertical lock is final under an adversarial horizontal sweep`() {
        val lock = SwipeAxisLock(touchSlop = 24f)
        // The WebView took the gesture: a vertical phase.
        assertNull(lock.feed(deltaX = 2f, deltaY = -24f))
        repeat(11) { assertEquals(ArticleSwipeAxis.Vertical, lock.feed(deltaX = 2f, deltaY = -24f)) }
        // The L-curve afterwards: every later movement must still report
        // vertical. A per-move re-evaluation would flip to horizontal here -
        // that is exactly the regression shape this lock exists to prevent.
        repeat(20) { assertEquals(ArticleSwipeAxis.Vertical, lock.feed(deltaX = -40f, deltaY = -2f)) }
        assertEquals(-776f, lock.totalDx, 0.001f)
        assertEquals(-328f, lock.totalDy, 0.001f)
    }

    @Test
    fun `horizontal lock is final under vertical drift`() {
        val lock = SwipeAxisLock(touchSlop = 24f)
        assertNull(lock.feed(deltaX = -24f, deltaY = 0f))
        assertEquals(ArticleSwipeAxis.Horizontal, lock.feed(deltaX = -24f, deltaY = 2f))
        repeat(16) { assertEquals(ArticleSwipeAxis.Horizontal, lock.feed(deltaX = -2f, deltaY = 24f)) }
    }

    @Test
    fun `no movement sequence ever flips the decided axis`() {
        // Monotonicity property: whatever the finger does after the lock, the
        // decided axis stays put for the rest of the gesture.
        val random = kotlin.random.Random(seed = 20261109)
        repeat(100) {
            val lock = SwipeAxisLock(touchSlop = 24f)
            var decided: ArticleSwipeAxis? = null
            repeat(200) {
                val axis =
                    lock.feed(
                        deltaX = random.nextFloat() * 80f - 40f,
                        deltaY = random.nextFloat() * 80f - 40f,
                    )
                if (decided == null) {
                    decided = axis
                } else {
                    assertEquals(decided, axis)
                }
            }
        }
    }

    private fun release(dragOffset: Float, gestureDx: Float) =
        resolveArticleSwipeReleaseDirection(
            dragOffset = dragOffset,
            gestureDx = gestureDx,
            threshold = 96f,
            layoutDirection = LayoutDirection.Ltr,
            canLoadPrevious = true,
            canLoadNext = true,
        )

    @Test
    fun `drag from rest tracks the gesture`() {
        assertEquals(
            -120f,
            articleSwipeDragOffset(caughtOffset = 0f, gestureDx = -120f, widthPx = 720f),
            0.001f,
        )
    }

    @Test
    fun `drag catching a settle continues from the caught offset`() {
        // Pages were 500px short of rest when the finger took hold.
        assertEquals(
            300f,
            articleSwipeDragOffset(caughtOffset = 500f, gestureDx = -200f, widthPx = 720f),
            0.001f,
        )
    }

    @Test
    fun `drag catching the tail of a settle is worth the same as one from rest`() {
        // A late first event must not eat into the swipe distance just because
        // the pages were still a few pixels short of rest.
        assertEquals(
            329f,
            articleSwipeDragOffset(caughtOffset = 3f, gestureDx = 326f, widthPx = 720f),
            0.001f,
        )
    }

    @Test
    fun `drag never moves past the neighboring page`() {
        assertEquals(
            720f,
            articleSwipeDragOffset(caughtOffset = 600f, gestureDx = 400f, widthPx = 720f),
            0.001f,
        )
        assertEquals(
            -720f,
            articleSwipeDragOffset(caughtOffset = 0f, gestureDx = -900f, widthPx = 720f),
            0.001f,
        )
    }

    @Test
    fun `release from rest behaves like the settle decision`() {
        assertEquals(ArticleSwipeDirection.Next, release(dragOffset = -120f, gestureDx = -120f))
        assertEquals(ArticleSwipeDirection.Previous, release(dragOffset = 120f, gestureDx = 120f))
        assertNull(release(dragOffset = -95f, gestureDx = -95f))
    }

    @Test
    fun `swiping back while the previous swipe still settles returns to the old article`() {
        // Just swiped to the next article: it is current and still 600px from
        // rest, the old article showing on the previous side. Swiping back
        // right away pulls the old article in further (the 200px drag runs
        // into the page-width clamp at 720px) and switches to it.
        assertEquals(ArticleSwipeDirection.Previous, release(dragOffset = 720f, gestureDx = 200f))
    }

    @Test
    fun `second swipe in the same direction carries on to the following article`() {
        // Caught 100px before rest, dragged 400px further the same way.
        assertEquals(ArticleSwipeDirection.Next, release(dragOffset = -300f, gestureDx = -400f))
    }

    @Test
    fun `leftover settle offset alone never switches article`() {
        // The pages are still far from rest, but the finger barely moved.
        assertNull(release(dragOffset = 600f, gestureDx = 30f))
    }

    @Test
    fun `swipe that only slows the settle down does not send the reader back`() {
        // Swiping on towards the next article, released while the old article
        // is still showing on the previous side.
        assertNull(release(dragOffset = 300f, gestureDx = -300f))
    }

    @Test
    fun `release direction respects unavailable neighbors`() {
        assertNull(
            resolveArticleSwipeReleaseDirection(
                dragOffset = -300f,
                gestureDx = -300f,
                threshold = 96f,
                layoutDirection = LayoutDirection.Ltr,
                canLoadPrevious = true,
                canLoadNext = false,
            )
        )
    }

    @Test
    fun `swipe commits once the reader state reaches its article`() {
        assertEquals(
            ArticleSwipeCommitStatus.Committed,
            resolveArticleSwipeCommitStatus(
                readerArticleId = "b",
                pendingArticleId = "b",
                pendingBaseArticleId = "a",
            ),
        )
    }

    @Test
    fun `swipe waits while the reader state still holds the article it started from`() {
        assertEquals(
            ArticleSwipeCommitStatus.Waiting,
            resolveArticleSwipeCommitStatus(
                readerArticleId = "a",
                pendingArticleId = "c",
                pendingBaseArticleId = "a",
            ),
        )
    }

    @Test
    fun `swipe back onto the article the reader state never left commits at once`() {
        assertEquals(
            ArticleSwipeCommitStatus.Committed,
            resolveArticleSwipeCommitStatus(
                readerArticleId = "a",
                pendingArticleId = "a",
                pendingBaseArticleId = "a",
            ),
        )
    }

    @Test
    fun `navigation elsewhere supersedes a pending swipe`() {
        assertEquals(
            ArticleSwipeCommitStatus.Superseded,
            resolveArticleSwipeCommitStatus(
                readerArticleId = "x",
                pendingArticleId = "b",
                pendingBaseArticleId = "a",
            ),
        )
    }
}
