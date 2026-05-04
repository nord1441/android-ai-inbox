# Filesystem + Markdown Inbox sync — manual exercise

End-to-end checks against a real SAF folder synced by Syncthing
between two devices. Walk these scenarios before declaring the
feature shippable.

## Setup

- Two Android devices with the debug APK signed by the same
  `debug.keystore`.
- Syncthing installed and configured to share the same folder
  between both devices (e.g. `/storage/emulated/0/Documents/Inbox`).
- POST_NOTIFICATIONS granted (so the FGS / completion notifications
  are visible — denial doesn't break sync, only its visibility).
- Both devices have a model in `files/models/` (push via
  `scripts/push-model.sh` if needed).

After setup, on each device: Settings → 「同期フォルダを選ぶ」 →
pick the Syncthing-shared folder.

## Scenarios

### A — First export

On device A, open the app and Settings → 「今すぐ同期」.
Expect: in the Syncthing folder, a `2026-XX-XX-<id>.md` per existing
inbox item plus an `attachments/` directory with binaries.

### B — First import

On device B, open the app and Settings → 「今すぐ同期」 (this happens
automatically on app launch but the manual button removes timing
ambiguity).
Expect: the items A exported appear in B's inbox list within seconds
of Syncthing finishing its sync.

### C — Round-trip a new share

1. Device A: share an image to AI Inbox; wait for the completion
   notification.
2. Device A: confirm a new `.md` appeared in the synced folder.
3. Wait for Syncthing to mirror to device B.
4. Device B: open the app (which auto-syncs on launch).
5. Expect: the new item with summary, tags, and image is in B's
   inbox.

### D — Round-trip a delete

1. Device A: open the item, tap delete.
2. Device A: confirm the corresponding `.md` is rewritten with
   `status: DELETED` (open in any text editor / Obsidian).
3. Device B: sync.
4. Expect: the item disappears from B's inbox list.

### E — External edit is ignored

1. On device A, open one of the exported `.md` files in Obsidian.
2. Change the body to "EDITED EXTERNALLY" and save.
3. Device A: 「今すぐ同期」.
4. Expect: A's inbox is unchanged. The `.md` may be overwritten back
   to its previous body on the next export pass (we don't propagate
   edits) — confirm via diff if you care to see this.

### F — SAF permission revoked

1. On device A, system settings → AI Inbox → Permissions →
   「ファイルとメディア」 → revoke.
2. Open the app, Settings, 「今すぐ同期」.
3. Expect: error state appears; 「フォルダを変更」 button is
   available; re-picking restores function.
