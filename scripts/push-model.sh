#!/usr/bin/env bash
#
# Push a Gemma .litertlm model file directly into the AI Inbox debug app's
# private files/models/ directory, bypassing the in-app downloader.
#
# Why this exists:
#   - The in-app ModelDownloadWorker over Hugging Face is slow / fragile
#     for multi-GB files on flaky networks.
#   - /data/data/<pkg>/files is owned by the app's uid, so plain `adb push`
#     cannot write there. We use `run-as` (debuggable APK only).
#   - On Android 11+ the app uid often cannot read /data/local/tmp due to
#     SELinux, so we stream the file via `adb shell ... < local_file` —
#     stdin is delivered straight into run-as.
#
# Usage:
#   scripts/push-model.sh <path-to-litertlm> [variant]
#     variant: e2b | e4b   (inferred from the source filename if omitted)
#
# Environment:
#   PKG       Override target package (default: com.example.aiinbox.debug)
#   ADB       Override adb binary path (default: adb on PATH)

set -euo pipefail

PKG="${PKG:-com.example.aiinbox.debug}"
ADB="${ADB:-adb}"

usage() {
    sed -n '3,22p' "$0"
    exit 1
}

[[ $# -ge 1 ]] || usage

LOCAL_FILE="$1"
VARIANT_HINT="${2:-}"

[[ -f "$LOCAL_FILE" ]] || { echo "error: file not found: $LOCAL_FILE" >&2; exit 1; }

infer_variant() {
    local fname
    fname="$(basename "$1" | tr '[:upper:]' '[:lower:]')"
    case "$fname" in
        *e2b*) echo "e2b" ;;
        *e4b*) echo "e4b" ;;
        *)     echo "" ;;
    esac
}

if [[ -z "$VARIANT_HINT" ]]; then
    VARIANT_HINT="$(infer_variant "$LOCAL_FILE")"
    [[ -n "$VARIANT_HINT" ]] || {
        echo "error: cannot infer variant from filename. pass e2b|e4b explicitly." >&2
        exit 1
    }
fi

case "$VARIANT_HINT" in
    e2b|e4b) ;;
    *) echo "error: variant must be e2b or e4b (got: $VARIANT_HINT)" >&2; exit 1 ;;
esac

TARGET_NAME="gemma-4-${VARIANT_HINT}.litertlm"
SIZE_BYTES="$(stat -c %s "$LOCAL_FILE")"
SIZE_MB=$((SIZE_BYTES / 1024 / 1024))

echo "==> Source : $LOCAL_FILE"
echo "==> Size   : ${SIZE_MB} MB (${SIZE_BYTES} bytes)"
echo "==> Target : ${PKG}:files/models/${TARGET_NAME}"

# Sanity checks
if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "error: no adb device. Connect a device and authorize USB debugging." >&2
    exit 1
fi

if ! "$ADB" shell "pm path ${PKG}" 2>/dev/null | tr -d '\r' | grep -q '^package:'; then
    echo "error: package ${PKG} not installed on the device." >&2
    echo "       hint: \`adb shell pm list packages | grep aiinbox\` to verify." >&2
    exit 1
fi

# run-as smoke test (debuggable APK only). If this fails, the build is release.
if ! "$ADB" shell "run-as ${PKG} id" 2>&1 | tr -d '\r' | grep -q "uid="; then
    echo "error: run-as ${PKG} failed. APK must be debuggable (debug build)." >&2
    exit 1
fi

echo "==> Ensuring files/models/ exists…"
"$ADB" shell "run-as ${PKG} mkdir -p files/models"

# Stream into a .part sibling so a partial transfer never replaces a
# previously-good file.
echo "==> Streaming ${SIZE_MB} MB into the app sandbox via run-as (no progress bar; large files take minutes)…"
if command -v pv >/dev/null 2>&1; then
    pv -- "$LOCAL_FILE" | "$ADB" shell "run-as ${PKG} sh -c 'cat > files/models/${TARGET_NAME}.part'"
else
    "$ADB" shell "run-as ${PKG} sh -c 'cat > files/models/${TARGET_NAME}.part'" < "$LOCAL_FILE"
fi

echo "==> Verifying size on device…"
DEVICE_SIZE="$("$ADB" shell "run-as ${PKG} stat -c %s files/models/${TARGET_NAME}.part" | tr -d '\r')"
if [[ "$DEVICE_SIZE" != "$SIZE_BYTES" ]]; then
    echo "error: size mismatch (local=${SIZE_BYTES} device=${DEVICE_SIZE}). .part file left for inspection." >&2
    exit 1
fi

echo "==> Atomically renaming .part → final…"
"$ADB" shell "run-as ${PKG} mv files/models/${TARGET_NAME}.part files/models/${TARGET_NAME}"
"$ADB" shell "run-as ${PKG} chmod 600 files/models/${TARGET_NAME}"

echo "==> Done. Current contents of files/models/:"
"$ADB" shell "run-as ${PKG} ls -la files/models/"
