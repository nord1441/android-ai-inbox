package com.example.aiinbox.ui.detail

import androidx.lifecycle.SavedStateHandle
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
class DetailViewModelTest {
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test
    fun `loads item by id from save state`() = runTest {
        val flow = MutableStateFlow<InboxItem?>(null)
        val repo: InboxRepository = mockk()
        every { repo.observeById("abc") } returns flow

        val vm = DetailViewModel(repo, SavedStateHandle(mapOf(DetailViewModel.NAV_ARG_ID to "abc")))
        vm.uiState.test {
            assertThat(awaitItem().item).isNull()
            flow.value = InboxItem(
                id = "abc", originalText = "x",
                originalSubject = null, sourceApp = null,
                receivedAt = 1L, status = ItemStatus.COMPLETED, updatedAt = 1L,
            )
            assertThat(awaitItem().item?.id).isEqualTo("abc")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
