package com.example.aiinbox.screenshot

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.aiinbox.data.db.AttachmentKind
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class BitmapToAttachmentPipelineTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)
    @Inject lateinit var pipeline: BitmapToAttachmentPipeline
    @Inject lateinit var repository: com.example.aiinbox.data.repository.InboxRepository

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .build()
        )
        hiltRule.inject()
    }

    @Test
    fun saveAsItem_createsItemAndAttachment() = runBlocking {
        val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val itemId = pipeline.saveAsItem(bmp, AttachmentKind.SCREENSHOT, "screenshot:capture")
        val full = repository.getItemWithAttachments(itemId)!!
        assertThat(full.attachments).hasSize(1)
        assertThat(full.attachments[0].kind).isEqualTo(AttachmentKind.SCREENSHOT)
        assertThat(full.item.sourceApp).isEqualTo("screenshot:capture")
    }

    @Test
    fun averageLuminance_blackBitmap_returnsLow() {
        val black = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
        assertThat(pipeline.averageLuminance(black)).isLessThan(5)
    }

    @Test
    fun averageLuminance_whiteBitmap_returnsHigh() {
        val white = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        assertThat(pipeline.averageLuminance(white)).isGreaterThan(250)
    }
}
