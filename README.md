# observ

Android app for recording timestamped observations during an experiment (photos, videos,
audio notes, typed notes and marks) on the **experiment clock**: an external clock that
starts at 00:00. The phone is synced to it by reading the clock off a MARK, photo or video
frame; several syncs are averaged. Times are kept at 0.1 s precision for reliable 1 s accuracy.

The full specification is in [DESIGN.md](DESIGN.md).

## Install (sideload)

1. On the phone, open the repository's **Releases → latest** page and download `observ.apk`
   (or grab the APK artifact from a GitHub Actions run).
2. Open it. Android asks to allow installs from that app (browser / Files) once; allow it.
3. Updates: install the newer APK over the old one. This only works if every APK is signed
   with the same release key (see *Signing*). Uninstalling deletes Tests not yet exported.

Requires Android 10 or newer.

## Output

Each Test exports as a zip (`Downloads/observ/<test name>.zip`, later `<test name>_v.2.zip`, …)
holding all media plus:

- `events.txt`: one tab-separated line per item, with test time (`MMM:SS.s`), local time,
  Unix epoch, type, active flag, end time and duration for recordings, file, note text.
- `syncs.txt`: one line per sync, with its reading, adjustment, delta to the average, method
  and source.
- `test.json`: the app's own data.

## Building

GitHub Actions builds every push (`.github/workflows/build.yml`), runs the unit tests and, on
the default branch, publishes the APK as the `latest` release.

Locally: Android SDK + JDK 17, then `./gradlew testDebugUnitTest assembleDebug`.

The time model (`app/src/main/java/com/kimbar/observ/model`) is plain Kotlin with no Android
dependencies, covered by the unit tests in `app/src/test`.

### Signing

Release builds are signed when these repository secrets are set
(Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `OBSERV_KEYSTORE_BASE64` | the `.jks` keystore, base64-encoded |
| `OBSERV_KEYSTORE_PASSWORD` | keystore password |
| `OBSERV_KEY_ALIAS` | key alias |
| `OBSERV_KEY_PASSWORD` | key password |

Without them CI builds a debug APK, which is signed with a throwaway key that changes between
builds, so each debug build has to be installed fresh. For local release builds put the same
values in an untracked `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`). Keep a backup of the keystore: without it, existing installs cannot be updated.
