package com.example.aiinbox.screenshot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.aiinbox.R

class ScreenshotCaptureActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            Toast.makeText(this, R.string.screenshot_consent_canceled, Toast.LENGTH_SHORT).show()
            finish()
            return@registerForActivityResult
        }
        val svc = Intent(this, ScreenshotCaptureService::class.java).apply {
            putExtra(ScreenshotCaptureService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(ScreenshotCaptureService.EXTRA_RESULT_DATA, result.data)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            ContextCompat.startForegroundService(this, svc)
        } else {
            startService(svc)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(mpm.createScreenCaptureIntent())
    }
}
