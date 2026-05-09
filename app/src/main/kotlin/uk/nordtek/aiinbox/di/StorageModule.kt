package uk.nordtek.aiinbox.di

import android.content.Context
import uk.nordtek.aiinbox.data.storage.EncryptedImageStore
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
