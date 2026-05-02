package com.example.aiinbox.di

import android.content.Context
import com.example.aiinbox.data.crypto.KeystorePassphraseProvider
import com.example.aiinbox.data.db.AppDatabase
import com.example.aiinbox.data.db.InboxDao
import com.example.aiinbox.data.db.buildEncryptedDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun providePassphraseProvider(
        @ApplicationContext ctx: Context,
    ): KeystorePassphraseProvider = KeystorePassphraseProvider(ctx)

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext ctx: Context,
        passphraseProvider: KeystorePassphraseProvider,
    ): AppDatabase = buildEncryptedDatabase(ctx, passphraseProvider)

    @Provides
    fun provideInboxDao(db: AppDatabase): InboxDao = db.inboxDao()
}
