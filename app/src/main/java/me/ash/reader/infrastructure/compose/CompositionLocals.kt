package me.ash.reader.infrastructure.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import me.ash.reader.domain.service.ReadingInsightsRecorder

/**
 * The reading-insights recorder, provided at the composition root. Nullable so
 * previews and any call site outside the provided tree degrade to a no-op.
 */
val LocalReadingInsights = staticCompositionLocalOf<ReadingInsightsRecorder?> { null }

@Composable
fun ProvideCompositionLocals(
    insightsRecorder: ReadingInsightsRecorder?,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalReadingInsights provides insightsRecorder) {
        ProvideUriHandler(insightsRecorder, content)
    }
}
