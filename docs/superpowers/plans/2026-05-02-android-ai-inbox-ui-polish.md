# Android AI Inbox — Plan 3: UI Polish & Full Feature 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Plan 1 / 2 で構築した動くアプリに、スペックで定義した完成形のUI機能（検索、フィルタ、編集、再要約、削除Undo、設定画面、高度な通知）を載せる。

**Architecture:** 既存の `InboxRepository` に検索・フィルタクエリを追加。`InboxViewModel` / `DetailViewModel` をフルアクション対応に拡張。通知ヘルパに「イベント検出時のカレンダーアクション」「グルーピング」を追加。`SettingsScreen` を新規追加。

**Tech Stack:** 既存 (Plan 1, 2) のみ。新規依存なし。

**前提:** Plan 1 / 2 が完了し、すべてのテストがパス、実Gemma 4でShare→要約→詳細表示→カレンダー連携が動く状態。

**スペックリンク:** [`docs/superpowers/specs/2026-05-02-android-ai-inbox-design.md`](../specs/2026-05-02-android-ai-inbox-design.md)

---

## このPlanの完成条件（Definition of Done）

1. ✅ Inbox画面に検索バー（FTS5全文検索）と、カテゴリ × タグ × 「📅イベントあり」フィルタチップ
2. ✅ 検索/フィルタが`Flow`でリアクティブに反映、追加処理を待たず即座に絞り込まれる
3. ✅ 詳細画面で `title` / `summary` / `category` / `tags` / `people` / `places` を編集可能
4. ✅ 編集された項目は `userEditedFields` に記録され、再要約しても上書きされない
5. ✅ 詳細画面の「再要約」アクションでLLM再実行（編集済みフィールドは保持）
6. ✅ 詳細画面の「削除」アクションで5秒Undo付きで削除（Snackbar）
7. ✅ イベント検出時の通知に「📅 カレンダーに追加」アクションボタン → Intent直接起動
8. ✅ 複数件が短時間に完了した時、`groupKey` で通知が束ねられる
9. ✅ 設定画面でモデル状態（バージョン、サイズ、再DLボタン）/ DB使用量 / バージョン情報が確認できる
10. ✅ 空状態・エラー状態（FAILED）の見た目が洗練されている
11. ✅ Plan 1 / 2 の全テストが引き続きパス、Plan 3新規テストもパス

---

## ファイル構成（Plan 3で作成・編集するファイル）

```
android-ai-inbox/
├── app/src/main/
│   ├── AndroidManifest.xml                                [編集] (Calendar action receiver)
│   ├── kotlin/com/example/aiinbox/
│   │   ├── data/
│   │   │   ├── db/InboxDao.kt                             [編集] (フィルタクエリ追加)
│   │   │   └── repository/InboxRepository.kt              [編集] (observeFiltered, undo支援)
│   │   ├── notification/
│   │   │   ├── NotificationHelper.kt                      [編集] (event action, grouping)
│   │   │   └── CalendarActionReceiver.kt                  [新規]
│   │   ├── ui/
│   │   │   ├── inbox/
│   │   │   │   ├── InboxFilter.kt                         [新規]
│   │   │   │   ├── InboxUiState.kt                        [編集]
│   │   │   │   ├── InboxViewModel.kt                      [編集]
│   │   │   │   └── InboxScreen.kt                         [編集] (search bar + filter chips)
│   │   │   ├── detail/
│   │   │   │   ├── DetailUiState.kt                       [編集]
│   │   │   │   ├── DetailViewModel.kt                     [編集] (edit/reprocess/delete)
│   │   │   │   └── DetailScreen.kt                        [編集] (editable fields, undo)
│   │   │   ├── settings/
│   │   │   │   ├── SettingsViewModel.kt                   [新規]
│   │   │   │   └── SettingsScreen.kt                      [新規]
│   │   │   └── navigation/Routes.kt                       [編集] (settings)
│   ├── res/values/strings.xml                             [編集]
└── app/src/{test,androidTest}/...                         [新規 多数]
```

---

## Task 1: 検索/フィルタ用の DAO クエリ拡張

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/db/InboxDao.kt`
- Test: `app/src/androidTest/kotlin/com/example/aiinbox/data/db/InboxDaoFilterTest.kt`

- [ ] **Step 1: DAO に Flow 検索とフィルタを追加**

`InboxDao.kt` に追記：
```kotlin
@Query(
    """
    SELECT * FROM inbox_items
    WHERE (:hasEventOnly = 0 OR event_title IS NOT NULL)
    ORDER BY received_at DESC
    """
)
fun observeFiltered(hasEventOnly: Int): Flow<List<InboxItem>>

@Query(
    """
    SELECT i.* FROM inbox_items i
    JOIN inbox_fts f ON f.id = i.id
    WHERE inbox_fts MATCH :query
      AND (:hasEventOnly = 0 OR i.event_title IS NOT NULL)
    ORDER BY i.received_at DESC
    """
)
fun observeSearch(query: String, hasEventOnly: Int): Flow<List<InboxItem>>
```

**Note:** カテゴリやタグでの絞り込みは Repository 側で Kotlinフィルタすると十分高速（手元のレコード数想定で問題なし）、SQLを複雑にしない。

- [ ] **Step 2: AndroidTest**

```kotlin
package uk.nordtek.aiinbox.data.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import uk.nordtek.aiinbox.data.crypto.KeystorePassphraseProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxDaoFilterTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var dao: InboxDao

    @Before fun setup() {
        ctx.deleteDatabase("inbox.db")
        db = buildEncryptedDatabase(ctx, KeystorePassphraseProvider(ctx))
        dao = db.inboxDao()
    }
    @After fun teardown() { db.close(); ctx.deleteDatabase("inbox.db") }

    private fun item(id: String, hasEvent: Boolean = false) = InboxItem(
        id = id, originalText = "本文$id",
        originalSubject = null, sourceApp = null, receivedAt = id.hashCode().toLong(),
        status = ItemStatus.COMPLETED, summary = "summary $id", title = "t$id",
        event = if (hasEvent) ExtractedEvent("ev$id", null, null, null, 0.5f) else null,
        updatedAt = 0L,
    )

    @Test
    fun `observeFiltered hasEventOnly returns only items with event`() = runBlocking {
        dao.insert(item("a", hasEvent = false))
        dao.insert(item("b", hasEvent = true))
        dao.observeFiltered(hasEventOnly = 1).test {
            assertThat(awaitItem().map { it.id }).containsExactly("b")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeSearch with hasEvent filter`() = runBlocking {
        dao.insert(item("a", hasEvent = false).copy(summary = "東京の話"))
        dao.insert(item("b", hasEvent = true).copy(summary = "東京の打ち合わせ"))
        dao.observeSearch("東京", hasEventOnly = 1).test {
            assertThat(awaitItem().map { it.id }).containsExactly("b")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 3: テスト実行**

```bash
./gradlew :app:connectedDebugAndroidTest --tests uk.nordtek.aiinbox.data.db.InboxDaoFilterTest
```
Expected: PASS

- [ ] **Step 4: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/data/db/InboxDao.kt \
        app/src/androidTest/kotlin/com/example/aiinbox/data/db/InboxDaoFilterTest.kt
git commit -m "feat(data): add filtered observation and search queries to InboxDao"
```

---

## Task 2: InboxFilter + InboxRepository 拡張

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/ui/inbox/InboxFilter.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/repository/InboxRepository.kt`
- Test: `app/src/androidTest/kotlin/com/example/aiinbox/data/repository/InboxRepositoryFilterTest.kt`

- [ ] **Step 1: `InboxFilter.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.ui.inbox

data class InboxFilter(
    val query: String = "",
    val categories: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val hasEventOnly: Boolean = false,
) {
    val isEmpty: Boolean
        get() = query.isBlank() && categories.isEmpty() && tags.isEmpty() && !hasEventOnly
}
```

- [ ] **Step 2: `InboxRepository.kt` に `observeFiltered` を追加**

```kotlin
import uk.nordtek.aiinbox.ui.inbox.InboxFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun observeFiltered(filter: InboxFilter): Flow<List<InboxItem>> {
    val hasEventInt = if (filter.hasEventOnly) 1 else 0
    val baseFlow = if (filter.query.isBlank()) {
        dao.observeFiltered(hasEventInt)
    } else {
        val sanitized = "\"${filter.query.replace("\"", "")}\""
        dao.observeSearch(sanitized, hasEventInt)
    }
    return baseFlow.map { list ->
        list.filter { item ->
            (filter.categories.isEmpty() || item.category in filter.categories) &&
            (filter.tags.isEmpty() || item.tags.any { it in filter.tags })
        }
    }
}
```

- [ ] **Step 3: 既存の `observeAll` を残しつつ、Plan 3 では新APIを使う**

`observeAll()` は引き続きそのまま残す（テスト互換性）。ViewModel側で `observeFiltered(InboxFilter())` を使う。

- [ ] **Step 4: AndroidTest を追加**

```kotlin
package uk.nordtek.aiinbox.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import uk.nordtek.aiinbox.data.crypto.KeystorePassphraseProvider
import uk.nordtek.aiinbox.data.db.AppDatabase
import uk.nordtek.aiinbox.data.db.buildEncryptedDatabase
import uk.nordtek.aiinbox.ui.inbox.InboxFilter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxRepositoryFilterTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var repo: InboxRepository

    @Before fun setup() {
        ctx.deleteDatabase("inbox.db")
        db = buildEncryptedDatabase(ctx, KeystorePassphraseProvider(ctx))
        repo = InboxRepository(db.inboxDao())
    }
    @After fun teardown() { db.close(); ctx.deleteDatabase("inbox.db") }

    @Test
    fun `category filter narrows results`() = runBlocking {
        val id1 = repo.createPendingItem("a", null, null)
        val id2 = repo.createPendingItem("b", null, null)
        // 直接DAO経由でカテゴリ更新
        db.inboxDao().getById(id1)?.copy(category = "仕事")?.let { db.inboxDao().update(it) }
        db.inboxDao().getById(id2)?.copy(category = "個人")?.let { db.inboxDao().update(it) }

        repo.observeFiltered(InboxFilter(categories = setOf("仕事"))).test {
            assertThat(awaitItem().map { it.id }).containsExactly(id1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `tag filter matches any tag`() = runBlocking {
        val id1 = repo.createPendingItem("a", null, null)
        db.inboxDao().getById(id1)?.copy(tags = listOf("食事", "東京"))?.let { db.inboxDao().update(it) }

        repo.observeFiltered(InboxFilter(tags = setOf("東京"))).test {
            assertThat(awaitItem().map { it.id }).containsExactly(id1)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 5: テスト実行**

```bash
./gradlew :app:connectedDebugAndroidTest --tests uk.nordtek.aiinbox.data.repository.InboxRepositoryFilterTest
```
Expected: PASS

- [ ] **Step 6: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/data/repository/InboxRepository.kt \
        app/src/main/kotlin/com/example/aiinbox/ui/inbox/InboxFilter.kt \
        app/src/androidTest/kotlin/com/example/aiinbox/data/repository/InboxRepositoryFilterTest.kt
git commit -m "feat(data): add InboxFilter and observeFiltered to repository"
```

---

## Task 3: InboxViewModel に検索・フィルタ操作を追加

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/inbox/InboxUiState.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/inbox/InboxViewModel.kt`

- [ ] **Step 1: `InboxUiState.kt` を更新**

```kotlin
package uk.nordtek.aiinbox.ui.inbox

import uk.nordtek.aiinbox.data.db.InboxItem

data class InboxUiState(
    val items: List<InboxItem> = emptyList(),
    val loading: Boolean = true,
    val filter: InboxFilter = InboxFilter(),
    val availableCategories: Set<String> = emptySet(),
    val availableTags: Set<String> = emptySet(),
)
```

- [ ] **Step 2: `InboxViewModel.kt` を更新**

```kotlin
package uk.nordtek.aiinbox.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import uk.nordtek.aiinbox.data.repository.InboxRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val repository: InboxRepository,
) : ViewModel() {

    private val filterState = MutableStateFlow(InboxFilter())

    private val itemsFlow = filterState
        .flatMapLatest { filter -> repository.observeFiltered(filter) }

    private val allItemsFlow = repository.observeAll() // 利用可能カテゴリ/タグ抽出用

    val uiState: StateFlow<InboxUiState> = combine(
        itemsFlow,
        allItemsFlow,
        filterState,
    ) { items, allItems, filter ->
        InboxUiState(
            items = items,
            loading = false,
            filter = filter,
            availableCategories = allItems.mapNotNull { it.category }.toSet(),
            availableTags = allItems.flatMap { it.tags }.toSet(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = InboxUiState(loading = true),
    )

    fun onQueryChanged(query: String) {
        filterState.value = filterState.value.copy(query = query)
    }

    fun onCategoryToggled(category: String) {
        val cur = filterState.value.categories
        filterState.value = filterState.value.copy(
            categories = if (category in cur) cur - category else cur + category
        )
    }

    fun onTagToggled(tag: String) {
        val cur = filterState.value.tags
        filterState.value = filterState.value.copy(
            tags = if (tag in cur) cur - tag else cur + tag
        )
    }

    fun onHasEventToggled() {
        filterState.value = filterState.value.copy(hasEventOnly = !filterState.value.hasEventOnly)
    }

    fun onClearFilter() {
        filterState.value = InboxFilter()
    }
}
```

- [ ] **Step 3: ViewModelTest を追加**

```kotlin
// 既存 InboxViewModelTest.kt に追加
@Test
fun `query change reflects in filter state`() = runTest {
    val flow = MutableStateFlow<List<InboxItem>>(emptyList())
    val repo: InboxRepository = mockk()
    every { repo.observeFiltered(any()) } returns flow
    every { repo.observeAll() } returns flow

    val vm = InboxViewModel(repo)
    vm.uiState.test {
        skipItems(1) // 初期値
        vm.onQueryChanged("hello")
        val s = awaitItem()
        assertThat(s.filter.query).isEqualTo("hello")
        cancelAndIgnoreRemainingEvents()
    }
}
```

- [ ] **Step 4: テスト実行**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.ui.inbox.InboxViewModelTest
```
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/ui/inbox/ \
        app/src/test/kotlin/com/example/aiinbox/ui/inbox/
git commit -m "feat(ui): add query and filter state management to InboxViewModel"
```

---

## Task 4: InboxScreen に検索バー + フィルタチップを追加

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/inbox/InboxScreen.kt`

- [ ] **Step 1: `InboxScreen.kt` を更新**

```kotlin
package uk.nordtek.aiinbox.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.nordtek.aiinbox.R
import uk.nordtek.aiinbox.data.db.InboxItem
import uk.nordtek.aiinbox.data.db.ItemStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onItemClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Inbox") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "設定")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            OutlinedTextField(
                value = state.filter.query,
                onValueChange = viewModel::onQueryChanged,
                placeholder = { Text("検索…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            FilterChipsRow(state, viewModel)

            if (state.items.isEmpty() && !state.loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (state.filter.isEmpty)
                            stringResourceCompat(R.string.inbox_empty)
                        else "条件に一致するアイテムがありません"
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        InboxItemCard(item = item, onClick = { onItemClick(item.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipsRow(state: InboxUiState, vm: InboxViewModel) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = state.filter.hasEventOnly,
                onClick = { vm.onHasEventToggled() },
                label = { Text("📅 イベントあり") },
            )
        }
        items(state.availableCategories.toList()) { cat ->
            FilterChip(
                selected = cat in state.filter.categories,
                onClick = { vm.onCategoryToggled(cat) },
                label = { Text(cat) },
            )
        }
        items(state.availableTags.toList()) { tag ->
            FilterChip(
                selected = tag in state.filter.tags,
                onClick = { vm.onTagToggled(tag) },
                label = { Text("#$tag") },
            )
        }
    }
}

@Composable
private fun InboxItemCard(item: InboxItem, onClick: () -> Unit) {
    Card(modifier = Modifier.clickable { onClick() }) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = item.title ?: item.originalText.take(40),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.summary ?: "(処理待ち...)",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                if (item.status == ItemStatus.PENDING || item.status == ItemStatus.PROCESSING) {
                    StatusChip("処理中")
                }
                if (item.status == ItemStatus.FAILED) StatusChip("失敗")
                if (item.event != null) StatusChip("📅")
                item.category?.let { StatusChip(it) }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String) {
    AssistChip(
        onClick = {},
        label = { Text(text) },
        colors = AssistChipDefaults.assistChipColors(),
    )
}

@Composable
private fun stringResourceCompat(id: Int): String =
    androidx.compose.ui.res.stringResource(id)
```

- [ ] **Step 2: ビルド確認**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/ui/inbox/InboxScreen.kt
git commit -m "feat(ui): add search bar and filter chips to InboxScreen"
```

---

## Task 5: DetailViewModel に編集・再要約・削除アクション

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/detail/DetailUiState.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/detail/DetailViewModel.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/repository/InboxRepository.kt`

- [ ] **Step 1: `InboxRepository.kt` に list 系編集 + 削除Undo支援を追加**

```kotlin
suspend fun updateListField(id: String, field: String, values: List<String>) {
    val current = dao.getById(id) ?: return
    val updated = when (field) {
        "tags" -> current.copy(tags = values)
        "people" -> current.copy(people = values)
        "places" -> current.copy(places = values)
        else -> error("updateListField: unsupported field $field")
    }
    dao.update(
        updated.copy(
            userEditedFields = updated.userEditedFields + field,
            updatedAt = System.currentTimeMillis(),
        )
    )
}

/** 削除Undoのために、deleteで一旦アイテムを保持しておくバッファ */
private val deletedBuffer = java.util.concurrent.ConcurrentHashMap<String, InboxItem>()

suspend fun softDelete(id: String): Boolean {
    val item = dao.getById(id) ?: return false
    deletedBuffer[id] = item
    dao.deleteById(id)
    return true
}

suspend fun restoreDeleted(id: String): Boolean {
    val item = deletedBuffer.remove(id) ?: return false
    dao.insert(item)
    return true
}

fun finalizeDelete(id: String) {
    deletedBuffer.remove(id)
}
```

- [ ] **Step 2: `DetailUiState.kt` を更新**

```kotlin
package uk.nordtek.aiinbox.ui.detail

import uk.nordtek.aiinbox.data.db.InboxItem

data class DetailUiState(
    val item: InboxItem? = null,
    val loading: Boolean = true,
    val deleted: Boolean = false,            // 削除Undoがアクティブな状態
    val errorMessage: String? = null,
)
```

- [ ] **Step 3: `DetailViewModel.kt` を拡張**

```kotlin
package uk.nordtek.aiinbox.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import uk.nordtek.aiinbox.data.repository.InboxRepository
import uk.nordtek.aiinbox.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: InboxRepository,
    private val workScheduler: WorkScheduler,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val itemId: String = checkNotNull(savedStateHandle[NAV_ARG_ID])
    private val deletedFlow = MutableStateFlow(false)
    private val errorFlow = MutableStateFlow<String?>(null)

    val uiState: StateFlow<DetailUiState> = combine(
        repository.observeById(itemId),
        deletedFlow,
        errorFlow,
    ) { item, deleted, err ->
        DetailUiState(
            item = item,
            loading = item == null && !deleted,
            deleted = deleted,
            errorMessage = err,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DetailUiState(loading = true),
    )

    fun onEditField(field: String, value: String?) {
        viewModelScope.launch {
            try {
                repository.updateField(itemId, field, value)
            } catch (t: Throwable) {
                errorFlow.value = t.message
            }
        }
    }

    fun onEditListField(field: String, values: List<String>) {
        viewModelScope.launch {
            try {
                repository.updateListField(itemId, field, values)
            } catch (t: Throwable) {
                errorFlow.value = t.message
            }
        }
    }

    fun onReprocess() {
        viewModelScope.launch {
            workScheduler.enqueueSummarize(itemId)
        }
    }

    fun onDelete() {
        viewModelScope.launch {
            if (repository.softDelete(itemId)) {
                deletedFlow.value = true
                // 5秒待ってfinalize（Undoが押されなければ）
                delay(5_000)
                if (deletedFlow.value) {
                    repository.finalizeDelete(itemId)
                }
            }
        }
    }

    fun onUndoDelete() {
        viewModelScope.launch {
            if (repository.restoreDeleted(itemId)) {
                deletedFlow.value = false
            }
        }
    }

    fun clearError() {
        errorFlow.value = null
    }

    companion object {
        const val NAV_ARG_ID = "id"
    }
}
```

- [ ] **Step 4: 既存のDetailViewModelTestを更新（コンストラクタ引数追加）**

`DetailViewModelTest.kt`：
```kotlin
val workScheduler: WorkScheduler = mockk(relaxed = true)
val vm = DetailViewModel(repo, workScheduler, SavedStateHandle(mapOf(DetailViewModel.NAV_ARG_ID to "abc")))
```

ほか、編集・削除のテストを追加：
```kotlin
@Test
fun `onEditField calls repository updateField`() = runTest {
    val repo: InboxRepository = mockk(relaxed = true)
    every { repo.observeById("abc") } returns MutableStateFlow(null)
    val vm = DetailViewModel(repo, mockk(relaxed = true), SavedStateHandle(mapOf(DetailViewModel.NAV_ARG_ID to "abc")))
    vm.onEditField("title", "新タイトル")
    coVerify { repo.updateField("abc", "title", "新タイトル") }
}
```

- [ ] **Step 5: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/ui/detail/ \
        app/src/main/kotlin/com/example/aiinbox/data/repository/InboxRepository.kt \
        app/src/test/kotlin/com/example/aiinbox/ui/detail/
git commit -m "feat(ui): add edit/reprocess/delete actions to DetailViewModel"
```

---

## Task 6: DetailScreen — 編集UI、再要約、削除Undo

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/detail/DetailScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: `strings.xml` に追加**

```xml
<string name="action_reprocess">再要約</string>
<string name="action_delete">削除</string>
<string name="action_undo">取り消す</string>
<string name="snackbar_deleted">削除しました</string>
<string name="confirm_reprocess_title">再要約しますか？</string>
<string name="confirm_reprocess_body">編集済みの項目は保護されます。</string>
```

- [ ] **Step 2: `DetailScreen.kt` を編集UI対応に書き直し**

```kotlin
package uk.nordtek.aiinbox.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.nordtek.aiinbox.R
import uk.nordtek.aiinbox.calendar.CalendarIntentBuilder
import uk.nordtek.aiinbox.data.db.ExtractedEvent
import uk.nordtek.aiinbox.data.db.InboxItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showReprocessDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.deleted) {
        if (state.deleted) {
            val res = snackbar.showSnackbar(
                ctx.getString(R.string.snackbar_deleted),
                actionLabel = ctx.getString(R.string.action_undo),
            )
            when (res) {
                SnackbarResult.ActionPerformed -> viewModel.onUndoDelete()
                SnackbarResult.Dismissed -> onBack()
            }
        }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(state.item?.title ?: "詳細") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { showReprocessDialog = true }) {
                        Icon(Icons.Default.Refresh, contentDescription = ctx.getString(R.string.action_reprocess))
                    }
                    IconButton(onClick = { viewModel.onDelete() }) {
                        Icon(Icons.Default.Delete, contentDescription = ctx.getString(R.string.action_delete))
                    }
                },
            )
        },
    ) { padding ->
        val item = state.item ?: return@Scaffold

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item.event?.let { ev ->
                EventCard(event = ev) {
                    val intent = CalendarIntentBuilder.build(
                        event = ev,
                        summary = item.summary,
                        originalTextSnippet = item.originalText.take(500),
                    )
                    ctx.startActivity(intent)
                }
            }

            EditableField(
                label = "タイトル",
                value = item.title ?: "",
                onChange = { viewModel.onEditField("title", it) },
            )
            EditableField(
                label = "要約",
                value = item.summary ?: "",
                onChange = { viewModel.onEditField("summary", it) },
                singleLine = false,
            )
            EditableField(
                label = "カテゴリ",
                value = item.category ?: "",
                onChange = { viewModel.onEditField("category", it) },
            )
            EditableListField(
                label = "タグ",
                values = item.tags,
                onChange = { viewModel.onEditListField("tags", it) },
            )
            EditableListField(
                label = "人物",
                values = item.people,
                onChange = { viewModel.onEditListField("people", it) },
            )
            EditableListField(
                label = "場所",
                values = item.places,
                onChange = { viewModel.onEditListField("places", it) },
            )

            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("原文", modifier = Modifier.padding(bottom = 4.dp))
                    Text(item.originalText)
                }
            }
        }
    }

    if (showReprocessDialog) {
        AlertDialog(
            onDismissRequest = { showReprocessDialog = false },
            title = { Text(ctx.getString(R.string.confirm_reprocess_title)) },
            text = { Text(ctx.getString(R.string.confirm_reprocess_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showReprocessDialog = false
                    viewModel.onReprocess()
                }) { Text("再要約") }
            },
            dismissButton = {
                TextButton(onClick = { showReprocessDialog = false }) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun EditableField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EditableListField(
    label: String,
    values: List<String>,
    onChange: (List<String>) -> Unit,
) {
    OutlinedTextField(
        value = values.joinToString(", "),
        onValueChange = { input ->
            onChange(input.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        },
        label = { Text("$label (カンマ区切り)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EventCard(event: ExtractedEvent, onAddToCalendar: () -> Unit) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("📅 " + event.title)
            event.startMillis?.let {
                Text("開始: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)))
            }
            event.location?.let { Text("場所: $it") }
            if (event.confidence < 0.6f) {
                Text("⚠ 自動抽出の信頼度が低めです（要確認）")
            }
            Button(onClick = onAddToCalendar) {
                Text(LocalContext.current.getString(R.string.add_to_calendar))
            }
        }
    }
}
```

- [ ] **Step 3: ビルド確認**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/ui/detail/DetailScreen.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(ui): add editable fields, reprocess, and delete-with-undo to DetailScreen"
```

---

## Task 7: NotificationHelper — イベント検出時のカレンダーアクション

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/notification/NotificationHelper.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/notification/CalendarActionReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: `CalendarActionReceiver.kt` を作成（通知アクションタップ受け）**

```kotlin
package uk.nordtek.aiinbox.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import uk.nordtek.aiinbox.calendar.CalendarIntentBuilder
import uk.nordtek.aiinbox.data.db.ExtractedEvent
import uk.nordtek.aiinbox.data.repository.InboxRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CalendarActionReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: InboxRepository

    override fun onReceive(context: Context, intent: Intent) {
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val item = repository.getById(itemId)
                val event = item?.event
                if (item != null && event != null) {
                    val calIntent = CalendarIntentBuilder.build(
                        event = event,
                        summary = item.summary,
                        originalTextSnippet = item.originalText.take(500),
                    )
                    context.startActivity(calIntent)
                }
                if (notifId >= 0) NotificationManagerCompat.from(context).cancel(notifId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "uk.nordtek.aiinbox.ADD_TO_CALENDAR"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_NOTIF_ID = "notif_id"
    }
}
```

- [ ] **Step 2: `AndroidManifest.xml` に Receiver 登録**

```xml
<receiver
    android:name=".notification.CalendarActionReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="uk.nordtek.aiinbox.ADD_TO_CALENDAR"/>
    </intent-filter>
</receiver>
```

- [ ] **Step 3: `NotificationHelper.kt` を拡張**

```kotlin
package uk.nordtek.aiinbox.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import uk.nordtek.aiinbox.MainActivity
import uk.nordtek.aiinbox.R
import uk.nordtek.aiinbox.data.db.InboxItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val groupKey = "ai_inbox_group"

    fun showCompletion(item: InboxItem) {
        NotificationChannels.ensureCreated(context)
        val notifId = item.id.hashCode()
        val title = item.title?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.notification_summary_complete_title)
        val text = item.summary ?: ""
        val hasEvent = item.event != null
        val channel = if (hasEvent)
            NotificationChannels.CHANNEL_EVENT_DETECTED
        else NotificationChannels.CHANNEL_SUMMARY_COMPLETE

        val contentPI = openItemPendingIntent(item.id, notifId)

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentPI)
            .setAutoCancel(true)
            .setGroup(groupKey)
            .setPriority(if (hasEvent) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)

        if (hasEvent) {
            val addPI = calendarActionPendingIntent(item.id, notifId)
            builder.addAction(
                android.R.drawable.ic_menu_my_calendar,
                context.getString(R.string.add_to_calendar),
                addPI,
            )
        }

        runCatching {
            val nm = NotificationManagerCompat.from(context)
            nm.notify(notifId, builder.build())
            // グループサマリ
            nm.notify(GROUP_SUMMARY_ID, buildGroupSummary())
        }
    }

    private fun openItemPendingIntent(itemId: String, notifId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_OPEN_ITEM_ID, itemId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun calendarActionPendingIntent(itemId: String, notifId: Int): PendingIntent {
        val intent = Intent(CalendarActionReceiver.ACTION).apply {
            setPackage(context.packageName)
            putExtra(CalendarActionReceiver.EXTRA_ITEM_ID, itemId)
            putExtra(CalendarActionReceiver.EXTRA_NOTIF_ID, notifId)
        }
        return PendingIntent.getBroadcast(
            context,
            notifId xor 0xCAFE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildGroupSummary() = NotificationCompat.Builder(
        context, NotificationChannels.CHANNEL_SUMMARY_COMPLETE
    )
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(context.getString(R.string.app_name))
        .setStyle(
            NotificationCompat.InboxStyle()
                .setSummaryText(context.getString(R.string.app_name))
        )
        .setGroup(groupKey)
        .setGroupSummary(true)
        .build()

    companion object {
        const val EXTRA_OPEN_ITEM_ID = "open_item_id"
        private const val GROUP_SUMMARY_ID = 0x0001
    }
}
```

- [ ] **Step 4: ビルド確認**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/notification/ \
        app/src/main/AndroidManifest.xml
git commit -m "feat(notification): add calendar action button and group summary"
```

---

## Task 8: SettingsViewModel + SettingsScreen

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/ui/settings/SettingsUiState.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/ui/settings/SettingsViewModel.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/navigation/Routes.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/MainActivity.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/inbox/InboxScreen.kt` (Settings IconButton)
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: `strings.xml`**

```xml
<string name="settings_title">設定</string>
<string name="settings_model_status">モデル状態</string>
<string name="settings_model_redownload">再ダウンロード</string>
<string name="settings_db_size">データベース使用量</string>
<string name="settings_version">バージョン</string>
```

- [ ] **Step 2: `Routes.kt` を更新**

```kotlin
object Routes {
    const val INBOX = "inbox"
    const val DETAIL = "detail/{id}"
    const val MODEL_DOWNLOAD = "model_download"
    const val SETTINGS = "settings"
    fun detail(id: String) = "detail/$id"
}
```

- [ ] **Step 3: `SettingsUiState.kt` / `SettingsViewModel.kt`**

```kotlin
package uk.nordtek.aiinbox.ui.settings

import uk.nordtek.aiinbox.llm.ModelVariant

data class SettingsUiState(
    val currentVariant: ModelVariant? = null,
    val modelSizeBytes: Long = 0,
    val dbSizeBytes: Long = 0,
    val versionName: String = "",
)
```

```kotlin
package uk.nordtek.aiinbox.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import uk.nordtek.aiinbox.BuildConfig
import uk.nordtek.aiinbox.llm.ModelManager
import uk.nordtek.aiinbox.work.ModelDownloadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val modelManager: ModelManager,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val variant = modelManager.currentVariant()
            val modelSize = variant?.let { modelManager.modelFilePath(it).length() } ?: 0L
            val dbSize = withContext(Dispatchers.IO) {
                getApplication<Application>().getDatabasePath("inbox.db").length()
            }
            _state.value = SettingsUiState(
                currentVariant = variant,
                modelSizeBytes = modelSize,
                dbSizeBytes = dbSize,
                versionName = BuildConfig.VERSION_NAME,
            )
        }
    }

    fun onRedownload() {
        viewModelScope.launch {
            val variant = _state.value.currentVariant ?: uk.nordtek.aiinbox.llm.RamDetector
                .selectVariantForDevice(getApplication())
            modelManager.deleteModel(variant)
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(Data.Builder().putString(ModelDownloadWorker.KEY_VARIANT, variant.name).build())
                .build()
            WorkManager.getInstance(getApplication())
                .enqueueUniqueWork("model_dl_${variant.name}", androidx.work.ExistingWorkPolicy.REPLACE, request)
        }
    }
}
```

- [ ] **Step 4: `SettingsScreen.kt`**

```kotlin
package uk.nordtek.aiinbox.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.nordtek.aiinbox.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(LocalContext_getString(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "戻る") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(LocalContext_getString(R.string.settings_model_status))
                    Text("バリアント: ${s.currentVariant?.name ?: "未DL"}")
                    Text("ファイルサイズ: ${s.modelSizeBytes / 1024 / 1024} MB")
                    Button(onClick = viewModel::onRedownload) {
                        Text(LocalContext_getString(R.string.settings_model_redownload))
                    }
                }
            }
            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(LocalContext_getString(R.string.settings_db_size))
                    Text("${s.dbSizeBytes / 1024} KB")
                }
            }
            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(LocalContext_getString(R.string.settings_version))
                    Text(s.versionName)
                }
            }
        }
    }
}

@Composable
private fun LocalContext_getString(id: Int): String =
    androidx.compose.ui.res.stringResource(id)
```

- [ ] **Step 5: `MainActivity.kt` の NavHost に Settings を追加**

```kotlin
import uk.nordtek.aiinbox.ui.settings.SettingsScreen

// NavHost 内
composable(Routes.SETTINGS) {
    SettingsScreen(onBack = { nav.popBackStack() })
}

// InboxScreen の callback で nav.navigate(Routes.SETTINGS) を渡す
composable(Routes.INBOX) {
    InboxScreen(
        onItemClick = { id -> nav.navigate(Routes.detail(id)) },
        onSettingsClick = { nav.navigate(Routes.SETTINGS) },
    )
}
```

- [ ] **Step 6: ビルド確認**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/ui/settings/ \
        app/src/main/kotlin/com/example/aiinbox/ui/navigation/Routes.kt \
        app/src/main/kotlin/com/example/aiinbox/MainActivity.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(ui): add SettingsScreen with model status and re-download"
```

---

## Task 9: 空状態・エラー状態（FAILED）の見た目改善

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/inbox/InboxScreen.kt`

- [ ] **Step 1: FAILEDアイテムには「再要約」ボタンを直接出す**

`InboxItemCard` 内：
```kotlin
if (item.status == ItemStatus.FAILED) {
    Row {
        StatusChip("失敗")
        // タップで詳細を開いて再要約させる、または直接Worker起動するボタン
    }
}
```

詳細仕様: 失敗時はカードに赤系の薄い背景 + 「タップして再要約」テキスト。実装は Material3 の `CardColors.containerColor` を `errorContainer` 系に。

```kotlin
val cardColors = if (item.status == ItemStatus.FAILED) {
    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
} else CardDefaults.cardColors()

Card(modifier = Modifier.clickable { onClick() }, colors = cardColors) { ... }
```

- [ ] **Step 2: ビルド + コミット**

```bash
./gradlew :app:assembleDebug
git add app/src/main/kotlin/com/example/aiinbox/ui/inbox/InboxScreen.kt
git commit -m "feat(ui): style failed items with errorContainer background"
```

---

## Task 10: 手動E2E検証 + Plan 3 完了

- [ ] **Step 1: 全テストPASS確認**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```
Expected: 全PASS

- [ ] **Step 2: 手動検証チェックリスト**

- [ ] 検索バーに「東京」と入力 → リアルタイムに絞り込み
- [ ] 「📅イベントあり」チップタップ → イベント検出済みのみ残る
- [ ] カテゴリチップ複数選択 → AND/OR動作確認（実装はOR）
- [ ] 詳細画面で要約を編集 → 戻る → 再要約タップ → 編集した要約が保持される
- [ ] 詳細画面のタイトルを編集 → タグを追加 → 戻る → 再度開いて反映確認
- [ ] 詳細画面で削除 → Snackbar「削除しました」「取り消す」 → 取り消すタップ → アイテム復活
- [ ] 削除 → 5秒待つ → アイテムが完全消滅
- [ ] イベント検出された通知に「📅 カレンダーに追加」アクションボタン → タップで標準カレンダー起動
- [ ] 短時間に5件Share → 通知がグループにまとまる
- [ ] 設定画面 → モデルバリアント・サイズ・バージョン確認
- [ ] 設定画面の「再ダウンロード」タップ → モデル削除されてDL開始
- [ ] LLMを意図的に失敗させる（例: 巨大入力）→ アイテムカードがエラー色 → タップで詳細 → 再要約

- [ ] **Step 3: Plan 3完了マーカーコミット**

```bash
git commit --allow-empty -m "milestone(plan-3): UI polish and full feature complete

- Inbox: 検索バー(FTS5) + フィルタチップ(category × tags × イベントあり)
- Repository.observeFiltered() でリアクティブ絞り込み
- Detail: 全フィールド編集 (title/summary/category/tags/people/places)
- userEditedFields による再要約時の編集保護を活用
- 5秒Snackbar Undo 付き削除
- 再要約アクション (確認ダイアログ付き)
- 通知: イベント検出時の「カレンダーに追加」アクションボタン
- 通知のgroupKey束ね + サマリ通知
- 設定画面: モデル状態・DB使用量・再DL・バージョン
- FAILEDアイテムのエラーコンテナ色強調

これでスペック「Android AI Inbox」のMVP完成。
次のフェーズ候補: セマンティック検索、TODO/Key Points抽出、BiometricPrompt起動ロック など。
"
```

---

## Plan 3 自己チェック

**1. スペックカバレッジ:**
- ✅ FTS5検索 + フィルタチップUI (Tasks 1, 2, 3, 4)
- ✅ 詳細画面の編集 (Tasks 5, 6)
- ✅ userEditedFields 再要約保護 (既存 InboxRepository / Plan 1で実装、本Planで活用)
- ✅ 削除Undo (Tasks 5, 6)
- ✅ 再要約アクション (Tasks 5, 6)
- ✅ 通知のカレンダーアクションボタン (Task 7)
- ✅ 通知グルーピング (Task 7)
- ✅ 設定画面 (Task 8)
- ✅ FAILED状態の表示 (Task 9)

**スペック対応で漏れていそうな項目の確認:**
- `confidence` が低いイベントの警告アイコン → Task 6 の `EventCard` で実装
- `event` 抽出時の `start_iso` が日付のみ→終日扱い → Plan 1 / Plan 2 で対応済み
- `LlmInferenceService` の通知アイコンタップ→アプリ起動 → Plan 2 で `Foreground` 通知のContentIntent未設定。MVP範囲では Service の通知は単なる状態表示でタップ非アクティブで許容（必要なら本Plan後に追加）。

**2. プレースホルダ走査:** TBD/TODOなし。

**3. 型整合性:**
- `InboxFilter` (Task 2) のシグネチャは Task 3 / 4 で一貫
- `Repository.observeFiltered(filter)` / `observeAll()` 両方を残し、Plan 1 のテストとも互換
- `Repository.softDelete` / `restoreDeleted` / `finalizeDelete` は Task 5 で導入、Plan 1 の既存 `delete()` も残置（互換）
- `userEditedFields` のキー文字列は Task 5 で `"title", "summary", "category", "tags", "people", "places"` を使用、Plan 1 の `applySummarizeResult` でこれらキーで保護判定済み
- `CalendarActionReceiver.ACTION` は Task 7 で定義、`AndroidManifest.xml` の intent-filter と一致

---

## 実行ハンドオフ

Plan 3完成、 `docs/superpowers/plans/2026-05-02-android-ai-inbox-ui-polish.md` に保存。Plan 1 → Plan 2 の完了後に本Plan を実行する。

実行方法は2択：
**1. Subagent-Driven (推奨)** — タスクごとに新規subagent + タスク間レビュー
**2. Inline Execution** — `executing-plans` skillでバッチ実行

3つのPlanが揃ったので、ユーザーに実行方法を確認する。
