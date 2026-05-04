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
