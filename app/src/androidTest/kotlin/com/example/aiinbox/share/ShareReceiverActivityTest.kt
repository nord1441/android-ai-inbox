package com.example.aiinbox.share

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.aiinbox.data.repository.InboxRepository
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ShareReceiverActivityTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)
    @Inject lateinit var repository: InboxRepository

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun shareSingleImage_createsItemWithOneAttachment() = runBlocking {
        hiltRule.inject()
        val uri = createTestImageUri()
        val intent = Intent(ApplicationProvider.getApplicationContext(), ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        ActivityScenario.launch<ShareReceiverActivity>(intent).use {
            // applicationScope に投げているので polling
            var count = 0
            repeat(50) {
                val items = repository.observeAllWithAttachments().firstOrNull()
                if (items != null && items.isNotEmpty() && items[0].attachments.isNotEmpty()) {
                    count = items[0].attachments.size
                    return@repeat
                }
                delay(100)
            }
            assertThat(count).isEqualTo(1)
        }
    }

    private fun createTestImageUri(): Uri {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val file = File(ctx.cacheDir, "share-test.jpg")
        ByteArrayOutputStream().use { bao ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, bao)
            file.writeBytes(bao.toByteArray())
        }
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    }
}
