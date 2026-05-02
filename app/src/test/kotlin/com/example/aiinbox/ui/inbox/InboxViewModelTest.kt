package com.example.aiinbox.ui.inbox

import app.cash.turbine.test
import com.example.aiinbox.data.db.InboxItem
import com.example.aiinbox.data.db.ItemStatus
import com.example.aiinbox.data.repository.InboxRepository
import com.google.common.truth.Truth.assertThat
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
class InboxViewModelTest {
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test
    fun `state reflects repository emissions`() = runTest {
        val flow = MutableStateFlow<List<InboxItem>>(emptyList())
        val repo: InboxRepository = mockk()
        every { repo.observeFiltered(any()) } returns flow
        every { repo.observeAll() } returns flow

        val vm = InboxViewModel(repo)
        vm.uiState.test {
            assertThat(awaitItem().items).isEmpty()
            flow.value = listOf(sampleItem("1"))
            assertThat(awaitItem().items).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `query change reflects in filter state`() = runTest {
        val flow = MutableStateFlow<List<InboxItem>>(emptyList())
        val repo: InboxRepository = mockk()
        every { repo.observeFiltered(any()) } returns flow
        every { repo.observeAll() } returns flow

        val vm = InboxViewModel(repo)
        vm.uiState.test {
            skipItems(1) // 初期値 (loading = true)
            vm.onQueryChanged("hello")
            var seen = awaitItem()
            while (seen.filter.query != "hello") {
                seen = awaitItem()
            }
            assertThat(seen.filter.query).isEqualTo("hello")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `category toggle reflects in filter state`() = runTest {
        val flow = MutableStateFlow<List<InboxItem>>(emptyList())
        val repo: InboxRepository = mockk()
        every { repo.observeFiltered(any()) } returns flow
        every { repo.observeAll() } returns flow

        val vm = InboxViewModel(repo)
        vm.uiState.test {
            skipItems(1)
            vm.onCategoryToggled("work")
            var seen = awaitItem()
            while (!seen.filter.categories.contains("work")) {
                seen = awaitItem()
            }
            assertThat(seen.filter.categories).contains("work")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hasEvent toggle reflects in filter state`() = runTest {
        val flow = MutableStateFlow<List<InboxItem>>(emptyList())
        val repo: InboxRepository = mockk()
        every { repo.observeFiltered(any()) } returns flow
        every { repo.observeAll() } returns flow

        val vm = InboxViewModel(repo)
        vm.uiState.test {
            skipItems(1)
            vm.onHasEventToggled()
            var seen = awaitItem()
            while (!seen.filter.hasEventOnly) {
                seen = awaitItem()
            }
            assertThat(seen.filter.hasEventOnly).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun sampleItem(id: String) = InboxItem(
        id = id, originalText = "x",
        originalSubject = null, sourceApp = null,
        receivedAt = 1L, status = ItemStatus.COMPLETED, updatedAt = 1L,
    )
}
