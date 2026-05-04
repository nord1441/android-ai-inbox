package com.example.aiinbox.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.aiinbox.data.db.SyncStateDao
import com.example.aiinbox.data.db.SyncStateEntity
import com.example.aiinbox.data.repository.InboxRepository
import com.example.aiinbox.sync.DriveApiClient
import com.example.aiinbox.sync.DriveAuthRepository
import com.example.aiinbox.sync.DriveAuthRequiredException
import com.example.aiinbox.sync.SyncEngine
import com.example.aiinbox.sync.SyncManifest
import com.example.aiinbox.sync.SyncState
import com.example.aiinbox.sync.SyncStateRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json

/**
 * One Drive sync run:
 *   1. Confirm we have a valid access token (else surface ReauthRequired).
 *   2. Pull the remote manifest with If-None-Match against the last seen ETag.
 *   3. Diff local vs remote (LWW + tombstone).
 *   4. Apply pulls (download envelopes + attachment bytes, write to local DB
 *      + EncryptedImageStore) then pushes (upload envelopes + attachment
 *      bytes, create-or-update per existing file presence).
 *   5. Re-publish a fresh manifest reflecting the post-sync state and
 *      capture its ETag for next time.
 *   6. Mark touched ids `last_synced_at = now` and update sync_state.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val authRepository: DriveAuthRepository,
    private val api: DriveApiClient,
    private val engine: SyncEngine,
    private val syncStateDao: SyncStateDao,
    private val syncStateRepository: SyncStateRepository,
    private val repository: InboxRepository,
) : CoroutineWorker(appContext, params) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun doWork(): Result {
        // No-op when not linked at all — saves a network round trip and
        // avoids spurious "ReauthRequired" noise in the UI before the user
        // has ever linked.
        if (authRepository.currentEmail() == null) return Result.success()

        val token = authRepository.freshAccessToken()
        if (token == null) {
            syncStateRepository.setError(SyncState.Cause.ReauthRequired, "再リンクしてください")
            return Result.failure()
        }

        syncStateRepository.setRunning()
        try {
            val state = syncStateDao.get()
            val manifestFile = api.findFileByName(MANIFEST_NAME)

            // Pull manifest with conditional GET. 304 means remote is byte-for-byte
            // unchanged since we last published — we already pushed everything
            // local on that earlier run, so there is nothing to do this round.
            val remoteItems: List<SyncManifest.ManifestItem>? = when {
                manifestFile == null -> emptyList()
                else -> when (
                    val r = api.downloadBytes(manifestFile.id, ifNoneMatchEtag = state?.lastManifestEtag)
                ) {
                    DriveApiClient.DownloadResult.NotModified -> null
                    is DriveApiClient.DownloadResult.Body ->
                        json.decodeFromString(SyncManifest.serializer(), r.bytes.decodeToString()).items
                }
            }

            val now = System.currentTimeMillis()
            if (remoteItems == null) {
                // 304 fast path: stamp last_full_sync_at and bail.
                syncStateDao.upsert(
                    (state ?: SyncStateEntity()).copy(
                        accountEmail = state?.accountEmail ?: authRepository.currentEmail(),
                        lastFullSyncAt = now,
                    )
                )
                syncStateRepository.setIdle()
                return Result.success()
            }

            val localRefs = repository.allLocalRefs()
            val diff = SyncEngine.diff(localRefs, remoteItems)

            // One Drive list call serves both pull (dereference filenames →
            // file ids) and push (find-vs-create + size compare). Skip the
            // call if neither side has work to do.
            val fileLookup = if (diff.pull.isNotEmpty() || diff.push.isNotEmpty()) {
                api.listAllFiles()
            } else {
                emptyMap()
            }
            engine.applyPull(diff.pull, fileLookup)
            engine.applyPush(diff.push, fileLookup)

            // Re-publish manifest with post-sync state.
            val newManifest = engine.buildManifest(now)
            val manifestBytes = json.encodeToString(SyncManifest.serializer(), newManifest).encodeToByteArray()
            val newMeta = if (manifestFile == null) {
                api.createFile(MANIFEST_NAME, manifestBytes, "application/json")
            } else {
                api.updateFileBytes(manifestFile.id, manifestBytes, "application/json")
            }

            syncStateDao.upsert(
                SyncStateEntity(
                    id = 1,
                    accountEmail = state?.accountEmail ?: authRepository.currentEmail(),
                    lastFullSyncAt = now,
                    // findFileByName / createFile / updateFileBytes don't return
                    // an HTTP ETag (they hit the metadata endpoint). The next
                    // manifest pull will get a 200 with body and refresh the
                    // ETag from the GET response.
                    lastManifestEtag = newMeta.etag ?: state?.lastManifestEtag,
                )
            )
            repository.markSynced(diff.push + diff.pull, now)

            syncStateRepository.setIdle()
            return Result.success()
        } catch (e: DriveAuthRequiredException) {
            android.util.Log.w(TAG, "Drive token rejected; user re-link required", e)
            syncStateRepository.setError(SyncState.Cause.ReauthRequired, "再リンクしてください")
            return Result.failure()
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "sync failed", t)
            syncStateRepository.setError(SyncState.Cause.Other, t.message)
            return Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val MANIFEST_NAME = "manifest.json"
    }
}
