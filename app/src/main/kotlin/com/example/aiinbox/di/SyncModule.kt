package com.example.aiinbox.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module for the Drive sync subsystem. Empty for now — `DriveTokenStore`
 * is a constructor-injected `@Singleton` and needs no `@Provides`. This
 * module exists as the seam where later providers (DriveApiClient,
 * DriveAuthRepository, etc.) will land if they need explicit factory
 * customization (e.g. a dedicated OkHttpClient or Json instance).
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule
