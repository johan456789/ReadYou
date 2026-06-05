package me.ash.reader.ui.page.adaptive

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun launchMarkReadStatus(
    applicationScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    markReadStatus: suspend () -> Set<String>,
    onMarked: (Set<String>) -> Unit = {},
) {
    applicationScope.launch(ioDispatcher) {
        onMarked(markReadStatus())
    }
}
