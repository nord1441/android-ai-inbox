package com.example.aiinbox.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.example.aiinbox.data.crypto.KeystorePassphraseProvider
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object SqlCipherFactory {

    /**
     * No-op on sqlcipher-android 4.x: the native library is loaded automatically
     * via a static initializer in [net.zetetic.database.sqlcipher.SupportOpenHelperFactory].
     * Kept as a named function so call-sites remain readable and any future migration
     * back to an explicit load only needs one change.
     */
    @Suppress("UNUSED_PARAMETER")
    fun loadLibs(context: Context) {
        // sqlcipher-android 4.x self-initialises – no explicit System.loadLibrary call needed.
    }

    fun create(passphraseProvider: KeystorePassphraseProvider): SupportSQLiteOpenHelper.Factory {
        val passphraseBytes = passphraseProvider.get().toByteArray(Charsets.UTF_8)
        return SupportOpenHelperFactory(passphraseBytes)
    }
}

fun buildEncryptedDatabase(
    context: Context,
    passphraseProvider: KeystorePassphraseProvider,
): AppDatabase {
    SqlCipherFactory.loadLibs(context)
    return Room.databaseBuilder(context, AppDatabase::class.java, "inbox.db")
        .openHelperFactory(SqlCipherFactory.create(passphraseProvider))
        .build()
}
