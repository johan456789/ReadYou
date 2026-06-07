package me.ash.reader.ui.page.home.flow

import me.ash.reader.domain.model.article.ArticleWithFeed

internal data class MarkedReadUndoAction(
    val items: List<ArticleWithFeed>,
    val deferDbCommitsAtMarkTime: Boolean,
)

internal fun createMarkedReadUndoAction(
    items: List<ArticleWithFeed>,
    deferDbCommits: Boolean,
): MarkedReadUndoAction = MarkedReadUndoAction(items, deferDbCommits)

internal fun performMarkedReadUndo(
    action: MarkedReadUndoAction,
    undoWithDiffMap: (Array<ArticleWithFeed>) -> Unit,
    undoWithCommittedState: (List<ArticleWithFeed>) -> Unit,
) {
    // Intentionally use the captured mode from mark-time, not current mode.
    undoMarkedRead(
        items = action.items,
        deferDbCommits = action.deferDbCommitsAtMarkTime,
        undoWithDiffMap = undoWithDiffMap,
        undoWithCommittedState = undoWithCommittedState,
    )
}

internal fun undoMarkedRead(
    items: List<ArticleWithFeed>,
    deferDbCommits: Boolean,
    undoWithDiffMap: (Array<ArticleWithFeed>) -> Unit,
    undoWithCommittedState: (List<ArticleWithFeed>) -> Unit,
) {
    if (items.isEmpty()) return
    if (deferDbCommits) {
        undoWithDiffMap(items.toTypedArray())
    } else {
        undoWithCommittedState(items.distinctBy { it.article.id })
    }
}

internal fun filterUndoMarkedIds(
    affectedIds: Set<String>,
    preExistingLogicalReadIds: Set<String>,
): Set<String> = affectedIds - preExistingLogicalReadIds
