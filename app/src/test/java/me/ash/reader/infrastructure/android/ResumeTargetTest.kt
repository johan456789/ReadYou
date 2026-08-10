package me.ash.reader.infrastructure.android

import me.ash.reader.ui.page.nav3.key.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumeTargetTest {

    @Test
    fun `null when the top route is not reading`() {
        assertNull(resumeTarget(top = Route.Feeds, currentArticleId = "a"))
        assertNull(resumeTarget(top = Route.Settings, currentArticleId = null))
        assertNull(resumeTarget(top = null, currentArticleId = "a"))
    }

    @Test
    fun `resumes the durable current article`() {
        assertEquals("a", resumeTarget(top = Route.Reading(null), currentArticleId = "a"))
    }

    @Test
    fun `falls back to the article id restored in the back stack route`() {
        assertEquals("a", resumeTarget(top = Route.Reading("a"), currentArticleId = null))
    }

    @Test
    fun `durable current article wins over the route article id`() {
        assertEquals("b", resumeTarget(top = Route.Reading("a"), currentArticleId = "b"))
    }

    @Test
    fun `null when reading route has no article and nothing is durable`() {
        assertNull(resumeTarget(top = Route.Reading(null), currentArticleId = null))
    }
}
