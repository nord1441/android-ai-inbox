package com.example.aiinbox.sync

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists Drive OAuth tokens through Android Keystore-backed
 * EncryptedSharedPreferences. Mirrors HfTokenStore so the surrounding
 * pattern stays familiar.
 */
@Singleton
open class DriveTokenStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtEpochMs: Long,
        val accountEmail: String,
    )

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "drive_tokens",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    open fun get(): Tokens? {
        val a = prefs.getString(KEY_ACCESS, null) ?: return null
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val expires = prefs.getLong(KEY_EXPIRES, 0L)
        if (expires == 0L) return null
        return Tokens(
            accessToken = a,
            refreshToken = prefs.getString(KEY_REFRESH, null),
            expiresAtEpochMs = expires,
            accountEmail = email,
        )
    }

    open fun put(tokens: Tokens) {
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, tokens.refreshToken)
            .putLong(KEY_EXPIRES, tokens.expiresAtEpochMs)
            .putString(KEY_EMAIL, tokens.accountEmail)
            .apply()
    }

    open fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES = "expires_at"
        private const val KEY_EMAIL = "account_email"
    }
}
