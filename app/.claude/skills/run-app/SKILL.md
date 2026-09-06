---
name: run-app
description: Build, install, launch, and drive the LedgerPrime Android app (Kotlin/Compose). Use when asked to run the app, build the APK, take a screenshot of a screen, or confirm a change works on a real device or emulator.
---

LedgerPrime is an offline-first double-entry accounting app for Android (Kotlin, Jetpack
Compose, Room) - no login, works with zero connectivity. Drive it via
`.claude/skills/run-app/driver.sh` over `adb`, targeting a physical device (this dev machine
has no working hardware virtualization - see Gotchas). All paths below are relative to the
repo root (the directory with `settings.gradle.kts`), one level up from `app/`.

## Prerequisites

- Android SDK with `platform-tools` (`adb`) and `build-tools` on `PATH`; `ANDROID_HOME` set.
- JDK 21 (`java -version`).
- Git Bash / a POSIX shell to run `driver.sh` (this is a Windows dev machine; PowerShell can't
  run the driver directly - use the Bash tool / Git Bash).
- A real Android device (minSdk 24) connected over USB with **Developer options -> USB
  debugging** on, the "Allow USB debugging" prompt accepted, and the **device unlocked**
  (adb can wake the screen but cannot enter a PIN/pattern/biometric).

## Build

```bash
bash app/.claude/skills/run-app/driver.sh build
# -> ./gradlew.bat assembleDebug ; APK at app/build/outputs/apk/debug/app-debug.apk
```

## Run (agent path)

```bash
bash app/.claude/skills/run-app/driver.sh install              # adb install -r the debug APK
bash app/.claude/skills/run-app/driver.sh launch                # wake device, start MainActivity
bash app/.claude/skills/run-app/driver.sh screenshot home       # -> screenshots/home.png
```

| command | what it does |
|---|---|
| `build` | `gradlew assembleDebug` |
| `install` | `adb install -r` the debug APK |
| `launch` | wakes the screen (fails loudly if the lock screen is still up) and starts `MainActivity`; waits 5s for cold start |
| `screenshot <name>` | `adb exec-out screencap -p` -> `screenshots/<name>.png` |
| `tap <x> <y>` | `adb shell input tap` |
| `swipe <x1> <y1> <x2> <y2> [ms]` | `adb shell input swipe` |
| `back` | `KEYCODE_BACK` |
| `dump` | `uiautomator dump` -> pulls `window_dump.xml` to repo root (grep it for `text="..."` to find tap targets, instead of guessing coordinates from a screenshot) |
| `logcat` | tails logcat filtered to the app's PID (Ctrl-C to stop) |
| `stop` | `am force-stop` the app |

Screen coordinates are device-resolution-specific - run `dump` (or take a screenshot) after
each navigation rather than hardcoding taps from a different device's coordinates. Verified
flow on a 720x1600 device: home dashboard's bottom nav "Reports" tab is at roughly `(651,
1451)` - re-derive for any other device via `dump`.

## Run (human path)

Open the project in Android Studio and hit Run - same APK, same device. No different from the
agent path in substance; useless headless.

## Test

```bash
cd app && ./gradlew.bat testDebugUnitTest --console=plain
```

---

## Gotchas

- **No emulator on this machine.** The installed AVD (`Pixel_9_Pro`, x86_64 image) needs
  hardware virtualization; `Get-WindowsOptionalFeature` for Hypervisor Platform requires
  elevation this session didn't have, so it's unconfirmed whether it's even enabled. Forcing
  software fallback (`-accel off`) doesn't just run slow - it hangs (observed: 0.2s of CPU
  time burned over 20 minutes of wall clock, `adb` stuck reporting the device `offline`).
  Don't burn time retrying accel-off; use a real device instead, per `driver.sh`.
- **`adb` cannot unlock a secured lock screen.** `launch` will wake the screen but exits with
  an error if `mDreamingLockscreen=true` in `adb shell dumpsys window` - the phone owner has
  to unlock it by hand first (PIN/pattern/biometric can't be scripted).
- **Git Bash silently mangles `adb` device-side paths.** MSYS rewrites args that look like
  absolute POSIX paths (`/sdcard/...`) into Windows paths before `adb` sees them, so
  `adb pull /sdcard/window_dump.xml` fails with `failed to stat remote object
  'C:/Program Files/Git/sdcard/...'`. `driver.sh` sets `MSYS_NO_PATHCONV=1` up front - do the
  same in any ad hoc `adb shell`/`adb pull`/`adb push` command you type by hand on this shell.
- **Cold start needs ~5s, not ~2s.** Right after `install` (or `stop` then `launch`), a
  screenshot taken 3s after `am start` can still show a blank screen while Room finishes
  opening the DB - `driver.sh` sleeps 5s; don't shorten it.
- **`applicationId` != the Kotlin package.** The manifest's `.MainActivity` resolves against
  `namespace = "com.example"` (build.gradle.kts), giving activity class
  `com.example.MainActivity` - but `adb install`/`am start` need the `applicationId`,
  `com.aistudio.accounting.prodpk` (also from build.gradle.kts). The launch component is
  `com.aistudio.accounting.prodpk/com.example.MainActivity` (already baked into `driver.sh`).
