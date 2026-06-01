package me.ash.reader.ui.page.home.flow

import me.ash.reader.domain.model.article.ArticleWithFeed

internal fun undoMarkedRead(
    items: List<ArticleWithFeed>,
    deferDbCommits: Boolean,
    undoWithDiffMap: (Array<ArticleWithFeed>) -> Unit,
    undoWithRepository: (Set<String>) -> Unit,
) {
    if (items.isEmpty()) return
    if (deferDbCommits) {
        undoWithDiffMap(items.toTypedArray())
    } else {
        undoWithRepository(items.map { it.article.id }.toSet())
    }
}
