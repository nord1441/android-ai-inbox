package com.example.aiinbox.screenshot

import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

class ScreenshotTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ScreenshotCaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
