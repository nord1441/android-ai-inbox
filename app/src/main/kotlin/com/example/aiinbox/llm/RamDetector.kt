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

    @Suppress("UNUSED_PARAMETER")
    fun selectVariant(totalRamBytes: Long): ModelVariant {
        // Until the bigger Gemma 3 4B / Gemma 4 path lands (LiteRT-LM API),
        // we only ship Gemma 3 1B which is small enough for any minSdk 33
        // device with ≥6 GB RAM. RAM-based selection becomes meaningful again
        // once a 4B+ option is added.
        return ModelVariant.GEMMA_3_1B
    }

    fun selectVariantForDevice(context: Context): ModelVariant =
        selectVariant(detectTotalRamBytes(context))
}
