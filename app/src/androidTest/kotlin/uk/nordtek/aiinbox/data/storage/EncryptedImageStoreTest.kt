package uk.nordtek.aiinbox.data.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class EncryptedImageStoreTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var store: EncryptedImageStore
    private lateinit var dir: File

    @Before
    fun setup() {
        dir = File(ctx.filesDir, "attachments-test").apply { deleteRecursively(); mkdirs() }
        store = EncryptedImageStore(ctx, dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun saveThenRead_returnsSameBytes() {
        val original = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val name = store.save(original)
        val read = store.read(name).use { it.readBytes() }
        assertThat(read).isEqualTo(original)
    }

    @Test
    fun delete_removesFile() {
        val name = store.save(byteArrayOf(1, 2, 3))
        assertThat(File(dir, name).exists()).isTrue()
        store.delete(name)
        assertThat(File(dir, name).exists()).isFalse()
    }

    @Test(expected = IOException::class)
    fun read_missingFile_throwsIOException() {
        store.read("nonexistent.jpg.enc").use { it.readBytes() }
    }
}
