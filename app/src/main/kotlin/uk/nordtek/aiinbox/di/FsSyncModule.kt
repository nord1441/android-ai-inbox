package uk.nordtek.aiinbox.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.ZoneId
import javax.inject.Singleton

/**
 * FS sync wiring. Most classes are constructor-injected; this module hosts
 * the small set of platform values (e.g. the device default ZoneId) that
 * MarkdownExporter needs but cannot construct itself.
 */
@Module
@InstallIn(SingletonComponent::class)
object FsSyncModule {

    @Provides
    @Singleton
    fun provideSystemZoneId(): ZoneId = ZoneId.systemDefault()
}
