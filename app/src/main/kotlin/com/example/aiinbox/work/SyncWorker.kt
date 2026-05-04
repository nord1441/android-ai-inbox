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

            // Pull manifest. 304 → treat remote as unchanged; we still walk
            // local for push candidates and re-publish the manifest so our
            // last_synced stamps are accurate.
            val remoteItems: List<SyncManifest.ManifestItem> = when {
                manifestFile == null -> emptyList()
                else -> when (
                    val r = api.downloadBytes(manifestFile.id, ifNoneMatchEtag = state?.lastManifestEtag)
                ) {
                    DriveApiClient.DownloadResult.NotModified -> emptyList()
                    is DriveApiClient.DownloadResult.Body ->
                        json.decodeFromString(SyncManifest.serializer(), r.bytes.decodeToString()).items
                }
            }

            val localRefs = repository.allLocalRefs()
            val diff = SyncEngine.diff(localRefs, remoteItems)

            // One Drive list call to dereference filenames → file ids for pulls.
            val fileIdLookup = if (diff.pull.isNotEmpty()) api.listAllFileNamesAndIds() else emptyMap()
            engine.applyPull(diff.pull, fileIdLookup)
            engine.applyPush(diff.push)

            // Re-publish manifest with post-sync state.
            val now = System.currentTimeMillis()
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
                    // an HTTP ETag (they hit the metadata endpoint). For now we
                    // leave the ETag as-is — the next manifest pull will get a
                    // 200 with body and refresh the ETag from the GET response.
                    lastManifestEtag = newMeta.etag ?: state?.lastManifestEtag,
                )
            )
            repository.markSynced(diff.push + diff.pull, now)

            syncStateRepository.setIdle()
            return Result.success()
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "sync failed", t)
            val cause = when (t.message) {
                "auth required" -> SyncState.Cause.ReauthRequired
                else -> SyncState.Cause.Other
            }
            syncStateRepository.setError(cause, t.message)
            return Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val MANIFEST_NAME = "manifest.json"
    }
}
