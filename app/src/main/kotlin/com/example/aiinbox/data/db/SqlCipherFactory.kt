package com.example.aiinbox.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.example.aiinbox.data.crypto.KeystorePassphraseProvider
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object SqlCipherFactory {

    @Volatile private var loaded = false

    /**
     * Loads the SQLCipher native library. sqlcipher-android 4.x does NOT
     * auto-load via static initializer reliably — calls into [SQLiteConnection]
     * crash with UnsatisfiedLinkError if this isn't called before opening the
     * database. Idempotent (System.loadLibrary is no-op after first success).
     */
    @Suppress("UNUSED_PARAMETER")
    fun loadLibs(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("sqlcipher")
                loaded = true
            }
        }
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
        .addCallback(FtsCallback)
        .build()
}
