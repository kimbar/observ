# observ: design

Status: agreed. This describes the app as agreed in discussion and replaces the first
prototype's behaviour where they differ.

## 1. Purpose

Record observations during an experiment (photos, videos, audio notes, typed notes and
marks) and give each one a time on the **experiment clock**: an external clock visible to the
observer that starts at 00:00 and counts up. The goal is reliable **1 s accuracy**, so times
are kept and shown at **0.1 s precision**, never finer.

## 2. Concepts

**Test**: one experiment as recorded on one phone. It has a unique, required name. It holds
items and syncs.

**Item**: anything recorded in a Test:

| Item | Time it carries |
|---|---|
| MARK | the moment the finger touches the button |
| NOTE | the first keystroke, or the MARK pressed just before typing |
| PHOTO | start of exposure (camera sensor timestamp) |
| VIDEO | start and end (end = start + duration) |
| AUDIO | start and end (start from the audio hardware timestamp) |

**Sync**: a pairing of one moment on the phone with the **reading** of the external clock at
that moment, typed by the user. Any MARK, any PHOTO, and any **frame of any VIDEO** can become
a sync at any time by adding a reading. One video can supply many syncs (one per frame).
Notes and audio cannot be syncs.

**Active / inactive**: every item, every sync and every Test can be deactivated and
reactivated. Nothing is ever deleted, except a whole Test (section 9).

## 3. Time model

### Phone counter
Every moment is captured on Android's monotonic counter (`elapsedRealtimeNanos`): it starts at
boot, never jumps, and is unaffected by network time corrections. Local time, Unix epoch and
test time are all computed from it.

### Local time and Unix epoch
These come from the phone's own clock, at full precision. On an internet-connected phone this
is usually within tens of ms of true time. Syncs do not change it.

### Test time
For each active sync *i*:

```
offset_i = phone_counter_i − reading_i − adjustment
```

where `adjustment` is **+0.5 s for photo syncs** (a photo catches a whole-second display at a
random point within the second; the middle is the unbiased estimate) and **0 for MARK and
video syncs** (the user picks the moment the last digit changes, which is close to .000).

The Test's offset is the **plain average** of the active syncs' offsets. Then for any moment:

```
test_time = phone_counter − average_offset
```

- It applies to **every** item in the Test, including those recorded before the sync was made.
- Moments before 00:00 get negative test times.
- With no active sync the Test is **not synced**: test times show as "not synced" on screen
  and are empty in the file.
- Clock drift is ignored on purpose: human reading error dominates.

**Delta** of a sync = its own `reading + adjustment` minus the test time the average gives for
that moment. It shows how far each sync is from the consensus.

### Reboots
A reboot resets the phone counter. If a Test continues after a reboot, the app bridges the gap
using the phone's wall clock (accurate to tens of ms with network time) and shows a warning on
the Test. Items recorded after a reboot are flagged in the file.

## 4. Time formats

| Where | Format | Example |
|---|---|---|
| `events.txt` / `syncs.txt` | always `MMM:SS.s`, minutes not wrapped into hours | `123:12.6`, `-001:23.4` |
| Screen, format A | `MMM:SS.s` | `007:05.3` |
| Screen, format B | `H:MM:SS.s` | `0:07:05.3`, `-0:01:23.4` |

- Times are **rounded** to 0.1 s.
- One **global format switch** (A/B), always visible on every screen including sync entry,
  changes all on-screen presentation, also in old Tests. It never changes the files.
- Format A is the default (it is the preferred external clock format).

### Reading entry: digit wheels
External clock readings are entered with swipeable digit wheels, separators fixed, no tenths:

- format A: `[0][0][7]:[0][5]`
- format B: `[0]:[0][7]:[0][5]`

Units of seconds, minutes and hours go 0–9; tens of seconds and tens of minutes go 0–5. The
wheels start at the app's current estimate for that moment (or 000:00 if not synced), so
usually only the last digit or two need adjusting. The layout follows the global switch.

## 5. Test lifetime

- There is no "start" or "stop" button. Ending a test is just a NOTE or AUDIO item.
- Leaving a Test does not close it; any Test can be reopened at any time.
- **Until the first sync**, anything can be added.
- **Once synced, no new items can be added after test time 9:00:00.** Everything else stays
  possible: adding syncs from existing items, correcting readings, activating/deactivating,
  export. (999 minutes > 9 h, so format A never overflows.)
- If syncs change and the test time drops below 9:00:00 again, adding becomes possible again.

## 6. Screens

### 6.1 Tests (home)
- List of Tests: name, creation date, number of items, synced or not, exported or not.
- Inactive Tests are shown smaller and dimmed at the bottom.
- "New Test" asks for a name (required, must not match any existing Test).
- Per Test: open, export (active Tests only), deactivate / activate, delete (section 9).

### 6.2 Test
- Top: big **test clock** (or "not synced"), local time small beneath it, the format switch,
  and a **Syncs** button showing the count of active syncs and their spread.
- A **Camera** button opens and closes the camera view. The two modes:

  | | Camera closed (default) | Camera open |
  |---|---|---|
  | Shown | note field, **AUDIO** (start/stop), **MARK** | preview, **PHOTO**, **VIDEO** (start/stop), **MARK**, lens switches |
  | Hidden | preview, PHOTO, VIDEO | note field, AUDIO |

  MARK is available in both modes, for sudden events.
- The camera cannot be opened while audio is recording, and cannot be closed while video is
  recording, so a running recording always has its stop button on screen.
- Lens switches: **front / back**, and **normal / wide** (ultra-wide) where the phone exposes
  more than one back camera; the wide switch is hidden otherwise. No zoom control.
- PHOTO and MARK fire on finger-down.
- Note field: the note's time is pinned at the first keystroke (or to the preceding MARK)
  and shown next to the field.
- Item list, newest first, each with its test time. Inactive items are smaller and dimmed;
  they must be reactivated before anything else can be done with them.
- Tapping an active item opens it: view the photo, play the video or audio, edit the note
  text (no trace is kept), deactivate, or **add a sync** (MARK, PHOTO, VIDEO).

### 6.3 Add sync
- **From a MARK**: wheels only. Intended use: watch the external clock, tap MARK exactly as
  the last digit changes, then enter the reading (immediately or later).
- **From a PHOTO**: the photo with pinch-zoom, plus the wheels. The +0.5 s adjustment is
  applied automatically and stated on screen.
- **From a VIDEO**: frame scrubber (slider plus step one frame back/forward) with zoom, plus the
  wheels. The user steps to the first frame where the last digit has changed, enters the
  reading and saves; then can move to another frame and add another sync from the same video.
- Shows the resulting delta against the current average before saving.

### 6.4 Syncs
- All syncs of the Test, active and inactive. Inactive ones are grayed out and excluded from
  the average.
- Each row: test time of the moment, reading, method (MARK / PHOTO / VIDEO + frame), source
  item, delta to the average.
- Sort switch: by time of the sync's moment, or by delta (largest first).
- Per sync: deactivate / activate, correct the reading (wheels), open the source item.
- Summary: number of active syncs, average, spread (largest |delta|).

## 7. Storage and files

Each Test is a folder with its media files and two text files that always reflect the
**current state** (they are rewritten on every change, atomically, so a crash never leaves a
half-written file). Corrections and state changes leave no history.

### `events.txt`
Tab-separated, one line per item, `#` header lines with Test name, device, creation time and
a sync summary.

| Column | Example |
|---|---|
| `test_time` | `012:05.3` (empty if not synced) |
| `local_time` | `2026-10-17 14:03:22.123456` |
| `unix_epoch` | `1792238602.123456` |
| `type` | `PHOTO` |
| `active` | `1` / `0` |
| `end_test_time` | for VIDEO/AUDIO |
| `duration_s` | for VIDEO/AUDIO, `83.412` |
| `file` | `photo_20261017_140322_123.jpg` |
| `text` | note text, escaped newlines |
| `phone_counter_ns` | raw counter, for auditing |

Sorted by time.

### `syncs.txt`
Tab-separated, one line per sync: `test_time` of the moment, `reading`, `adjustment_s`,
`delta_s`, `method`, `source_file`, `frame` (video), `active`, `local_time`, `unix_epoch`,
`phone_counter_ns`.

Internally the app keeps the same data in a JSON file, which is the source of truth; the two
text files are generated from it.

## 8. Export

Zip of the whole Test folder (all media, inactive items included, both text files) saved to
`Downloads/observ/`, then the share sheet opens. Only active Tests can be exported.

- The first export is named `<test name>.zip`; later exports of the same Test get a version
  field: `<test name>_v.2.zip`, `<test name>_v.3.zip`, … (matching `v\.\d+`).
- The Test records its export count and whether it has **changed since the last export**. Any
  change to items, syncs, readings, note text or active state counts; the global format
  switch does not.

## 9. Deleting a Test

The only permanent deletion. Allowed when the Test is **inactive**, or has been **exported and
not changed since** the last export. Always asks for confirmation, since this is the one
irreversible action.

## 10. Not in scope

NTP sync; drift correction or linear fitting; syncs from notes or audio; tenths of a second in
readings; change history; multiple phones sharing a Test; camera zoom; landscape layout; translations.
