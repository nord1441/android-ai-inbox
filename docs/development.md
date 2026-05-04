# Development notes

Practical things that bite you when iterating on this app on a real
device. Add to this file when something costs more than five minutes to
figure out a second time.

## Don't lose your model file on every Run

Android Studio's **Run** action defaults to **uninstall + install** for
debug builds. Uninstalling clears `/data/data/<pkg>/files/` along with
everything else, which means the 2.4 GB Gemma model gets wiped and the
in-app downloader has to re-fetch it on first launch.

Two ways out:

1. **Use `./gradlew installDebug`** (terminal). It does an upgrade install
   and leaves app data intact. Combine with `adb shell am start` if you
   want auto-launch:

       ./gradlew installDebug \
         && adb shell am start -n com.example.aiinbox.debug/com.example.aiinbox.MainActivity

2. **Disable uninstall in the Run config**. *Run/Debug Configurations →
   app → Installation Options → "Always install with package manager"
   on, "Uninstall existing APK" off*. Subsequent Runs become upgrade
   installs.

If a model does get wiped, push it back without going through the HF
downloader (which is slow and gated):

    scripts/push-model.sh ~/Downloads/gemma-4-E2B-it.litertlm

That uses `run-as` over adb to stream the file directly into the
sandboxed `files/models/` dir; see the script header for details.

## Notification permission

`POST_NOTIFICATIONS` is a runtime permission on API 33+. The app
requests it on first launch, but if you previously denied it (or never
saw the dialog because of an older app version), every FGS progress
notification, download progress notification, and completion
notification will be silently dropped — long summaries look stuck.

Toggle it from device Settings → Apps → AI Inbox → Notifications, then
reopen the app.

## Release readiness

Before cutting the first user-facing release, walk this checklist. Most
items are escape hatches that exist for fast iteration and become
liabilities once real users have data to lose.

### Database

- Pre-release iteration **does not ship migrations** for schema bumps.
  Single-developer use means the recovery path on a schema mismatch is
  "uninstall and reinstall" (Room throws `IllegalStateException` at
  `databaseBuilder().build()` time when the on-disk version is older
  than the entities and no matching migration is registered).
- Before the first user-facing release, walk the schema-version history
  in `app/schemas/com.example.aiinbox.data.db.AppDatabase/` and add a
  `MIGRATION_X_Y` for every gap that lacks one. Drop one of these into
  `Migrations.kt` per gap, register them in `SqlCipherFactory.kt`'s
  `.addMigrations(...)` call, and add a `MigrationXToYTest` under
  `app/src/androidTest/.../data/db/`. Run
  `./gradlew :app:connectedDebugAndroidTest --tests
  "com.example.aiinbox.data.db.Migration*Test"` and confirm all pass.
- The committed schema files under
  `app/schemas/com.example.aiinbox.data.db.AppDatabase/` are the source
  of truth — they must be present for every version that real users
  will ever upgrade from.

### Drive sync

- Replace the placeholder `WEB_CLIENT_ID` in
  `app/src/main/kotlin/com/example/aiinbox/sync/DriveAuthRepository.kt`
  with the real OAuth 2.0 web client ID from the production Google Cloud
  project, and register the release SHA-1 fingerprint as an Android
  variant of the same client.
- Implement the silent token refresh path (`refreshAccessTokenInternal`)
  before the first wide release; v1 ships path (b) where the user
  re-links every ~1 h.

### LLM model distribution

- Land the HF-token-aware in-app `ModelDownloadWorker` so users without
  `adb` access can download models. The current path requires
  `scripts/push-model.sh`, which is unusable for non-developers.

## Useful one-liners

    # Watch only this app's interesting tags
    adb logcat -v time SummarizeWorker:V LlmInferenceService:V \
        LiteRtLmEngine:V LlmServiceClient:V ModelDownloadWorker:V \
        MainActivity:I AndroidRuntime:E *:S

    # Inspect the on-device model directory
    adb shell 'run-as com.example.aiinbox.debug ls -la files/models/'

    # Verify the package is installed and debuggable
    adb shell 'pm path com.example.aiinbox.debug'
    adb shell 'run-as com.example.aiinbox.debug id'
