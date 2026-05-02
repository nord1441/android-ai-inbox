package com.example.aiinbox.data.repository

import com.example.aiinbox.data.db.InboxDao
import com.example.aiinbox.data.db.InboxItem
import com.example.aiinbox.data.db.ItemStatus
import com.example.aiinbox.llm.SummarizeResult
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InboxRepository @Inject constructor(
    private val dao: InboxDao,
) {

    fun observeAll(): Flow<List<InboxItem>> = dao.observeAll()

    fun observeById(id: String): Flow<InboxItem?> = dao.observeById(id)

    suspend fun getById(id: String): InboxItem? = dao.getById(id)

    suspend fun getPendingItems(): List<InboxItem> = dao.getByStatus(ItemStatus.PENDING)

    suspend fun createPendingItem(text: String, subject: String?, sourceApp: String?): String {
        val now = System.currentTimeMillis()
        val item = InboxItem(
            id = UUID.randomUUID().toString(),
            originalText = text,
            originalSubject = subject,
            sourceApp = sourceApp,
            receivedAt = now,
            status = ItemStatus.PENDING,
            updatedAt = now,
        )
        dao.insert(item)
        return item.id
    }

    suspend fun markProcessing(id: String) {
        val current = dao.getById(id) ?: return
        dao.update(current.copy(status = ItemStatus.PROCESSING, updatedAt = System.currentTimeMillis()))
    }

    suspend fun applySummarizeResult(id: String, result: SummarizeResult) {
        val current = dao.getById(id) ?: return
        val edited = current.userEditedFields

        dao.update(
            current.copy(
                title = if ("title" in edited) current.title else result.title,
                summary = if ("summary" in edited) current.summary else result.summary,
                category = if ("category" in edited) current.category else result.category,
                tags = if ("tags" in edited) current.tags else result.tags,
                people = if ("people" in edited) current.people else result.people,
                places = if ("places" in edited) current.places else result.places,
                urls = if ("urls" in edited) current.urls else result.urls,
                event = if ("event" in edited) current.event else result.event,
                status = ItemStatus.COMPLETED,
                lastError = null,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun markFailed(id: String, error: String) {
        val current = dao.getById(id) ?: return
        dao.update(
            current.copy(
                status = ItemStatus.FAILED,
                processingAttempts = current.processingAttempts + 1,
                lastError = error,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun incrementAttempts(id: String) {
        val current = dao.getById(id) ?: return
        dao.update(current.copy(processingAttempts = current.processingAttempts + 1))
    }

    suspend fun updateField(id: String, field: String, value: String?) {
        val current = dao.getById(id) ?: return
        val updated = when (field) {
            "title" -> current.copy(title = value)
            "summary" -> current.copy(summary = value)
            "category" -> current.copy(category = value)
            else -> error("updateField: unsupported field $field")
        }
        dao.update(
            updated.copy(
                userEditedFields = updated.userEditedFields + field,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun delete(id: String) {
        dao.deleteById(id)
    }

    suspend fun search(query: String): List<InboxItem> {
        if (query.isBlank()) return emptyList()
        // FTS5 MATCH のためにユーザー入力を簡易サニタイズ
        val sanitized = query.replace("\"", "").let { "\"$it\"" }
        return dao.searchFts(sanitized)
    }
}
