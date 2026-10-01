#!/usr/bin/env bash
# Build, sign, install Delta, and put the accessibility service back.
#
# Installing over the top of a package that owns an enabled accessibility service
# makes the system drop that service from the enabled set, so a plain
# `adb install -r` leaves the phone with the key integration silently dead. This
# re-adds it and waits until it is actually bound, which is the only state that
# matters: "enabled" in settings is not the same as connected.
set -euo pipefail

PKG=com.brl.blurrmbtg
SERVICE=$PKG/com.blurr.voice.ScreenInteractionService
BT="$HOME/Android/Sdk/build-tools/36.0.0"
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"

APK="$PROJECT/app/build/outputs/apk/debug/app-debug.apk"
ALIGNED=/tmp/delta-aligned.apk
SIGNED=/tmp/delta-signed.apk

"$BT/zipalign" -f -p 4 "$APK" "$ALIGNED"
"$BT/apksigner" sign \
  --ks "$HOME/.android/delta-release.jks" \
  --ks-pass "file:$HOME/.android/delta-release.pass" \
  --ks-key-alias delta \
  --out "$SIGNED" "$ALIGNED"

adb install -r "$SIGNED" | tail -1

current="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
case ":$current:" in
  *":$SERVICE:"*) echo "service already enabled" ;;
  *)
    adb shell settings put secure enabled_accessibility_services "$current:$SERVICE"
    adb shell settings put secure accessibility_enabled 1
    echo "re-enabled $SERVICE"
    ;;
esac

# Launching un-stops the package, which is what lets the system bind the service.
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

for _ in $(seq 1 15); do
  if adb shell dumpsys accessibility 2>/dev/null | grep -q "Binding services:.*$PKG"; then
    echo "accessibility service bound"
    exit 0
  fi
  sleep 1
done

echo "WARNING: $SERVICE did not bind. Check: adb shell dumpsys accessibility | grep -A3 'Enabled services'" >&2
exit 1
