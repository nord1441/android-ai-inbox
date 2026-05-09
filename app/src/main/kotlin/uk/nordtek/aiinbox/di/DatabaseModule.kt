package uk.nordtek.aiinbox.di

import android.content.Context
import uk.nordtek.aiinbox.data.crypto.KeystorePassphraseProvider
import uk.nordtek.aiinbox.data.db.AppDatabase
import uk.nordtek.aiinbox.data.db.AttachmentDao
import uk.nordtek.aiinbox.data.db.FsSyncStateDao
import uk.nordtek.aiinbox.data.db.InboxDao
import uk.nordtek.aiinbox.data.db.buildEncryptedDatabase
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

    @Provides
    fun provideAttachmentDao(db: AppDatabase): AttachmentDao = db.attachmentDao()

    @Provides
    fun provideFsSyncStateDao(db: AppDatabase): FsSyncStateDao = db.fsSyncStateDao()
}
