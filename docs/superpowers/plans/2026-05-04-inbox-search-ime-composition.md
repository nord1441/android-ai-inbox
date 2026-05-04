# Inbox search IME composition handling — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the Inbox search field from re-querying the FTS / LIKE backend on every Japanese-IME composition update; only forward confirmed text to the ViewModel.

**Architecture:** Switch the single `OutlinedTextField` in `InboxScreen.kt` from the `String`-based overload to the `TextFieldValue`-based one, hold a local `TextFieldValue` state, and gate the `viewModel::onQueryChanged` call on `composition == null`. Keep the local state in sync with `state.filter.query` via `LaunchedEffect` so external resets (e.g. a future "clear filter" button) propagate.

**Tech Stack:** Jetpack Compose Material3, `androidx.compose.ui.text.input.TextFieldValue`.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `app/src/main/kotlin/com/example/aiinbox/ui/inbox/InboxScreen.kt` | Inbox list screen + search input | Modify the search `OutlinedTextField` only |

`InboxViewModel.onQueryChanged` is already correct (accepts any String, downstream handles filtering) — no change.

There is no test file in scope. The change is verified manually on the connected device per the spec; Compose IME composition is impractical to drive from instrumented tests for this surface.

---

## Task 1: Switch search field to TextFieldValue with composition gating

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/inbox/InboxScreen.kt`

- [ ] **Step 1: Add the required imports**

Locate the import block at the top of `InboxScreen.kt` (currently ending around line 48 with `import com.example.aiinbox.data.db.ItemStatus`). Add these four imports in alphabetical order within the existing `androidx.compose.*` group:

```kotlin
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
```

Note: `getValue` (already imported from `androidx.lifecycle.compose.collectAsStateWithLifecycle` use) is needed again for `by remember` delegation. If you see `import androidx.compose.runtime.getValue` already present, do not duplicate it.

- [ ] **Step 2: Replace the search `OutlinedTextField` block**

In `InboxScreen.kt` find this block (currently lines ~74–81):

```kotlin
            OutlinedTextField(
                value = state.filter.query,
                onValueChange = viewModel::onQueryChanged,
                placeholder = { Text(stringResource(R.string.inbox_search_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
```

Replace it with:

```kotlin
            var fieldValue by remember { mutableStateOf(TextFieldValue(state.filter.query)) }
            // Re-sync if the query is reset from outside the field (e.g. a future
            // clear-filter button). Cheap to keep here even before such a button
            // exists — it's the seam that makes adding one trivial.
            LaunchedEffect(state.filter.query) {
                if (state.filter.query != fieldValue.text) {
                    fieldValue = fieldValue.copy(text = state.filter.query)
                }
            }
            OutlinedTextField(
                value = fieldValue,
                onValueChange = { newValue ->
                    fieldValue = newValue
                    // Only forward to the search backend once the IME has no
                    // unconfirmed composition range. ASCII typing leaves
                    // composition == null on every keystroke so real-time search
                    // for English is preserved.
                    if (newValue.composition == null) {
                        viewModel.onQueryChanged(newValue.text)
                    }
                },
                placeholder = { Text(stringResource(R.string.inbox_search_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
```

The `var fieldValue by remember { mutableStateOf(TextFieldValue(state.filter.query)) }` line uses `by` delegation, which is why `getValue` and `setValue` from `androidx.compose.runtime` are imported in Step 1.

- [ ] **Step 3: Compile via Android Studio**

In Android Studio, **Build → Make Project** (or `./gradlew :app:assembleDebug` from the terminal if Java is on PATH). Expected: build succeeds. If it fails with "unresolved reference: TextFieldValue" or similar, recheck the import added in Step 1.

- [ ] **Step 4: Install the new APK on the connected device**

In Android Studio click **Run 'app'**, or from the terminal run `./gradlew :app:installDebug && adb shell am start -n com.example.aiinbox.debug/com.example.aiinbox.MainActivity`. Expected: the app launches and the Inbox screen is visible. The model file in `files/models/` is preserved across upgrade installs (unless Android Studio's Run config has "Uninstall existing APK" enabled — see `docs/development.md`).

- [ ] **Step 5: Manual verification — Japanese IME**

On the device:

1. Tap the search field.
2. Switch to Google Japanese Input (or another Japanese IME).
3. Type `ashita` slowly — the romaji-to-hiragana composition should display "あした" with an underline.
4. **Observe**: the result list **must not** flicker or change while "あした" is unconfirmed.
5. Tap the candidate "明日" from the IME bar (or hit the confirm key for "あした").
6. **Observe**: the result list updates **once**, filtering on the confirmed text.
7. Backspace through the confirmed text: the list updates as characters are deleted.

If the list updates while underlined characters are still on screen, the gate is not working; recheck Step 2's `composition == null` branch.

- [ ] **Step 6: Manual verification — ASCII typing & paste**

On the device:

1. Clear the search field.
2. Type `test` letter by letter using a Latin keyboard. Expected: the list filters on every keystroke (existing behaviour — `composition` is `null` for direct ASCII input).
3. Long-press the field and paste any string. Expected: the list updates immediately on paste.

- [ ] **Step 7: Manual verification — IME candidate switching mid-composition**

On the device:

1. Clear the search field.
2. Type `ashita` to get a kanji candidate list.
3. Use the IME's candidate-bar arrows (or tap a different candidate) to swap among 明日 / 朝日 / etc. **without** confirming.
4. **Observe**: the field's visible text and underline must remain coherent throughout — no flashing, no duplicated characters, no caret jump.
5. Confirm the chosen candidate. List updates once.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/ui/inbox/InboxScreen.kt
git commit -m "$(cat <<'EOF'
fix(inbox): gate search on confirmed IME text to stop Japanese-input flicker

The String overload of OutlinedTextField forwards every IME composition
update — unconfirmed romaji, unconfirmed hiragana, every kanji candidate
preview — to onValueChange, so filterState.query churned with each
keystroke and the FTS/LIKE backend re-ran for text the user had not
actually committed. The result list flickered on every keystroke and the
IME composition underline desynced from the field value.

Switch the field to the TextFieldValue overload, hold a local
TextFieldValue, and only call viewModel.onQueryChanged when
`composition == null` (i.e. the IME has no in-flight unconfirmed range:
candidate confirmed, composition deleted, or non-IME input like ASCII /
paste). LaunchedEffect keeps the local state in sync with
state.filter.query so external resets (e.g. a future clear button)
propagate without an extra wiring change.

Manual verification only — Compose IME composition isn't worth driving
from instrumented tests for a one-file surface; see the design spec for
the full manual test plan.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-review notes (for the implementer)

- The implementer can copy the imports as a block; the `getValue` line may already exist in the file — keep only one copy.
- If the `LaunchedEffect` is later considered overkill (no external resets exist today), it is still cheap and is documented as the seam for the planned clear-filter button. Do not remove without an accompanying plan change.
- Do not introduce a debounce here. The spec explicitly excludes it (Japanese confirmation is the natural debounce; ASCII bursts are absorbed by FTS5).
