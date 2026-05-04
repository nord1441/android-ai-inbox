# Inbox search: IME composition handling

## Problem

The Inbox search field (`InboxScreen.kt`) currently binds
`OutlinedTextField(value = state.filter.query, onValueChange =
viewModel::onQueryChanged)`. With the `String`-based overload, Compose
forwards every IME composition update — including unconfirmed romaji,
unconfirmed hiragana, and the active kanji conversion candidate — to
`onValueChange`. Each call mutates `filterState.query`, which
`flatMapLatest` relays to the FTS5 / LIKE query in `InboxRepository`.

Symptoms while typing Japanese:

- Result list flickers with every keystroke as the unconfirmed text is
  treated as a search query.
- The IME's composition underline visually desyncs from the value the
  field believes it holds.
- After picking a kanji candidate, the field briefly shows duplicated
  or partial text before settling.

## Goal

Search executes only against text the user has actually committed via
the IME (or text without any composition state, such as ASCII typing
and clipboard pastes). The IME's own composition rendering must remain
intact during selection of a kanji candidate.

## Approach

Switch the search field to the `TextFieldValue`-based overload of
`OutlinedTextField` and only forward the text to the ViewModel when
the value has no active composition range.

```kotlin
var fieldValue by remember { mutableStateOf(TextFieldValue("")) }

// Keep field text in sync if the filter is reset from elsewhere
// (e.g. an "X" button on a chip in a follow-up change).
LaunchedEffect(state.filter.query) {
    if (state.filter.query != fieldValue.text) {
        fieldValue = fieldValue.copy(text = state.filter.query)
    }
}

OutlinedTextField(
    value = fieldValue,
    onValueChange = { newValue ->
        fieldValue = newValue
        if (newValue.composition == null) {
            viewModel.onQueryChanged(newValue.text)
        }
    },
    ...
)
```

`composition == null` is true exactly when the IME has no in-flight
unconfirmed range, which is the moment a candidate is picked, the user
backspaces composition away, or non-IME input arrives. ASCII typing
yields `composition == null` on every keystroke, preserving the
existing real-time feel for English users.

## Files affected

| File | Change |
|---|---|
| `app/src/main/kotlin/com/example/aiinbox/ui/inbox/InboxScreen.kt` | Add `TextFieldValue` local state, `LaunchedEffect` sync, switch the `OutlinedTextField` overload, gate `onValueChange` on `composition == null`. |

`InboxViewModel.onQueryChanged` is already defensive (any-string input,
trimming and FTS / LIKE selection happens in `InboxRepository`) and
needs no change.

## Out of scope

- Debounce / throttle of the search flow. Japanese IME confirmation is
  itself an effective debounce; ASCII bursts hit the DB but the
  existing FTS5 path is fast enough to absorb that.
- Changes to `InboxRepository`'s LIKE-vs-FTS5 cutover or its quoting.
- Adding a clear / "X" button to the search field. (Out of scope, but
  the `LaunchedEffect` sync above is written so adding one later is
  trivial.)
- Search field outside the Inbox list (Detail screen has no search).

## Testing

Manual on the connected device:

1. Open Inbox. Tap the search field.
2. Type "あした" via Google Japanese Input (romaji "ashita"):
   - During "a", "as", "ash", "ashi", "ashit", "ashita" composition the
     result list **must not** change.
   - After confirming "あした" or selecting "明日", the list **must**
     update once.
3. Type ASCII "test": the list updates on every keystroke (existing
   behaviour preserved).
4. Backspace through a confirmed query: list updates as characters are
   deleted.
5. Pick a kanji candidate from the IME bar partway through "ashita →
   明日" — the field's visible text and underline must remain coherent
   throughout (no duplication, no flashing).

No automated test is included; Compose IME composition is awkward to
drive in instrumented tests and the change surface is small.
