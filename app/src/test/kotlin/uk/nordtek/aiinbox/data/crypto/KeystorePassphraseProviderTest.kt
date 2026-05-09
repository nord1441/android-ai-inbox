package uk.nordtek.aiinbox.data.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric unit tests for [KeystorePassphraseProvider].
 *
 * No @Config: Robolectric 4.16.1 defaults to the project's targetSdk=35, which works.
 *
 * EncryptedSharedPreferences requires the AndroidKeyStore JCE provider (hardware-backed),
 * which is unavailable in the JVM. The internal secondary constructor of
 * [KeystorePassphraseProvider] accepts a pre-built SharedPreferences so tests can supply
 * a plain in-memory store. Production code always uses the no-arg (primary + Hilt) path
 * that creates EncryptedSharedPreferences.
 */
@RunWith(RobolectricTestRunner::class)
class KeystorePassphraseProviderTest {

    private fun makePrefs(ctx: Context) =
        ctx.getSharedPreferences("test_keystore", Context.MODE_PRIVATE)

    @Test
    fun `first call generates a 32 byte passphrase`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val provider = KeystorePassphraseProvider(ctx, makePrefs(ctx))
        val pass1 = provider.get()
        assertThat(pass1).hasLength(32)
    }

    @Test
    fun `subsequent calls return same passphrase`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // Share the same underlying SharedPreferences instance to simulate persistence
        val sharedPrefs = makePrefs(ctx)
        val pass1 = KeystorePassphraseProvider(ctx, sharedPrefs).get()
        val pass2 = KeystorePassphraseProvider(ctx, sharedPrefs).get()
        assertThat(pass2).isEqualTo(pass1)
    }
}
