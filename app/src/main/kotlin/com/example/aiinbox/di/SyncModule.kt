package com.example.aiinbox.di

import com.example.aiinbox.sync.DriveApiClient
import com.example.aiinbox.sync.DriveAuthRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Hilt module for the Drive sync subsystem. `DriveApiClient` needs an explicit
 * @Provides because its `tokenProvider` parameter is a function type that Hilt
 * cannot auto-provide via constructor injection alone.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    @Provides
    @Singleton
    fun provideDriveApiClient(
        client: OkHttpClient,
        auth: DriveAuthRepository,
    ): DriveApiClient = DriveApiClient(
        client = client,
        tokenProvider = { auth.freshAccessToken() },
    )
}
