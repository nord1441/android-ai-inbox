package com.example.aiinbox.sync

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the two-step OAuth handshake:
 *   1. Credential Manager picks a Google account and returns an ID token.
 *   2. Authorization API requests the drive.appdata scope and returns
 *      access + refresh tokens.
 *
 * Tokens are persisted via DriveTokenStore. Refresh failures surface
 * via [freshAccessToken] returning null; callers are responsible for
 * routing the user back to [link] in that case.
 */
@Singleton
open class DriveAuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenStore: DriveTokenStore,
) {

    open suspend fun link(activity: Activity): Result<DriveTokenStore.Tokens> {
        return try {
            val cm = CredentialManager.create(activity)
            val idOption = GetGoogleIdOption.Builder()
                .setServerClientId(WEB_CLIENT_ID)
                .setFilterByAuthorizedAccounts(false)
                .build()
            val cred = cm.getCredential(
                activity,
                GetCredentialRequest.Builder().addCredentialOption(idOption).build(),
            )
            val idTokenCred = GoogleIdTokenCredential.createFrom(cred.credential.data)
            val email = idTokenCred.id

            val authClient = Identity.getAuthorizationClient(activity)
            val authReq = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(SCOPE_DRIVE_APPDATA)))
                .requestOfflineAccess(WEB_CLIENT_ID, /* forceCodeForRefreshToken = */ true)
                .build()
            val authResult = authClient.authorize(authReq).await()

            // If user-consent UI is needed, the result carries a PendingIntent.
            // Caller (the screen) is responsible for launching it; here we expect
            // the consent path was already cleared. If not, surface an error.
            if (authResult.hasResolution()) {
                return Result.failure(
                    DriveAuthException("user consent required for drive.appdata", authResult.pendingIntent)
                )
            }

            val token = authResult.accessToken
                ?: return Result.failure(IllegalStateException("no access token returned"))
            val refresh = authResult.serverAuthCode
            val tokens = DriveTokenStore.Tokens(
                accessToken = token,
                refreshToken = refresh,
                expiresAtEpochMs = System.currentTimeMillis() + 50 * 60 * 1000L, // 50 min safety
                accountEmail = email,
            )
            tokenStore.put(tokens)
            Result.success(tokens)
        } catch (e: GetCredentialException) {
            Result.failure(e)
        }
    }

    open fun unlink() {
        tokenStore.clear()
    }

    open fun currentEmail(): String? = tokenStore.get()?.accountEmail

    open suspend fun freshAccessToken(): String? {
        val t = tokenStore.get() ?: return null
        if (t.expiresAtEpochMs > System.currentTimeMillis() + 60_000) return t.accessToken
        return refreshAccessTokenInternal(t)
    }

    protected open suspend fun refreshAccessTokenInternal(prev: DriveTokenStore.Tokens): String? {
        // v1 ships path (b): no silent refresh, force re-link on expiry.
        // See spec for the rationale (no client_secret on a public Android client
        // and the manual cost is acceptable for a single-developer hobby project).
        // When silent refresh becomes a priority, swap this body for an OkHttp
        // POST to https://oauth2.googleapis.com/token with grant_type=refresh_token.
        return null
    }

    class DriveAuthException(
        message: String,
        val pendingIntent: android.app.PendingIntent? = null,
    ) : Exception(message)

    companion object {
        // Replace at deployment time with a real OAuth 2.0 web client ID created
        // at https://console.cloud.google.com/apis/credentials. Until then, link
        // attempts will fail at the GoogleIdOption validation step.
        const val WEB_CLIENT_ID = "REPLACE_ME_WEB_CLIENT_ID.apps.googleusercontent.com"
        const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    }
}
