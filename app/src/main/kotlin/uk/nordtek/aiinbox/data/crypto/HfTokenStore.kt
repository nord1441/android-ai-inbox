package uk.nordtek.aiinbox.data.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the user's Hugging Face access token (used to download gated Gemma
 * models) in EncryptedSharedPreferences. Master key is held in Android
 * Keystore — never written to backup, never readable from another app.
 */
@Singleton
class HfTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun get(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun set(token: String) {
        prefs.edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    fun hasToken(): Boolean = !get().isNullOrBlank()

    companion object {
        private const val PREFS_FILE = "ai_inbox_hf"
        private const val KEY_TOKEN = "hf_token"
    }
}
