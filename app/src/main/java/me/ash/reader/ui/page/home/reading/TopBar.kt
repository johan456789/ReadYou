package me.ash.reader.ui.page.home.reading

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MenuOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.LocalReadingPageTonalElevation
import me.ash.reader.infrastructure.preference.LocalSharedContent
import me.ash.reader.infrastructure.preference.ReadingPageTonalElevationPreference
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.page.adaptive.NavigationAction

/**
 * One title drawn in the top bar. During a horizontal swipe the bar renders the
 * outgoing and incoming layers at complementary alphas, so the title crossfades
 * in step with the pages instead of lingering behind a fixed-duration animation.
 */
data class TopBarTitleLayer(
    val text: String,
    val alpha: Float,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    isScrolled: Boolean = false,
    titleLayers: List<TopBarTitleLayer> = emptyList(),
    link: String? = "",
    navigationAction: NavigationAction,
    onClick: (() -> Unit)? = null,
    onNavButtonClick: (NavigationAction) -> Unit = {},
    onNavigateToStylePage: () -> Unit,
) {
    val context = LocalContext.current
    val sharedContent = LocalSharedContent.current
    val isOutlined =
        LocalReadingPageTonalElevation.current == ReadingPageTonalElevationPreference.Outlined

    val containerColor by
        animateColorAsState(
            with(MaterialTheme.colorScheme) {
                if (isOutlined || !isScrolled) surface else surfaceContainer
            },
            label = "",
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        )

    Column(modifier = Modifier.drawBehind { drawRect(containerColor) }) {
            Spacer(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            )
            TopAppBar(
                title = {
                    Box {
                        // Outgoing and incoming titles overlap and fade past each
                        // other while their pages slide, so the title follows the
                        // gesture rather than waiting for a bar-level animation.
                        titleLayers.forEach { layer ->
                            if (layer.alpha > 0.01f) {
                                Text(
                                    modifier = Modifier.alpha(layer.alpha),
                                    text = layer.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style =
                                        MaterialTheme.typography.titleLarge.merge(
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                )
                            }
                        }
                    }
                },
                modifier =
                    if (onClick == null) Modifier
                    else
                        Modifier.clickable(
                            onClick = onClick,
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ),
                windowInsets = WindowInsets(0.dp),
                navigationIcon = {
                    val imageVector =
                        when (navigationAction) {
                            NavigationAction.Close -> Icons.Rounded.Close
                            NavigationAction.HideList -> Icons.AutoMirrored.Rounded.MenuOpen
                            NavigationAction.ExpandList -> Icons.Rounded.Menu
                        }
                    val contentDescription =
                        when (navigationAction) {
                            NavigationAction.Close -> stringResource(R.string.close)
                            NavigationAction.HideList -> "Hide list"
                            NavigationAction.ExpandList -> "Expand list"
                        }
                    FeedbackIconButton(
                        imageVector = imageVector,
                        contentDescription = contentDescription,
                        tint = MaterialTheme.colorScheme.onSurface,
                    ) {
                        onNavButtonClick(navigationAction)
                    }
                },
                actions = {
                    FeedbackIconButton(
                        modifier = Modifier.size(22.dp),
                        imageVector = Icons.Outlined.Palette,
                        contentDescription = stringResource(R.string.style),
                        tint = MaterialTheme.colorScheme.onSurface,
                    ) {
                        onNavigateToStylePage()
                    }
                    FeedbackIconButton(
                        modifier = Modifier.size(20.dp),
                        imageVector = Icons.Outlined.Share,
                        contentDescription = stringResource(R.string.share),
                        tint = MaterialTheme.colorScheme.onSurface,
                    ) {
                        val shareTitle = titleLayers.maxByOrNull { it.alpha }?.text
                        sharedContent.share(context, shareTitle, link)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
            if (isOutlined && isScrolled) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    thickness = 0.5f.dp,
                )
            }
    }
}
