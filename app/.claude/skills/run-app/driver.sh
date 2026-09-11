#!/usr/bin/env bash
# Driver for the LedgerPrime Android app: build, install, launch, and poke it over adb.
# No emulator is assumed - this project's dev machine has no working hardware
# virtualization (see Gotchas in SKILL.md), so this driver targets a real device
# connected over USB with "USB debugging" enabled and the device UNLOCKED (adb can
# wake the screen but cannot enter a PIN/pattern/biometric).
#
# Run from the repo root (the directory containing settings.gradle.kts) or from app/ -
# it cd's to the repo root itself.
#
# Usage:
#   bash app/.claude/skills/run-app/driver.sh build              # ./gradlew assembleDebug
#   bash app/.claude/skills/run-app/driver.sh install             # install the debug APK
#   bash app/.claude/skills/run-app/driver.sh launch              # wake+start MainActivity
#   bash app/.claude/skills/run-app/driver.sh screenshot <name>   # PNG -> ./screenshots/<name>.png
#   bash app/.claude/skills/run-app/driver.sh tap <x> <y>
#   bash app/.claude/skills/run-app/driver.sh back
#   bash app/.claude/skills/run-app/driver.sh dump                # uiautomator XML -> stdout path
#   bash app/.claude/skills/run-app/driver.sh logcat              # tail app's logcat (Ctrl-C to stop)
#   bash app/.claude/skills/run-app/driver.sh stop                # force-stop the app
set -euo pipefail

# On Git Bash (MSYS), bash rewrites args that look like absolute POSIX paths (e.g.
# "/sdcard/...") into Windows paths before adb ever sees them - which breaks any
# adb command that takes an on-device path. MSYS_NO_PATHCONV=1 disables that.
export MSYS_NO_PATHCONV=1

# cd to repo root regardless of where this is invoked from.
cd "$(dirname "$0")/../../../.."
[ -f settings.gradle.kts ] || { echo "could not find repo root (settings.gradle.kts)" >&2; exit 1; }

APP_ID="com.ledgerprime.app"
MAIN_ACTIVITY="$APP_ID/com.example.MainActivity"
APK="app/build/outputs/apk/debug/app-debug.apk"
SCREENSHOT_DIR="screenshots"

cmd="${1:-}"
shift || true

require_device() {
  local n
  n=$(adb devices | grep -c "	device$" || true)
  if [ "$n" -lt 1 ]; then
    echo "no authorized adb device. Connect a phone via USB with USB debugging enabled" >&2
    echo "and accept the 'Allow USB debugging' prompt, then re-run." >&2
    adb devices
    exit 1
  fi
}

case "$cmd" in
  build)
    ./gradlew.bat assembleDebug --console=plain
    ;;
  install)
    require_device
    [ -f "$APK" ] || { echo "$APK not found - run 'build' first" >&2; exit 1; }
    adb install -r "$APK"
    ;;
  launch)
    require_device
    # Wake the screen; does NOT unlock a secured lock screen - the device must
    # already be unlocked (adb has no way to enter a PIN/pattern/biometric).
    awake=$(adb shell dumpsys power | grep -o 'mWakefulness=[A-Za-z]*' | cut -d= -f2)
    if [ "$awake" != "Awake" ]; then
      adb shell input keyevent KEYCODE_WAKEUP
      sleep 1
    fi
    locked=$(adb shell dumpsys window | grep -o 'mDreamingLockscreen=[a-z]*' | cut -d= -f2)
    if [ "$locked" = "true" ]; then
      echo "device is locked - unlock it by hand (adb cannot enter a PIN/pattern), then re-run 'launch'." >&2
      exit 1
    fi
    adb shell am start -n "$MAIN_ACTIVITY"
    sleep 5  # cold start after install/DB open needs ~4-5s, not the ~1-2s a warm start needs
    ;;
  screenshot)
    require_device
    name="${1:-screen}"
    mkdir -p "$SCREENSHOT_DIR"
    adb exec-out screencap -p > "$SCREENSHOT_DIR/$name.png"
    echo "$SCREENSHOT_DIR/$name.png"
    ;;
  tap)
    require_device
    adb shell input tap "$1" "$2"
    ;;
  swipe)
    require_device
    adb shell input swipe "$1" "$2" "$3" "$4" "${5:-300}"
    ;;
  back)
    require_device
    adb shell input keyevent KEYCODE_BACK
    ;;
  dump)
    require_device
    adb shell uiautomator dump /sdcard/window_dump.xml >/dev/null
    adb pull /sdcard/window_dump.xml ./window_dump.xml >/dev/null
    echo "./window_dump.xml"
    ;;
  logcat)
    require_device
    pid=$(adb shell pidof "$APP_ID" || true)
    if [ -z "$pid" ]; then echo "app not running - 'launch' it first" >&2; exit 1; fi
    adb logcat --pid="$pid"
    ;;
  stop)
    require_device
    adb shell am force-stop "$APP_ID"
    ;;
  *)
    echo "usage: $0 {build|install|launch|screenshot <name>|tap <x> <y>|swipe <x1> <y1> <x2> <y2>|back|dump|logcat|stop}" >&2
    exit 1
    ;;
esac
