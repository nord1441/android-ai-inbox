package com.example.aiinbox.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.aiinbox.data.db.AppDatabase
import com.example.aiinbox.data.storage.EncryptedImageStore
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class InboxRepositoryDeleteTombstoneTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var db: AppDatabase
    @Inject lateinit var repository: InboxRepository
    @Inject lateinit var imageStore: EncryptedImageStore

    @Before fun setup() = hilt.inject()

    @Test
    fun delete_setsTombstoneAndErasesFile_butKeepsRow() = runBlocking {
        // Arrange: create an item with one attachment via the test helper.
        val id = repository.createTestItemWithAttachment(
            text = "hello",
            attachmentBytes = ByteArray(16) { 1 },
        )
        val attBefore = db.attachmentDao().listForItem(id)
        assertEquals(1, attBefore.size)
        val encName = attBefore.first().encryptedFilename
        assertNotNull(imageStore.read(encName))

        // Act
        repository.delete(id)

        // Assert: row exists with deleted_at set; file is gone; observable lists are empty.
        val row = db.inboxDao().getById(id)
        assertNotNull("row must remain (tombstone, not hard delete)", row)
        assertNotNull("deleted_at must be set", row!!.deletedAt)
        assertEquals(emptyList<Any>(), repository.observeAll().first())
        assertFalse("encrypted file must be erased", imageStore.exists(encName))
    }
}
