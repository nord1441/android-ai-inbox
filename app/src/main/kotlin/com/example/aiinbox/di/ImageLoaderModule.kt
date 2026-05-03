package com.example.aiinbox.di

import android.content.Context
import coil.ImageLoader
import com.example.aiinbox.data.storage.EncryptedImageStore
import com.example.aiinbox.ui.coil.EncryptedImageFetcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ImageLoaderModule {

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext ctx: Context,
        store: EncryptedImageStore,
    ): ImageLoader =
        ImageLoader.Builder(ctx)
            .components { add(EncryptedImageFetcher.Factory(store)) }
            // 復号した画像はディスクキャッシュしない
            .diskCache(null)
            .build()
}
