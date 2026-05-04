package com.example.aiinbox.sync

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveApiClient @Inject constructor(
    private val client: OkHttpClient,
    private val tokenProvider: suspend () -> String?,
    private val baseUrl: HttpUrl = "https://www.googleapis.com/".toHttpUrl(),
    private val uploadBaseUrl: HttpUrl = "https://www.googleapis.com/upload/".toHttpUrl(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class FileMetadata(val id: String, val name: String, val size: Long?, val etag: String?)

    sealed interface DownloadResult {
        object NotModified : DownloadResult
        data class Body(val bytes: ByteArray, val etag: String?) : DownloadResult
    }

    /** GET /drive/v3/files?spaces=appDataFolder&q=name='X' and 'appDataFolder' in parents */
    suspend fun findFileByName(name: String): FileMetadata? {
        val q = "name='$name' and 'appDataFolder' in parents"
        val url = baseUrl.newBuilder()
            .addPathSegments("drive/v3/files")
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("q", q)
            .addQueryParameter("fields", "files(id,name,size)")
            .build()
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer ${requireToken()}")
            .build()
        return client.newCall(req).executeSuspending().use { resp ->
            when {
                resp.code == 401 -> throw DriveAuthRequiredException()
                resp.code !in 200..299 -> error("Drive list failed: ${resp.code} ${resp.message}")
                else -> {
                    val body = resp.body!!.string()
                    val root = json.parseToJsonElement(body).jsonObject
                    val files = root["files"]?.jsonArray ?: return@use null
                    files.firstOrNull()?.jsonObject?.let { obj ->
                        FileMetadata(
                            id = obj["id"]!!.jsonPrimitive.content,
                            name = obj["name"]!!.jsonPrimitive.content,
                            size = obj["size"]?.jsonPrimitive?.longOrNull,
                            etag = null,
                        )
                    }
                }
            }
        }
    }

    /**
     * List every file in the appDataFolder spaces. Used by SyncWorker to build
     * a name → metadata lookup so both applyPull (dereference items/X.json
     * and attachments/X.bin) and applyPush (find vs. create + size compare)
     * can run from one Drive list call.
     *
     * Iterates pageToken until the server reports no nextPageToken.
     */
    suspend fun listAllFiles(): Map<String, FileMetadata> {
        val out = mutableMapOf<String, FileMetadata>()
        var pageToken: String? = null
        do {
            val url = baseUrl.newBuilder()
                .addPathSegments("drive/v3/files")
                .addQueryParameter("spaces", "appDataFolder")
                .addQueryParameter("fields", "nextPageToken,files(id,name,size)")
                .addQueryParameter("pageSize", "1000")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val req = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer ${requireToken()}")
                .build()
            pageToken = client.newCall(req).executeSuspending().use { resp ->
                when {
                    resp.code == 401 -> throw DriveAuthRequiredException()
                    resp.code !in 200..299 -> error("Drive list failed: ${resp.code} ${resp.message}")
                    else -> {
                        val root = json.parseToJsonElement(resp.body!!.string()).jsonObject
                        root["files"]?.jsonArray?.forEach { el ->
                            val obj = el.jsonObject
                            val name = obj["name"]!!.jsonPrimitive.content
                            out[name] = FileMetadata(
                                id = obj["id"]!!.jsonPrimitive.content,
                                name = name,
                                size = obj["size"]?.jsonPrimitive?.longOrNull,
                                etag = null,
                            )
                        }
                        root["nextPageToken"]?.jsonPrimitive?.content
                    }
                }
            }
        } while (pageToken != null)
        return out
    }

    /** GET /drive/v3/files/{fileId}?alt=media with optional If-None-Match */
    suspend fun downloadBytes(fileId: String, ifNoneMatchEtag: String? = null): DownloadResult {
        val url = baseUrl.newBuilder()
            .addPathSegments("drive/v3/files/$fileId")
            .addQueryParameter("alt", "media")
            .build()
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer ${requireToken()}")
            .apply { ifNoneMatchEtag?.let { header("If-None-Match", it) } }
            .build()
        return client.newCall(req).executeSuspending().use { resp ->
            when (resp.code) {
                304 -> DownloadResult.NotModified
                in 200..299 -> {
                    val bytes = resp.body!!.bytes()
                    DownloadResult.Body(bytes, resp.header("ETag"))
                }
                401 -> throw DriveAuthRequiredException()
                else -> error("Drive download failed: ${resp.code} ${resp.message}")
            }
        }
    }

    /** POST /upload/drive/v3/files?uploadType=multipart with appDataFolder parent */
    suspend fun createFile(name: String, body: ByteArray, mimeType: String): FileMetadata {
        val url = uploadBaseUrl.newBuilder()
            .addPathSegments("drive/v3/files")
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", "id,name,size")
            .build()
        val metaJson = """{"name":"$name","parents":["appDataFolder"]}"""
        val multipart = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metaJson.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(body.toRequestBody(mimeType.toMediaType()))
            .build()
        val req = Request.Builder()
            .url(url)
            .post(multipart)
            .header("Authorization", "Bearer ${requireToken()}")
            .build()
        return client.newCall(req).executeSuspending().use { resp ->
            when {
                resp.code == 401 -> throw DriveAuthRequiredException()
                resp.code !in 200..299 -> error("Drive create failed: ${resp.code} ${resp.message}")
                else -> parseFileMetadata(resp.body!!.string())
            }
        }
    }

    /** PATCH /upload/drive/v3/files/{fileId}?uploadType=media */
    suspend fun updateFileBytes(fileId: String, body: ByteArray, mimeType: String): FileMetadata {
        val url = uploadBaseUrl.newBuilder()
            .addPathSegments("drive/v3/files/$fileId")
            .addQueryParameter("uploadType", "media")
            .addQueryParameter("fields", "id,name,size")
            .build()
        val req = Request.Builder()
            .url(url)
            .patch(body.toRequestBody(mimeType.toMediaType()))
            .header("Authorization", "Bearer ${requireToken()}")
            .build()
        return client.newCall(req).executeSuspending().use { resp ->
            when {
                resp.code == 401 -> throw DriveAuthRequiredException()
                resp.code !in 200..299 -> error("Drive update failed: ${resp.code} ${resp.message}")
                else -> parseFileMetadata(resp.body!!.string())
            }
        }
    }

    /** DELETE /drive/v3/files/{fileId} */
    suspend fun deleteFile(fileId: String) {
        val url = baseUrl.newBuilder()
            .addPathSegments("drive/v3/files/$fileId")
            .build()
        val req = Request.Builder()
            .url(url)
            .delete()
            .header("Authorization", "Bearer ${requireToken()}")
            .build()
        client.newCall(req).executeSuspending().use { resp ->
            when {
                resp.code == 204 -> Unit
                resp.code == 401 -> throw DriveAuthRequiredException()
                else -> error("Drive delete failed: ${resp.code} ${resp.message}")
            }
        }
    }

    private suspend fun requireToken(): String =
        tokenProvider() ?: error("no Drive access token available")

    private fun parseFileMetadata(bodyStr: String): FileMetadata {
        val obj = json.parseToJsonElement(bodyStr).jsonObject
        return FileMetadata(
            id = obj["id"]!!.jsonPrimitive.content,
            name = obj["name"]!!.jsonPrimitive.content,
            size = obj["size"]?.jsonPrimitive?.longOrNull,
            etag = null,
        )
    }
}

/**
 * Thrown by [DriveApiClient] on HTTP 401. Caller (typically [SyncWorker])
 * is expected to translate this into [SyncState.Cause.ReauthRequired]
 * and stop retrying — refresh isn't possible without a re-link in v1.
 */
class DriveAuthRequiredException : Exception("Drive access token rejected; user re-link required")

private suspend fun okhttp3.Call.executeSuspending(): okhttp3.Response =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                cont.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                cont.resumeWith(Result.success(response))
            }
        })
    }
