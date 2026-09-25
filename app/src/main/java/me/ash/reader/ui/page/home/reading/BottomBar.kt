package me.ash.reader.ui.page.home.reading

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.LocalReadingPageTonalElevation
import me.ash.reader.infrastructure.preference.ReadingPageTonalElevationPreference
import me.ash.reader.ui.component.base.CanBeDisabledIconButton

@Composable
fun BottomBar(
    articleId: String?,
    isRead: Boolean,
    isStarred: Boolean,
    actionsEnabled: Boolean = true,
    isNextArticleAvailable: Boolean,
    isFullContent: Boolean,
    ttsButton: @Composable () -> Unit,
    onRead: (articleId: String, markRead: Boolean) -> Unit = { _, _ -> },
    onStarred: (articleId: String, isStarred: Boolean) -> Unit = { _, _ -> },
    onNextArticle: () -> Unit = {},
    onFullContent: (isFullContent: Boolean) -> Unit = {},
) {
    val tonalElevation = LocalReadingPageTonalElevation.current
    val isOutlined = tonalElevation == ReadingPageTonalElevationPreference.Outlined
    val view = LocalView.current

    Column {
        if (isOutlined) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                thickness = 0.5f.dp
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.run { if (isOutlined) surface else surfaceContainer }
        ) {
            // TODO: Component styles await refactoring
            Row(
                modifier = Modifier
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .height(60.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                        CanBeDisabledIconButton(
                            modifier = Modifier.size(40.dp),
                            disabled = !actionsEnabled,
                            imageVector = if (isRead) {
                                Icons.Outlined.FiberManualRecord
                            } else {
                                Icons.Filled.FiberManualRecord
                            },
                            contentDescription = stringResource(if (isRead) R.string.mark_as_unread else R.string.mark_as_read),
                            tint = if (isRead) {
                                MaterialTheme.colorScheme.outline
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                        ) {
                            articleId?.let {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onRead(it, !isRead)
                            }
                        }
                        CanBeDisabledIconButton(
                            modifier = Modifier.size(40.dp),
                            disabled = !actionsEnabled,
                            imageVector = if (isStarred) {
                                Icons.Rounded.Star
                            } else {
                                Icons.Rounded.StarOutline
                            },
                            contentDescription = stringResource(if (isStarred) R.string.mark_as_unstar else R.string.mark_as_starred),
                            tint = if (isStarred) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        ) {
                            articleId?.let {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onStarred(it, !isStarred)
                            }
                        }
                        CanBeDisabledIconButton(
                            disabled = !isNextArticleAvailable,
                            modifier = Modifier.size(40.dp),
                            imageVector = Icons.Rounded.ExpandMore,
                            contentDescription = "Next Article",
                            tint = MaterialTheme.colorScheme.outline,
                        ) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onNextArticle()
                        }
                        ttsButton()
                        CanBeDisabledIconButton(
                            disabled = false,
                            modifier = Modifier.size(40.dp),
                            imageVector = if (isFullContent) {
                                Icons.AutoMirrored.Rounded.Article
                            } else {
                                Icons.AutoMirrored.Outlined.Article
                            },
                            contentDescription = stringResource(R.string.parse_full_content),
                            tint = if (isFullContent) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        ) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onFullContent(!isFullContent)
                        }
            }
        }
    }
}
