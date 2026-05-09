package uk.nordtek.aiinbox.sync

import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import uk.nordtek.aiinbox.data.db.AppDatabase
import uk.nordtek.aiinbox.data.repository.InboxRepository
import uk.nordtek.aiinbox.data.storage.EncryptedImageStore
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FsSyncSafSmokeTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var engine: FsSyncEngine
    @Inject lateinit var repository: InboxRepository
    @Inject lateinit var imageStore: EncryptedImageStore
    @Inject lateinit var db: AppDatabase

    @Before fun setup() = hilt.inject()

    @Test
    fun runOnce_exportsAliveLocalItemToDisk() = runBlocking {
        // Use the app's cacheDir as a fake "SAF tree" via DocumentFile.fromFile.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val tmp = File(ctx.cacheDir, "fs-sync-smoke").apply {
            deleteRecursively(); mkdirs()
        }
        val tree = DocumentFile.fromFile(tmp)
        val fakeTreeUri = tree.uri.toString()

        // Seed an item via the existing test helper.
        val id = repository.createTestItemWithAttachment(
            text = "smoke",
            attachmentBytes = byteArrayOf(1, 2, 3),
        )

        engine.runOnce(fakeTreeUri)

        // Assert: a .md file matching <id> exists at the tree root.
        val files = tmp.listFiles().orEmpty()
        val md = files.firstOrNull { it.name.endsWith(".md") && it.name.contains(id) }
        assertNotNull("expected exported .md for id $id; saw ${files.joinToString { it.name }}", md)
    }
}
