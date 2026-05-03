package me.ash.reader.ui.page.home.reading

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MetadataTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun titleClickOpensArticleLink() {
        val title = "Example article"
        val link = "https://example.com/article"
        var openedLink: String? = null

        composeRule.setContent {
            MaterialTheme {
                Metadata(
                    feedName = "Example feed",
                    title = title,
                    publishedDate = Date(0),
                    link = link,
                    onTitleClick = { openedLink = it },
                )
            }
        }

        composeRule.onNodeWithText(title)
            .assertHasClickAction()
            .performClick()

        assertEquals(link, openedLink)
    }
}
