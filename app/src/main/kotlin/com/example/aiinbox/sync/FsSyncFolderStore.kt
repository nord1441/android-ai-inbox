package com.example.aiinbox.sync

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the SAF tree URI the user picked. EncryptedSharedPreferences
 * here is overkill for a URI string, but it matches the pattern used by
 * HfTokenStore so the master-key plumbing is consistent across the app.
 */
@Singleton
open class FsSyncFolderStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "fs_sync_folder",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    open fun get(): String? = prefs.getString(KEY_URI, null)

    open fun set(uriString: String) {
        prefs.edit().putString(KEY_URI, uriString).apply()
    }

    open fun clear() {
        prefs.edit().remove(KEY_URI).apply()
    }

    private companion object {
        const val KEY_URI = "tree_uri"
    }
}
