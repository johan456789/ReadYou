package me.ash.reader.infrastructure.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.ash.reader.domain.service.RssService

/** Exposes [RssService] to code outside the Hilt graph. */
@InstallIn(SingletonComponent::class)
@EntryPoint
interface RssServiceEntryPoint {
    fun rssService(): RssService
}
