package com.example.aiinbox.di

import android.content.Context
import com.example.aiinbox.data.storage.EncryptedImageStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideEncryptedImageStore(
        @ApplicationContext ctx: Context,
    ): EncryptedImageStore = EncryptedImageStore(ctx)
}
