# Drive sync — manual two-device exercise

The two-device exercise is the only end-to-end check on the sync stack
because the apply-phase unit tests / SyncWorkerSmokeTest were deferred
(see commit `e341a58` for rationale). Walk these scenarios before
declaring the feature shippable; capture defects as new TODOs and
re-run after fixing.

## Setup

- **Two Android devices** (or one device + one emulator) running the
  debug APK signed by the same `debug.keystore`. If the second device
  was set up from a host other than the one that built the APK, copy
  the keystore over so APKs install as upgrades — see
  `docs/development.md → "Don't lose your model file on every Run"`
  for the keystore-sharing procedure.
- **Both devices linked to the same Google account.** A real OAuth 2.0
  Web client ID has been pasted into
  `DriveAuthRepository.WEB_CLIENT_ID` and the SHA-1 of the
  `debug.keystore` has been added as an Android variant of that
  client. Without this the link button just errors out.
- The **POST_NOTIFICATIONS** permission is granted on each device (so
  the sync FGS / completion notifications are visible — denial doesn't
  break sync, only its visibility).

After setup, do this once on each device to clear stale state:

    adb shell run-as com.example.aiinbox.debug pm clear-data com.example.aiinbox.debug
    # then re-launch and re-link Drive in Settings

(Or just uninstall + reinstall + re-push the model with
`scripts/push-model.sh`. The `pm clear-data` route is faster.)

## Scenarios

### A — Initial link, push side

On device A:

1. Settings → 「Driveをリンク」.
2. Pick the Google account → grant the "App data folder" consent.
3. Confirm Settings shows **「リンク済み: <email>」** and a 「リンク解除」
   button.
4. Watch `adb logcat -s SyncWorker` while tapping 「今すぐ同期」.
5. Status should briefly show 「同期中…」 then 「アイドル」.

Expected: the very first sync run creates `manifest.json` (empty
items list), no errors in logcat.

Verify on the web: https://drive.google.com/drive/u/0/settings → "Manage
apps" → AI Inbox should now show a non-zero "Hidden app data" usage.

### B — Initial link, pull side

On device B (with a fresh local DB, no items):

1. Link Drive to the same Google account.
2. Tap 「今すぐ同期」.

Expected: any items shared on device A in earlier scenarios appear in
the Inbox list within 30 s. Attachment thumbnails render
(EncryptedFile decryption succeeded against the locally re-encrypted
bytes).

### C — Round-trip a new share

1. **Device A:** share a screenshot or text via the OS share sheet to
   AI Inbox. Wait for the completion notification (~50 s on the
   Snapdragon-720G test device).
2. **Device B:** open the app (which triggers `maybeKickOffSync` from
   `MainActivity.onCreate`) or tap 「今すぐ同期」 in Settings.

Expected: the new item with its summary, tags, and image appears
within ~30 s. `adb logcat -s SyncWorker` on B shows a successful pull.

### D — Round-trip a delete

1. **Device A:** open the item in detail and tap delete (the trash
   icon). It disappears from the local Inbox immediately.
2. **Device A:** tap 「今すぐ同期」 in Settings (or wait for the next
   periodic tick) so the tombstone propagates.
3. **Device B:** open the app and trigger a sync.

Expected: the item disappears from device B's Inbox within 30 s. The
tombstone (item row with `deleted_at` set) survives in both DBs until
the daily TombstoneGcWorker physically removes it after 30 days.

### E — Wi-Fi off / on

1. **Device A:** turn off Wi-Fi and mobile data, share an image.
2. The summarize will run locally; the post-summarize sync trigger
   will fail at the network layer.
3. Settings should show **「エラー: …」** (the `SyncState.Cause.Other`
   path; specific message depends on OkHttp's IOException text).
4. Re-enable Wi-Fi.
5. Tap 「今すぐ同期」 in Settings.

Expected: status flips to 「同期中…」 → 「アイドル」 and the item
appears on device B within 30 s.

### F — Re-auth

1. Visit https://myaccount.google.com/permissions on the same Google
   account.
2. Find the entry for the AI Inbox OAuth client and revoke its access.
3. **Device A:** tap 「今すぐ同期」 in Settings.

Expected: Settings shows **「エラー: 再リンクしてください」** (the
`SyncState.Cause.ReauthRequired` path). The link state still shows
the email — `unlink` was not called automatically.

4. Tap 「リンク解除」 then 「Driveをリンク」 and re-grant the consent.
5. Tap 「今すぐ同期」 again.

Expected: a normal sync run completes. Status returns to 「アイドル」.

## Known v1 limitations to be aware of during testing

- **No silent token refresh.** After ~50 minutes of access-token
  freshness the next sync will land in scenario F's reauth flow. v2
  will hit `https://oauth2.googleapis.com/token` with the stored
  refresh token; v1 ships the manual re-link path. See
  `docs/development.md → Release readiness → Drive sync`.
- **Attachment binaries are uploaded as plain bytes** (Drive's
  server-side encryption is the confidentiality boundary). If you
  audit the `appData` folder in the web UI you can see filenames like
  `attachments/<uuid>.bin` — the bytes are decrypted on the way out
  of `EncryptedImageStore.readBytes` and re-encrypted into the local
  store on the way in via `writeWithName` (which uses the receiving
  device's Android Keystore). This is by design (see the Q1 decision
  in the design spec).
- **Apply-phase unit tests are deferred.** The diff is unit-tested
  but the apply-phase (network + DB + filesystem orchestration) is
  only verified by this exercise. Treat scenarios C and D as the
  load-bearing checks on the apply path.
