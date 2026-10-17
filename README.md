# observ

Android app for gathering timestamped field observations with sub-second precision:
photos, videos, audio notes and typed notes, all on one clock that can be synced to an
external reference.

## Install (sideload)

1. On the phone, open the repository's **Releases → latest** page and download `observ.apk`
   (or grab the APK artifact from a GitHub Actions run).
2. Open it. Android asks to allow installs from that app (browser / Files) once; allow it.
3. Updates: install the newer APK over the old one. This only works if every APK is signed
   with the same release key (see *Signing*). Uninstalling deletes sessions not yet exported.

Requires Android 10 or newer.

## Using it

- **Start** a session. Every event is appended immediately to the session's `events.txt`.
- **PHOTO** and **MARK** fire when your finger touches the button, not when you lift it.
- **VIDEO** and **AUDIO** toggle recording; start and end times are logged when recording stops.
- **Note**: the note's time is pinned when you type the first character (or when you press
  MARK), not when you press send.
- **Export** writes `Downloads/observ/<session>.zip` and opens the share sheet.

## Timing

All events are captured on the phone's monotonic clock (`elapsedRealtimeNanos`) and converted
to wall time with a *time base* set by the latest sync. `events.txt` keeps the raw value too,
so times can be recomputed afterwards with a different sync.

| Event | What the time means | Typical accuracy* |
|---|---|---|
| PHOTO | start of exposure (camera sensor timestamp) | ~1 ms |
| AUDIO_START / END | capture time of first / last sample (`AudioRecord` timestamp) | ~1–5 ms |
| VIDEO_START / END | estimated from recorder progress | may be late by a few tens of ms |
| MARK, PHOTO press | finger touch-down event time | touchscreen latency, ~10–30 ms |
| NOTE | first keystroke, or the preceding MARK | as above |

\* relative to the phone clock; add the sync error.

### Sync methods

- **Photo**: photograph the reference clock (ideally one showing milliseconds), zoom in, type
  the time shown. Most precise.
- **Video**: film the reference clock, step through frames, type the time on a chosen frame.
- **Manual**: tap the pad exactly as the reference clock rolls over to a new second, then type
  that time. Anticipate the change; a reaction tap adds ~200 ms.
- **NTP**: query a time server. Accuracy is about half the network round trip.

The phone's crystal drifts (commonly 1–50 ppm, i.e. up to ~0.2 s per hour), so re-sync during
long sessions. After two syncs at least a minute apart the app shows the measured drift. Syncs
are lost on reboot.

### `events.txt` format

Tab-separated, `#` lines are comments:

```
local_time  epoch_seconds  elapsed_ns  event  file  text
2026-10-17 14:03:22.123456  1792238602.123456  81234567890123  PHOTO  photo_20261017_140322_123.jpg  press_to_exposure=+142 ms
```

Lines are written in the order events become known (video and audio lines are written when the
recording stops), so sort by `epoch_seconds` for a timeline. `SYNC` and `TIMEBASE` lines carry
`base_ns`: `epoch_ns = elapsed_ns + base_ns`.

## Building

GitHub Actions builds every push (`.github/workflows/build.yml`), runs the unit tests and, on
the default branch, publishes the APK as the `latest` release.

Locally: Android SDK + JDK 17, then `./gradlew assembleDebug`.

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
