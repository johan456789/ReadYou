package me.ash.reader.ui.page.adaptive

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.domain.service.MarkReadStatusPartiallyAppliedException
import timber.log.Timber

internal fun launchMarkReadStatus(
    applicationScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    markReadStatus: suspend () -> Set<String>,
    onMarked: (Set<String>) -> Unit = {},
) {
    applicationScope.launch(ioDispatcher) {
        try {
            onMarked(markReadStatus())
        } catch (exception: MarkReadStatusPartiallyAppliedException) {
            Timber.tag("FlowViewModel")
                .w(exception, "markReadStatus completed locally but remote sync failed")
            onMarked(exception.affectedIds)
        }
    }
}
