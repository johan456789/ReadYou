package me.ash.reader.ui.page.home.foryou

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.LocalOpenLink
import me.ash.reader.infrastructure.preference.LocalOpenLinkSpecificBrowser
import me.ash.reader.infrastructure.preference.LocalSharedContent
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.openURL
import me.ash.reader.ui.page.home.flow.SwipeableArticleItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForYouPage(
    onBack: () -> Unit,
    navigateToArticle: (String) -> Unit,
    forYouViewModel: ForYouViewModel = hiltViewModel(),
) {
    val forYouUiState = forYouViewModel.forYouUiState.collectAsStateValue()
    val diffMap = forYouViewModel.diffMapHolder.diffMap
    val context = LocalContext.current
    val openLink = LocalOpenLink.current
    val openLinkSpecificBrowser = LocalOpenLinkSpecificBrowser.current
    val sharedContent = LocalSharedContent.current
    val listState = rememberLazyListState()
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // Same deferred-commit behavior as the Unread filter: opened articles dim in
    // place instead of vanishing; commits flush when the page goes away.
    LaunchedEffect(Unit) {
        forYouViewModel.setDeferDbCommits(true)
    }
    // A restored LazyListState can point past the current list (e.g. saved from a
    // longer "show older picks" list); snap back to the top once data arrives.
    var snappedToTop by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(forYouUiState.articles.isNotEmpty()) {
        if (forYouUiState.articles.isNotEmpty() && !snappedToTop) {
            listState.scrollToItem(0)
            snappedToTop = true
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            forYouViewModel.setDeferDbCommits(false)
            forYouViewModel.flushDeferredDiffs()
        }
    }

    RYScaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(text = stringResource(R.string.for_you)) },
                navigationIcon = {
                    FeedbackIconButton(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    ) {
                        onBack()
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        content = {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                state = listState,
            ) {
                items(
                    items = forYouUiState.articles,
                    key = { it.article.id },
                ) { articleWithFeed ->
                    SwipeableArticleItem(
                        articleWithFeed = articleWithFeed,
                        isRead =
                            diffMap[articleWithFeed.article.id]?.isRead
                                ?: articleWithFeed.article.isRead,
                        isSwipeEnabled = { listState.isScrollInProgress },
                        onClick = {
                            if (articleWithFeed.feed.isBrowser) {
                                forYouViewModel.diffMapHolder.updateDiff(
                                    articleWithFeed,
                                    markRead = true,
                                )
                                context.openURL(
                                    articleWithFeed.article.link,
                                    openLink,
                                    openLinkSpecificBrowser,
                                )
                            } else {
                                navigateToArticle(articleWithFeed.article.id)
                            }
                        },
                        onToggleStarred = forYouViewModel::onToggleStarred,
                        onToggleRead = forYouViewModel::onToggleRead,
                        onShare = { article ->
                            with(article.article) {
                                sharedContent.share(context, title, link)
                            }
                        },
                    )
                }
                if (!forYouUiState.includeRead) {
                    item(key = "for-you-footer") {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 24.dp, bottom = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.for_you_caught_up),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = { forYouViewModel.setIncludeRead(true) },
                            ) {
                                Text(text = stringResource(R.string.for_you_show_older_picks))
                            }
                        }
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(128.dp))
                    Spacer(
                        modifier =
                            Modifier.windowInsetsBottomHeight(
                                WindowInsets.navigationBars
                            )
                    )
                }
            }
        },
    )
}
