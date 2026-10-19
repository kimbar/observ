# observ: notes for Claude

Android app (Kotlin + Jetpack Compose) for timestamped experiment observations.
**DESIGN.md is the agreed specification**: read it before changing behaviour, and update it
when the user agrees to a change.

## Layout

- `app/src/main/java/com/kimbar/observ/model/`: pure Kotlin time model (no Android imports).
  `TestTime` computes test time from averaged syncs; `TestOps` is the only place that changes
  a Test; `Report` writes events.txt/syncs.txt; `Fmt`/`Wheels` format times.
- `data/`: `PhoneClock` (captures Moments), `TestStore` (folders, JSON, atomic writes), `Exporter`.
- `media/`: `CameraController` (CameraX), `WavRecorder`, `VideoFrames`.
- `ui/`: `HomeScreen` (Tests), `TestScreen`, `ItemScreen`, `SyncsScreen`, shared `Components`.
- Unit tests: `app/src/test/java/com/kimbar/observ/model/`.

## Local workflow (Windows, the user's machine)

The user runs Claude Code locally with the Android SDK from Android Studio and either the
emulator or their Samsung Galaxy A56 connected over USB (USB debugging). Use `gradlew.bat`
from cmd/PowerShell (or `./gradlew` from Git Bash).

```
gradlew testDebugUnitTest            # model unit tests, fast, no device
gradlew assembleDebug                # -> app/build/outputs/apk/debug/app-debug.apk
adb devices                          # must list exactly one "device"
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.kimbar.observ.debug/com.kimbar.observ.MainActivity
```

Debug builds have the id **`com.kimbar.observ.debug`** and are named "observ debug". They
install next to the user's release app (`com.kimbar.observ`). Never install, uninstall or
clear data of `com.kimbar.observ` (the release app holds real Tests) unless the user asks.

### Driving the app and looking at it

```
adb exec-out screencap -p > shots/name.png          # screenshot (then Read the PNG)
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml shots/ui.xml
                                                    # view tree with bounds of every element
adb shell input tap X Y
adb shell input swipe X1 Y1 X2 Y2 300               # e.g. digit wheels, scrolling
adb shell input text "hello%sworld"                 # %s = space
adb shell input keyevent KEYCODE_BACK
adb shell wm size                                   # screen size in pixels
```

Find tap coordinates from `ui.xml` bounds rather than guessing from screenshots. `shots/` is
git-ignored.

Test data lives in the app's external files dir:

```
adb shell ls /sdcard/Android/data/com.kimbar.observ.debug/files/tests/
adb pull /sdcard/Android/data/com.kimbar.observ.debug/files/tests/<test>/events.txt shots/
```

Reset the debug app: `adb shell pm clear com.kimbar.observ.debug` (grant camera/microphone
again afterwards, or `adb shell pm grant com.kimbar.observ.debug android.permission.CAMERA`
and `... android.permission.RECORD_AUDIO`).

### Checking a change

1. `gradlew testDebugUnitTest` must pass.
2. Build, install, and walk through the affected screens with screenshots.
3. For timing changes, check events.txt/syncs.txt against what was done.
4. Show the user the relevant screenshots before committing.

## CI and releases

GitHub Actions (`.github/workflows/build.yml`) builds the signed release APK and publishes
it as the `latest` release on every push to `main`. The repo is on a free plan, so CI minutes
are limited: test locally first, and put `[skip ci]` in commit messages for changes that do
not need a new release (docs, work in progress).

## Conventions

- Commit only when asked; never commit signing material (`*.jks`, `keystore.properties`).
- Keep all test-time logic in `model/` and covered by unit tests.
