package com.example.aiinbox.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class LlmServiceClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private var connection: ServiceConnection? = null

    suspend fun submit(text: String, hint: ContentHint, variant: ModelVariant): Result<SummarizeResult> {
        val deferred = CompletableDeferred<Result<SummarizeResult>>()
        val binder = bind() ?: return Result.failure(IllegalStateException("Service bind failed"))
        try {
            binder.submit(LlmInferenceService.Job(text, hint, variant, deferred))
            return deferred.await()
        } finally {
            unbind()
        }
    }

    private suspend fun bind(): LlmInferenceService.LocalBinder? = suspendCancellableCoroutine { cont ->
        val intent = Intent(context, LlmInferenceService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val b = service as? LlmInferenceService.LocalBinder
                cont.resume(b)
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        connection = conn
        val ok = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        if (!ok) cont.resume(null)
    }

    private fun unbind() {
        connection?.let { context.unbindService(it) }
        connection = null
    }
}
