package com.example.aiinbox.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Empty for now — every FS sync class is constructor-injected. Exists as a
 * seam where later @Provides definitions can land if needed.
 */
@Module
@InstallIn(SingletonComponent::class)
object FsSyncModule
