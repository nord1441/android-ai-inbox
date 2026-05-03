package com.example.aiinbox.screenshot

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.aiinbox.AiInboxApplication
import com.example.aiinbox.R
import com.example.aiinbox.data.db.AttachmentKind
import com.example.aiinbox.notification.NotificationChannels
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ScreenshotCaptureService : Service() {

    @Inject lateinit var pipeline: BitmapToAttachmentPipeline

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val timeoutRunnable = Runnable {
        android.util.Log.w(TAG, "Capture timeout — stopping")
        Toast.makeText(this, "スクリーンショットに失敗しました", Toast.LENGTH_SHORT).show()
        cleanupAndStop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: run { stopSelf(); return START_NOT_STICKY }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null) {
            android.util.Log.w(TAG, "No projection data — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, data)
        if (mp == null) {
            android.util.Log.w(TAG, "MediaProjection null — stopping")
            Toast.makeText(this, "スクリーンショットに失敗しました", Toast.LENGTH_SHORT).show()
            cleanupAndStop()
            return START_NOT_STICKY
        }
        mediaProjection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                android.util.Log.i(TAG, "MediaProjection stopped externally")
                cleanupAndStop()
            }
        }, mainHandler)

        startCapture(mp, retryOnBlack = true)
        mainHandler.postDelayed(timeoutRunnable, 10_000L)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notif: Notification = NotificationCompat.Builder(this, NotificationChannels.CHANNEL_SCREENSHOT_CAPTURE)
            .setSmallIcon(R.drawable.ic_screenshot_tile)
            .setContentTitle("スクリーンショット撮影中")
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startCapture(mp: MediaProjection, retryOnBlack: Boolean) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mp.createVirtualDisplay(
            "ai-inbox-screenshot",
            w, h, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, mainHandler,
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = try { reader.acquireLatestImage() } catch (_: Throwable) { null }
            if (image == null) return@setOnImageAvailableListener
            try {
                val bmp = imageToBitmap(image, w, h)
                image.close()

                if (retryOnBlack && pipeline.averageLuminance(bmp) < 5) {
                    android.util.Log.i(TAG, "Black frame detected; retrying after 200ms")
                    bmp.recycle()
                    cleanupCapture()
                    mainHandler.postDelayed({ startCapture(mp, retryOnBlack = false) }, 200L)
                    return@setOnImageAvailableListener
                }

                mainHandler.removeCallbacks(timeoutRunnable)
                cleanupCapture()
                val app = application as AiInboxApplication
                app.applicationScope.launch {
                    try {
                        pipeline.saveAsItem(bmp, AttachmentKind.SCREENSHOT, "screenshot:capture")
                        mainHandler.post {
                            Toast.makeText(this@ScreenshotCaptureService, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
                        }
                    } catch (t: Throwable) {
                        android.util.Log.e(TAG, "saveAsItem failed", t)
                    } finally {
                        cleanupAndStop()
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "imageReader handler threw", t)
                cleanupAndStop()
            }
        }, mainHandler)
    }

    private fun imageToBitmap(image: android.media.Image, w: Int, h: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * w
        val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) bmp else {
            val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
            bmp.recycle()
            cropped
        }
    }

    private fun cleanupCapture() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        virtualDisplay = null
        imageReader = null
    }

    private fun cleanupAndStop() {
        mainHandler.removeCallbacks(timeoutRunnable)
        cleanupCapture()
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        mediaProjection = null
        stopSelf()
    }

    override fun onDestroy() {
        cleanupCapture()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenshotCaptureService"
        private const val NOTIF_ID = 9001
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }
}
