package uk.nordtek.aiinbox.ui.inbox

import app.cash.turbine.test
import uk.nordtek.aiinbox.data.db.InboxItem
import uk.nordtek.aiinbox.data.db.InboxItemWithAttachments
import uk.nordtek.aiinbox.data.db.ItemStatus
import uk.nordtek.aiinbox.data.repository.InboxRepository
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
        val wrappedFlow = MutableStateFlow<List<InboxItemWithAttachments>>(emptyList())
        val allFlow = MutableStateFlow<List<InboxItem>>(emptyList())
        val repo: InboxRepository = mockk()
        every { repo.observeFilteredWithAttachments(any()) } returns wrappedFlow
        every { repo.observeAll() } returns allFlow

        val vm = InboxViewModel(repo)
        vm.uiState.test {
            assertThat(awaitItem().items).isEmpty()
            wrappedFlow.value = listOf(sampleItemWithAttachments("1"))
            assertThat(awaitItem().items).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `query change reflects in filter state`() = runTest {
        val wrappedFlow = MutableStateFlow<List<InboxItemWithAttachments>>(emptyList())
        val allFlow = MutableStateFlow<List<InboxItem>>(emptyList())
        val repo: InboxRepository = mockk()
        every { repo.observeFilteredWithAttachments(any()) } returns wrappedFlow
        every { repo.observeAll() } returns allFlow

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
        val wrappedFlow = MutableStateFlow<List<InboxItemWithAttachments>>(emptyList())
        val allFlow = MutableStateFlow<List<InboxItem>>(emptyList())
        val repo: InboxRepository = mockk()
        every { repo.observeFilteredWithAttachments(any()) } returns wrappedFlow
        every { repo.observeAll() } returns allFlow

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
        val wrappedFlow = MutableStateFlow<List<InboxItemWithAttachments>>(emptyList())
        val allFlow = MutableStateFlow<List<InboxItem>>(emptyList())
        val repo: InboxRepository = mockk()
        every { repo.observeFilteredWithAttachments(any()) } returns wrappedFlow
        every { repo.observeAll() } returns allFlow

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

    @Test
    fun `availableCategories and availableTags derived from observeAll`() = runTest {
        val wrappedFlow = MutableStateFlow<List<InboxItemWithAttachments>>(emptyList())
        val allFlow = MutableStateFlow<List<InboxItem>>(emptyList())
        val repo: InboxRepository = mockk()
        every { repo.observeFilteredWithAttachments(any()) } returns wrappedFlow
        every { repo.observeAll() } returns allFlow

        val vm = InboxViewModel(repo)
        vm.uiState.test {
            skipItems(1)
            allFlow.value = listOf(
                sampleItem("1").copy(category = "work", tags = listOf("urgent", "")),
                sampleItem("2").copy(category = "personal", tags = listOf("urgent", "home")),
                sampleItem("3").copy(category = null, tags = emptyList()),
            )
            var s = awaitItem()
            while (s.availableCategories.isEmpty()) {
                s = awaitItem()
            }
            assertThat(s.availableCategories).containsExactly("work", "personal")
            assertThat(s.availableTags).containsExactly("urgent", "home")
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun sampleItem(id: String) = InboxItem(
        id = id, originalText = "x",
        originalSubject = null, sourceApp = null,
        receivedAt = 1L, status = ItemStatus.COMPLETED, updatedAt = 1L,
    )

    private fun sampleItemWithAttachments(id: String) =
        InboxItemWithAttachments(item = sampleItem(id), attachments = emptyList())
}
