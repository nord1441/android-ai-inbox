package com.example.aiinbox.sync

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DriveApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DriveApiClient

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        client = DriveApiClient(
            client = OkHttpClient(),
            tokenProvider = { "test_access_token" },
            baseUrl = server.url("/"),
            uploadBaseUrl = server.url("/upload/"),
        )
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test
    fun downloadBytes_sendsBearerAndIfNoneMatch_andHandlesNotModified() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(304))
        val result = client.downloadBytes("file-id-1", ifNoneMatchEtag = "abc")
        assertTrue(result is DriveApiClient.DownloadResult.NotModified)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/drive/v3/files/file-id-1?alt=media", recorded.path)
        assertEquals("Bearer test_access_token", recorded.getHeader("Authorization"))
        assertEquals("abc", recorded.getHeader("If-None-Match"))
    }

    @Test
    fun downloadBytes_returnsBodyAndEtagOn200() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("ETag", "etag-2")
                .setBody(Buffer().write(byteArrayOf(1, 2, 3, 4)))
        )
        val result = client.downloadBytes("file-id-2")
        assertTrue(result is DriveApiClient.DownloadResult.Body)
        val body = result as DriveApiClient.DownloadResult.Body
        assertEquals(4, body.bytes.size)
        assertEquals("etag-2", body.etag)
    }

    @Test
    fun downloadBytes_throwsAuthRequiredOn401() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            client.downloadBytes("file-id-3")
            error("expected DriveAuthRequiredException")
        } catch (e: DriveAuthRequiredException) {
            // expected
        }
        Unit
    }

    @Test
    fun findFileByName_passesQueryAndDecodesFirstResult() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"files":[{"id":"f-1","name":"manifest.json","size":"123"}]}"""
            )
        )
        val meta = client.findFileByName("manifest.json")
        assertNotNull(meta)
        assertEquals("f-1", meta!!.id)
        assertEquals("manifest.json", meta.name)
        assertEquals(123L, meta.size)
        assertNull(meta.etag)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        // OkHttp encodes spaces as %20 and quotes as %27 in the q param.
        val path = recorded.path!!
        assertTrue("path missing /drive/v3/files: $path", path.startsWith("/drive/v3/files?"))
        assertTrue("path missing spaces=appDataFolder: $path", path.contains("spaces=appDataFolder"))
        assertTrue("path missing q with name=: $path", path.contains("q=name") && path.contains("manifest.json"))
        assertTrue("path missing fields: $path", path.contains("fields=files"))
    }

    @Test
    fun findFileByName_returnsNullWhenEmpty() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"files":[]}"""))
        val meta = client.findFileByName("nonexistent.json")
        assertNull(meta)
    }

    @Test
    fun createFile_sendsMultipartRelatedWithMetadataAndBytes() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"new-id","name":"items/foo.json","size":"42"}"""
            )
        )
        val meta = client.createFile("items/foo.json", byteArrayOf(7, 8, 9), "application/json")
        assertEquals("new-id", meta.id)
        assertEquals(42L, meta.size)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(
            "/upload/drive/v3/files?uploadType=multipart&fields=id%2Cname%2Csize",
            recorded.path,
        )
        val ct = recorded.getHeader("Content-Type")!!
        assertTrue("Content-Type must be multipart/related: $ct", ct.startsWith("multipart/related"))
        assertTrue("Content-Type must include boundary: $ct", ct.contains("boundary="))

        val bodyText = recorded.body.readUtf8()
        assertTrue("body must contain appDataFolder parent: $bodyText", bodyText.contains("appDataFolder"))
        assertTrue("body must contain requested name: $bodyText", bodyText.contains("items/foo.json"))
    }

    @Test
    fun updateFileBytes_patchesUploadEndpoint() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"existing-id","name":"items/foo.json","size":"99"}"""
            )
        )
        val meta = client.updateFileBytes("existing-id", byteArrayOf(1, 2), "application/json")
        assertEquals("existing-id", meta.id)
        assertEquals(99L, meta.size)

        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals(
            "/upload/drive/v3/files/existing-id?uploadType=media&fields=id%2Cname%2Csize",
            recorded.path,
        )
        assertEquals("Bearer test_access_token", recorded.getHeader("Authorization"))
    }

    @Test
    fun deleteFile_sendsDeleteAndAcceptsNoContent() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        client.deleteFile("doomed-id")

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/drive/v3/files/doomed-id", recorded.path)
        assertEquals("Bearer test_access_token", recorded.getHeader("Authorization"))
    }
}
