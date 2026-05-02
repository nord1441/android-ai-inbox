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
        every { repo.observeAll() } returns flow

        val vm = InboxViewModel(repo)
        vm.uiState.test {
            assertThat(awaitItem().items).isEmpty()
            flow.value = listOf(sampleItem("1"))
            assertThat(awaitItem().items).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun sampleItem(id: String) = InboxItem(
        id = id, originalText = "x",
        originalSubject = null, sourceApp = null,
        receivedAt = 1L, status = ItemStatus.COMPLETED, updatedAt = 1L,
    )
}
