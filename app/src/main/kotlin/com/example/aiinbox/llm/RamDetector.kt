package com.example.aiinbox.llm

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService

object RamDetector {

    fun detectTotalRamBytes(context: Context): Long {
        val am = context.getSystemService<ActivityManager>() ?: return 0L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }

    fun selectVariant(totalRamBytes: Long): ModelVariant {
        val gigabytes = totalRamBytes / (1024.0 * 1024 * 1024)
        return if (gigabytes >= 8.0) ModelVariant.GEMMA_4_E4B else ModelVariant.GEMMA_4_E2B
    }

    fun selectVariantForDevice(context: Context): ModelVariant =
        selectVariant(detectTotalRamBytes(context))
}
