package uk.nordtek.aiinbox.data.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeystorePassphraseProvider @Inject constructor(
    private val context: Context,
) {
    // Secondary constructor for tests: inject a pre-built SharedPreferences to avoid
    // the AndroidKeyStore JCE provider requirement that EncryptedSharedPreferences needs.
    internal constructor(
        context: Context,
        prefs: SharedPreferences,
    ) : this(context) {
        lazyPrefs = prefs
    }

    @Volatile
    private var lazyPrefs: SharedPreferences? = null

    private val prefs: SharedPreferences
        get() = lazyPrefs ?: synchronized(this) {
            lazyPrefs ?: createEncryptedPrefs().also { lazyPrefs = it }
        }

    /** 32文字のランダムパスフレーズを返す（初回生成、以降同じ値）。 */
    fun get(): String {
        val existing = prefs.getString(KEY_PASSPHRASE, null)
        if (existing != null) return existing
        val newPass = generatePassphrase()
        prefs.edit().putString(KEY_PASSPHRASE, newPass).apply()
        return newPass
    }

    private fun generatePassphrase(): String {
        val rnd = SecureRandom()
        val bytes = ByteArray(24)
        rnd.nextBytes(bytes)
        // base64 (URL-safe) → 32文字に切り詰め
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        ).take(32)
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    companion object {
        private const val PREFS_FILE = "ai_inbox_keystore"
        private const val KEY_PASSPHRASE = "db_passphrase"
    }
}
