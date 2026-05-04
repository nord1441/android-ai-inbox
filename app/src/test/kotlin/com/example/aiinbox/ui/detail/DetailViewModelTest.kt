package com.example.aiinbox.ui.detail

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.example.aiinbox.data.db.InboxItem
import com.example.aiinbox.data.db.InboxItemWithAttachments
import com.example.aiinbox.data.db.ItemStatus
import com.example.aiinbox.data.repository.InboxRepository
import com.example.aiinbox.sync.FsSyncCoordinator
import com.example.aiinbox.work.WorkScheduler
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun teardown() { Dispatchers.resetMain() }

    private fun newVm(
        repo: InboxRepository,
        ws: WorkScheduler = mockk(relaxed = true),
        coordinator: FsSyncCoordinator = mockk(relaxed = true),
    ) = DetailViewModel(repo, ws, coordinator, SavedStateHandle(mapOf(DetailViewModel.NAV_ARG_ID to "abc")))

    private fun wrap(item: InboxItem) = InboxItemWithAttachments(item, emptyList())

    @Test
    fun `loads item by id from save state`() = runTest {
        val flow = MutableStateFlow<InboxItemWithAttachments?>(null)
        val repo: InboxRepository = mockk(relaxed = true)
        every { repo.observeItemWithAttachments("abc") } returns flow

        val vm = newVm(repo)
        vm.uiState.test {
            assertThat(awaitItem().item).isNull()
            flow.value = wrap(
                InboxItem(
                    id = "abc", originalText = "x",
                    originalSubject = null, sourceApp = null,
                    receivedAt = 1L, status = ItemStatus.COMPLETED, updatedAt = 1L,
                )
            )
            assertThat(awaitItem().item?.id).isEqualTo("abc")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onEditField calls repository updateField`() = runTest {
        val repo: InboxRepository = mockk(relaxed = true)
        every { repo.observeItemWithAttachments("abc") } returns MutableStateFlow(null)
        val vm = newVm(repo)
        vm.onEditField("title", "新タイトル")
        coVerify { repo.updateField("abc", "title", "新タイトル") }
    }

    @Test
    fun `onEditListField calls repository updateListField`() = runTest {
        val repo: InboxRepository = mockk(relaxed = true)
        every { repo.observeItemWithAttachments("abc") } returns MutableStateFlow(null)
        val vm = newVm(repo)
        vm.onEditListField("tags", listOf("a", "b"))
        coVerify { repo.updateListField("abc", "tags", listOf("a", "b")) }
    }

    @Test
    fun `onReprocess enqueues summarize work`() = runTest {
        val repo: InboxRepository = mockk(relaxed = true)
        every { repo.observeItemWithAttachments("abc") } returns MutableStateFlow(null)
        val ws: WorkScheduler = mockk(relaxed = true)
        val vm = newVm(repo, ws)
        vm.onReprocess()
        coVerify { ws.enqueueSummarize("abc") }
    }

    @Test
    fun `onDelete sets deleted state`() = runTest {
        val repo: InboxRepository = mockk(relaxed = true)
        every { repo.observeItemWithAttachments("abc") } returns MutableStateFlow(null)
        coEvery { repo.softDelete("abc") } returns true
        val vm = newVm(repo)
        vm.uiState.test {
            skipItems(1)
            vm.onDelete()
            var s = awaitItem()
            while (!s.deleted) s = awaitItem()
            assertThat(s.deleted).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onUndoDelete clears deleted state`() = runTest {
        val repo: InboxRepository = mockk(relaxed = true)
        every { repo.observeItemWithAttachments("abc") } returns MutableStateFlow(null)
        coEvery { repo.softDelete("abc") } returns true
        coEvery { repo.restoreDeleted("abc") } returns true
        val vm = newVm(repo)
        vm.uiState.test {
            skipItems(1)
            vm.onDelete()
            var s = awaitItem()
            while (!s.deleted) s = awaitItem()
            vm.onUndoDelete()
            while (s.deleted) s = awaitItem()
            assertThat(s.deleted).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
