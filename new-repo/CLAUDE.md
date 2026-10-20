# CLAUDE.md: observ

Instructions for Claude (and humans) working on this repository. Read this file fully at the
start of every session. It is written for a repository started fresh; the first prototype lives
in the old `kimbar/observ` repository and can be consulted for working code.

---

## 1. What we are building

**observ** is an Android app for recording timestamped observations during an experiment:
photos, videos, audio notes, typed notes and MARKs (one-tap "something happened now").

Every observation gets a time on the **experiment clock**: an external clock visible to the
observer that starts at 00:00 and counts up. The phone is synced to that clock by reading it
off a MARK, a photo or a video frame; several syncs are averaged. Times are kept and shown at
**0.1 s precision** for reliable **1 s accuracy**.

The app is sideloaded onto a few phones (no app store). The main target phone is a
**Samsung Galaxy A56** (6.7", 1080 × 2340, Android 15/16, One UI).

### The specification

**`DESIGN.md` is the agreed specification.** It was worked out with the user in detail.

- Read it before changing any behaviour.
- Do not "improve" behaviour it defines without asking. If something in it looks wrong or
  incomplete, raise it with the user first.
- When the user agrees to a change, update `DESIGN.md` in the same piece of work.

---

## 2. How we work together

The user's preferences, learned the hard way on the first prototype:

- **Discuss before building.** For anything beyond a small fix, first state your
  understanding and the decisions you would make, and let the user confirm or correct. The
  first prototype was built on assumptions and had to be redone.
- **Don't fill gaps with guesses.** If a requirement is ambiguous, ask a short, concrete
  question. Offer a recommendation, not a list of everything possible.
- **Say plainly what was and was not verified.** "Compiles in CI" is not "works on the
  phone". Never describe UI as checked unless you looked at a screenshot of it.
- **The user likes to understand the process.** When they ask how to do something
  (trigger a build, install, sign), explain the steps and let them do it; don't do it for
  them unless asked.
- **Commit only when asked.** When asked to commit, write a clear message describing what
  changed and why.
- **Precision language:** the user cares about timing. Be exact about what a timestamp means
  (e.g. "start of exposure", "finger touch-down"), how it is captured and its likely error.

---

## 3. Where development happens

Work is split between two kinds of sessions that share this repository through git.

| | Local session (user's Windows PC) | Cloud session (claude.ai/code) |
|---|---|---|
| Android SDK, Gradle build | yes (Android Studio's SDK) | usually no: `dl.google.com` is blocked unless allowed in the environment's network settings |
| Emulator / phone over adb | yes | no |
| Screenshots of the real UI | yes | no |
| Unit tests of `model/` | yes | only via a plain-JVM side project, or CI |
| Good for | UI work, device features, verification | design discussion, pure-Kotlin logic, docs, refactors |

Rules:

- **UI changes are finished only after a local session has looked at screenshots.** A cloud
  session may write UI code, but must say it is unverified and leave it on a branch for a
  local session to check.
- Hand-offs go through git: push a branch, describe in the commit message or PR what still
  needs checking on a device.
- Before starting, `git pull` and check `git log` for work done in the other kind of session.

---

## 4. Local setup (Windows)

Already installed on the user's machine, or to be installed once:

- **Android Studio** (provides the SDK, the emulator and a JDK).
- **Git for Windows** and **Claude Code**; optionally VS Code with the Claude Code extension.
- Environment variables (user scope):
  - `JAVA_HOME` = `C:\Program Files\Android\Android Studio\jbr`
  - `ANDROID_HOME` = `C:\Users\g.kimbar\AppData\Local\Android\Sdk`
  - `Path` += `%ANDROID_HOME%\platform-tools` and `%ANDROID_HOME%\emulator`

Check with:

```
java -version
adb version
emulator -list-avds
```

### Shell notes

- Use `gradlew.bat` in cmd/PowerShell, `./gradlew` in Git Bash.
- **Do not save binary output through PowerShell redirection.** `adb exec-out screencap -p > x.png`
  produces a corrupt PNG in PowerShell (it re-encodes the bytes). Use the two-step form in
  section 6, or run the command from cmd or Git Bash.
- Paths with spaces need quotes.

---

## 5. Devices

### 5.1 Emulator (default for UI checks)

The virtual device is set up in Android Studio's Device Manager as **`Galaxy_A56`**:
custom hardware profile, 6.7", 1080 × 2340, Android 15 (API 35) or 16 (API 36), x86_64,
Google APIs image; back and front camera = **Webcam0**.

Start it and wait until it has booted:

```
emulator -list-avds
start "" emulator -avd Galaxy_A56 -no-boot-anim
adb wait-for-device
adb shell getprop sys.boot_completed      # repeat until it prints 1
adb devices                               # must show: emulator-5554   device
```

What the emulator cannot tell you: Samsung One UI specifics, the ultra-wide lens, real
camera behaviour, real touch feel, or timing precision. Use the phone for those.

The emulator's microphone only works after enabling *Extended controls → Microphone →
"Virtual microphone uses host audio input"* (resets on every start).

### 5.2 The real phone (A56 over USB)

Developer options → **USB debugging** on; accept the prompt on the phone when plugging in.
Use it for camera, wide lens, audio, timing and final checks.

**The user's phone holds real Tests in the release app.** See section 7: only ever install
the debug build there.

### 5.3 Exactly one device

Most `adb` commands need exactly one connected device. With both connected, add
`-s emulator-5554` or `-s <phone serial>` (from `adb devices`).

### 5.4 When adb sees no device

Problems seen on this machine before, in the order to try:

1. Is the emulator actually running? `tasklist | findstr /i qemu` must show
   `qemu-system-x86_64.exe`.
2. Restart the adb server: `adb kill-server`, `adb start-server`, `adb devices`.
3. Another program may run its own adb (Samsung Smart Switch, scrcpy, other SDKs):
   `where adb`, close the others.
4. Emulator *Extended controls → Settings → General*: untick "Use detected ADB location" and
   point it at `%ANDROID_HOME%\platform-tools\adb.exe`.
5. Cold boot the virtual device from Device Manager.
6. Hyper-V may reserve the emulator's ports:
   `netsh interface ipv4 show excludedportrange protocol=tcp` must not cover 5554–5585.

The emulator's drag-and-drop installer reports *"Could not parse error string"* whenever adb
is not connected; it is not a problem with the APK.

---

## 6. The visual check loop

This is how UI work is verified. Do it for every change that affects what is on screen.

### 6.1 Build and install

```
gradlew testDebugUnitTest
gradlew assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.kimbar.observ.debug/com.kimbar.observ.MainActivity
```

Grant permissions once after a fresh install or data reset, so the permission dialog does not
cover the screen:

```
adb shell pm grant com.kimbar.observ.debug android.permission.CAMERA
adb shell pm grant com.kimbar.observ.debug android.permission.RECORD_AUDIO
```

### 6.2 Look

Screenshots go into `shots/` (git-ignored). Name them by screen and state, e.g.
`shots/test-screen_synced_formatB.png`.

```
adb shell screencap -p /sdcard/shot.png
adb pull /sdcard/shot.png shots/test-screen_synced_formatB.png
```

Then open the PNG with the Read tool and actually look at it.

### 6.3 Find things and act

Never guess tap coordinates from a screenshot. Dump the view tree and use the element bounds:

```
adb shell uiautomator dump /sdcard/ui.xml
adb pull /sdcard/ui.xml shots/ui.xml
```

Each node has `text`, `content-desc` and `bounds="[x1,y1][x2,y2]"`; tap the centre. Give
composables meaningful `contentDescription`s or `Modifier.testTag` + `semantics { testTagsAsResourceId = true }`
so they are findable.

```
adb shell input tap X Y
adb shell input swipe X1 Y1 X2 Y2 300        # scroll, digit wheels (vertical swipe)
adb shell input text "Run%s1"                 # %s = space; ASCII only
adb shell input keyevent KEYCODE_BACK
adb shell input keyevent KEYCODE_ENTER
adb shell wm size                             # screen size
```

Wait briefly (about 0.5–1 s) after an action before the next screenshot; animations and
recompositions take time.

### 6.4 What to check on every UI change

- Nothing overlaps or is cut off; long Test names and the longest timer text
  (`-0:01:23.4`, `999:59.9`) fit.
- Both time formats (MMM:SS and H:MM:SS switch).
- Light and dark theme: `adb shell cmd uimode night yes` / `no`.
- Large fonts: `adb shell settings put system font_scale 1.3`, then back to `1.0`.
- Inactive items look diminished; active ones are clearly readable.
- Before/after: when changing an existing screen, take a screenshot before the change too
  and compare.

### 6.5 Checking data, not just pixels

Test folders of the debug app:

```
adb shell ls /sdcard/Android/data/com.kimbar.observ.debug/files/tests/
adb pull /sdcard/Android/data/com.kimbar.observ.debug/files/tests/<folder>/events.txt shots/
adb pull /sdcard/Android/data/com.kimbar.observ.debug/files/tests/<folder>/syncs.txt shots/
```

For timing-related changes, compare `events.txt`/`syncs.txt` with what was done on screen.

### 6.6 Reporting back

Show the user the relevant screenshots (before/after where useful), say what was checked on
which device, and list what could not be checked (e.g. "camera checked on emulator webcam
only").

---

## 7. Build variants and the user's real data

| | Debug | Release |
|---|---|---|
| Application id | `com.kimbar.observ.debug` | `com.kimbar.observ` |
| Launcher name | observ debug | observ |
| Signed with | the machine's debug key | the release key (CI secrets) |
| Built by | local sessions | CI on push to `main` |

Debug and release install side by side. **Never install over, uninstall, or clear the data
of `com.kimbar.observ`** unless the user explicitly asks: it holds real Tests, and
uninstalling deletes anything not yet exported.

Reset only the debug app: `adb shell pm clear com.kimbar.observ.debug`.

---

## 8. CI, signing and releases

- GitHub Actions builds on push to `main` (and on manual *Run workflow*): unit tests, signed
  release APK, and publishes it as the **`latest`** release with `observ.apk`.
- The repository is **private** on a **free plan**: CI minutes are limited (about 4–5 min per
  build). Verify locally first. Put **`[skip ci]`** in commit messages that do not need a new
  release (docs, work in progress, intermediate commits).
- Downloading a release requires being logged into GitHub (private repo), also on the phone.
- Signing uses repository secrets `OBSERV_KEYSTORE_BASE64`, `OBSERV_KEYSTORE_PASSWORD`,
  `OBSERV_KEY_ALIAS`, `OBSERV_KEY_PASSWORD`. The keystore must stay the same forever, or
  installed apps can no longer be updated. Never commit `*.jks`, `keystore.properties` or
  passwords.
- Release notes say `Signed: true` when the key was used.

---

## 9. Technical baseline

Known to work together in the first prototype:

| Component | Version |
|---|---|
| Android Gradle Plugin | 8.13.0 |
| Gradle | 8.14.3 |
| Kotlin (+ compose, serialization plugins) | 2.2.21 |
| Compose BOM | 2025.06.01 |
| CameraX | 1.4.2 |
| kotlinx-serialization-json | 1.9.0 |
| minSdk / targetSdk / compileSdk | 29 / 35 / 35 |
| JDK for the build | 17 |

Upgrade deliberately, one thing at a time, with a full visual check afterwards.

### Architecture

- **`model/`: pure Kotlin, no Android imports.** All test-time logic (sync averaging,
  rounding, formats, the 9 h limit, export naming, file contents) lives here and is covered
  by JVM unit tests. Changes to a Test go through one place (`TestOps`) so that "changed
  since export" can never be forgotten.
- **`data/`**: capturing moments, storage (one folder per Test, JSON as the source of truth,
  `events.txt`/`syncs.txt` regenerated on every change with atomic writes), export to
  Downloads.
- **`media/`**: camera, audio, video frame access.
- **`ui/`**: Compose screens; state in the model, not in screens.

### Timing techniques (keep these when rebuilding)

- Every moment is captured as `SystemClock.elapsedRealtimeNanos()` plus boot count plus the
  wall clock. Test time is computed from the monotonic counter; wall clock only bridges
  reboots.
- **Touch events:** use the pointer event's own `uptimeMillis` (converted to
  elapsedRealtime) for MARK and PHOTO, and fire on finger-down, not on release.
- **Photos:** use the camera sensor timestamp (`ImageProxy.imageInfo.timestamp`). Its time
  base is either BOOTTIME or MONOTONIC depending on the device; pick the interpretation that
  lands just before the image arrived.
- **Audio:** `AudioRecord.getTimestamp(..., TIMEBASE_BOOTTIME)` maps a frame position to its
  capture time, so the start of a WAV is known to milliseconds; the end follows from the
  sample count.
- **Video:** CameraX does not expose the first frame's time. Start is estimated from recorder
  status events as `event time − recorded duration` (earliest estimate); it can be tens of
  ms late. Frame-exact access for video syncs uses `MediaExtractor` sample times.

### Compose notes

- Single-line text that must fit (timer, long names): `BasicText` with
  `autoSize = TextAutoSize.StepBased(...)`.
- Long-lived effects that call back (e.g. digit wheels) need `rememberUpdatedState`.
- Release the camera when its view is hidden, not only when the screen closes.

---

## 10. Definition of done

A change is done when:

1. `gradlew testDebugUnitTest` passes (and new logic in `model/` has tests).
2. It was built, installed and checked visually per section 6 (UI changes), on the phone
   where device features are involved.
3. `DESIGN.md` reflects any agreed behaviour change.
4. The user has seen the relevant screenshots and agreed.
5. It is committed (when asked) with a clear message; `[skip ci]` unless a release is wanted.
