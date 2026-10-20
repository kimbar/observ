# observ: design

This is the agreed specification of the app. It describes **what** the app does and why;
`CLAUDE.md` describes how we build and check it.

Anything marked **(to confirm)** is a proposal that has not been agreed yet. Section 12
collects these open points.

---

## 1. Purpose

observ records observations during an experiment (photos, videos, audio notes, typed notes
and marks) and gives each one a time on the **experiment clock**.

The experiment clock is an external clock, visible to the observer, that starts at 00:00 and
counts up. It does not count down, pause or reset. The exact moment it shows 00:00 is not
reliable to observe, so the app never relies on it; instead the observer reads the clock at
convenient moments ("syncs") and the app works out the rest.

**Accuracy goal:** reliable **1 second** accuracy. Times are therefore kept and shown with
**0.1 s precision**, and never finer. Human reading error dominates everything else, so the
app deliberately does not try to be cleverer than that (no drift correction).

---

## 2. Devices and platforms

### Who uses it

The app is sideloaded (no app store) onto a few devices used by the experimenters.

### Which devices

- **General Android devices**, not one particular model: phones of all common sizes,
  foldables and **tablets**.
- **Modern devices only.** Minimum Android version: **Android 10** (to confirm whether to raise
  it, e.g. to Android 12, to simplify camera and storage handling).
- The developer's own **Samsung Galaxy A56** is used during development as the reference
  phone, but it is not the target. Nothing in the app may depend on Samsung-specific
  behaviour or on its screen size.

### Varying capabilities

Devices differ in what they have. The app works with what is there and hides what is not:

| Capability | If missing |
|---|---|
| Back camera | PHOTO/VIDEO use the front camera; if there is no camera at all, the Camera button is hidden |
| Front camera | front/back switch hidden |
| Ultra-wide back camera | normal/wide switch hidden |
| Microphone | AUDIO hidden; videos are recorded without sound |
| Permission refused (camera or microphone) | the related buttons explain why they are unavailable and how to allow it; everything else keeps working |

Notes, MARKs, syncs and export need no special hardware and always work.

---

## 3. Concepts

**Test**: one experiment as recorded on one device. It has a unique, required name and holds
items and syncs.

**Item**: anything recorded in a Test. Each item carries a time:

| Item | Its time is… |
|---|---|
| MARK | the moment the finger touches the button |
| NOTE | the first keystroke of the note, or the MARK pressed just before typing |
| PHOTO | the start of the exposure (camera sensor timestamp), not the button press |
| VIDEO | start and end |
| AUDIO | start and end (start from the audio hardware's own timestamp) |

**Sync**: a pairing of one moment on the device with the **reading** of the external clock at
that moment, typed in by the user. Any MARK, any PHOTO and any **frame of any VIDEO** can
become a sync, at any time, by adding a reading. One video can supply many syncs (one per
frame). Notes and audio cannot be syncs.

**Active / inactive**: every item, every sync and every Test can be deactivated and
reactivated. Nothing is ever deleted, except a whole Test (section 10).

---

## 4. Time model

### The device counter

Every moment is captured on Android's monotonic counter (`elapsedRealtimeNanos`). It starts
at boot, never jumps and is not affected by network time corrections. All times are computed
from it.

### Local time and Unix epoch

These come from the device's own clock, at full precision. On an internet-connected device
this is usually within tens of milliseconds of true time. Syncs do not change it.

### Test time

Each active sync says: "at this moment, the external clock showed this reading". From that,
each sync gives an **offset** between the device counter and the experiment clock:

```
offset = device counter at the sync − reading − adjustment
```

The **adjustment** is:

- **+0.5 s for photo syncs.** The external clock shows whole seconds, and a photo catches it
  at a random point within the second; the middle of the second is the unbiased guess.
- **0 for MARK and video syncs.** The user picks the moment the last digit changes, which is
  close to .000.

The Test's offset is the **plain average** of all active syncs' offsets, and then:

```
test time = device counter − average offset
```

- It applies to **every** item of the Test, including items recorded before the first sync.
- Moments before 00:00 get **negative** test times.
- With no active sync the Test is **not synced**: test times show "not synced" on screen and
  are empty in the files.
- A sync counts only if the sync and its source item are both active.

The **delta** of a sync is how far its own reading is from what the average says for that
moment. It shows outliers.

### Reboots

A reboot restarts the device counter. If a Test continues after a reboot, the app bridges the
gap with the device's wall clock (accurate to tens of ms with network time), shows a warning
on the Test, and flags items recorded after the reboot in the files.

---

## 5. Time formats

| Where | Format | Example |
|---|---|---|
| Files (`events.txt`, `syncs.txt`) | always `MMM:SS.s`, minutes not wrapped into hours | `123:12.6`, `-001:23.4` |
| Screen, format A (default) | `MMM:SS.s` | `007:05.3` |
| Screen, format B | `H:MM:SS.s` | `0:07:05.3`, `-0:01:23.4` |

- Times are **rounded** to 0.1 s.
- One **global format switch** (A/B) is always visible on every screen, including while
  entering a sync reading. It changes all on-screen times, also in old Tests, and never the
  files.

### Entering a reading: digit wheels

Readings of the external clock are entered with swipeable digit wheels. Separators are fixed;
there are no tenths, because the external clock does not show them:

- format A: `[0][0][7] : [0][5]`
- format B: `[0] : [0][7] : [0][5]`

Units of seconds, minutes and hours go 0–9; tens of seconds and tens of minutes go 0–5. The
wheels start at the app's current estimate for that moment (or 000:00 if not synced), so
usually only the last digit or two need changing. The layout follows the global switch.

---

## 6. Test lifetime

- There is no start or stop button. Ending a test is just a NOTE or an AUDIO item.
- Leaving a Test does not close it; any Test can be reopened at any time.
- **Until the first sync**, anything can be added.
- **Once synced, no new items can be added after test time 9:00:00.** Everything else stays
  possible: adding syncs from existing items, correcting readings, (de)activating, export.
  999 minutes is more than 9 hours, so format A never overflows.
- If syncs change and the test time drops below 9:00:00 again, adding is possible again.

---

## 7. Screens

### 7.1 Tests (home)

- List of Tests: name, creation date, number of items, synced or not, exported or not.
- Inactive Tests are shown smaller and dimmed, at the bottom. They must be activated before
  they can be opened.
- **New test** asks for a name (required, must not match an existing Test).
- Each Test has a ⋮ menu: Open, Export (active Tests only), Deactivate / Activate,
  Delete (section 10).

### 7.2 Test (recording)

Header, from top to bottom:

1. Back button (round) and the Test name; long names shrink to fit on one line.
2. The format switch (left) and the **Syncs** button with the number of active syncs and their
   spread (right).
3. The **test clock**, large, or "not synced". It always fits its space, at any length up to
   9 characters (`0:00:00.0`, `-001:23.4`).
4. The local time, small.

Below the header, a **Camera** button opens and closes the camera view:

| | Camera closed (default) | Camera open |
|---|---|---|
| Shown | note field, **AUDIO** (start/stop), **MARK** | preview, **PHOTO**, **VIDEO** (start/stop), **MARK**, lens switches |
| Hidden | preview, PHOTO, VIDEO | note field, AUDIO |

- **MARK** is available in both modes, for sudden events.
- PHOTO and MARK fire on finger-down.
- The camera cannot be opened while audio is recording, and cannot be closed while video is
  recording, so a running recording always has its stop button on screen.
- Lens switches: front / back, and normal / wide where available (section 2). No zoom.
- The note's time is pinned at the first keystroke (or to the MARK pressed just before) and
  shown next to the field.

**Item list**, newest first:

- Each item is a framed card with its test time and type in a large, readable font, and a
  second line with the note text or the recording's length.
- Inactive items are smaller and dimmed; they must be activated before anything else can be
  done with them.
- Each card has a ⋮ menu: Open (Open / edit for notes), Add sync… (MARK, PHOTO, VIDEO),
  Deactivate; for inactive items only Activate.

### 7.3 Item

- Photo with pinch-zoom; video stepped frame by frame (plus "open in player"); audio
  playback; note text editing (corrections leave no trace).
- For MARK, PHOTO and VIDEO frames: **add a sync** with the digit wheels. The resulting delta
  against the current average is shown before saving. For photos the +0.5 s is stated on
  screen. For videos the user steps to the first frame where the last digit has changed;
  several syncs can be added from one video.
- The syncs this item already supplies are listed below.

### 7.4 Syncs

- All syncs of the Test, active and inactive. Inactive ones are grayed out and do not count.
- Each row: test time of the moment, reading, method (MARK / PHOTO / VIDEO + frame), source,
  delta to the average.
- Sort by time or by delta (largest first).
- Per sync: deactivate / activate, correct the reading (wheels), open the source item.
- Summary: number of active syncs, spread (largest |delta|), local time of 00:00.

---

## 8. Responsive layout

The UI must work well on **every screen size and orientation** the app supports, from small
phones to tablets. This is a requirement, not a polish item.

### Principles

- **No fixed pixel layouts.** Sizes come from the available space (weights, wrapping,
  auto-sizing text), never from one device's dimensions.
- **Nothing is ever cut off or overlapping**, at any size, in either orientation, at large
  system font sizes (up to 200 %), and in light and dark theme.
- **Text that must stay on one line shrinks to fit** (test clock, Test names, digit wheels);
  other text wraps.
- **Touch targets** are at least 48 dp. MARK, PHOTO and the record buttons are large enough to
  hit without looking.
- **Rotation never interrupts anything:** a running recording, a typed note, an open sync
  entry or the camera view survive rotating the device or resizing the window (split screen,
  foldables).

### Layouts by width

Uses Android's standard window size classes:

| Width class | Typical devices | Layout |
|---|---|---|
| Compact (< 600 dp) | phones in portrait | single column, as described in section 7 |
| Medium (600–840 dp) | foldables, small tablets, phones in landscape | two panes on the Test screen: header and capture controls (and camera) on one side, item list on the other |
| Expanded (> 840 dp) | tablets, large foldables | two panes on the Test screen; Item and Syncs open beside the list instead of replacing it **(to confirm)** |

Short screens (phones in landscape): the header condenses (name and switches in one row next
to the clock) so the capture controls stay visible.

### Supported orientations

Portrait and landscape on all devices **(to confirm)**. Photos and videos are always saved
upright, whatever way the device is held.

---

## 9. Storage and files

Each Test is a folder holding its media files and two text files that always reflect the
**current state**. They are rewritten on every change, atomically, so a crash never leaves a
half-written file. Corrections and state changes leave no history. Internally the app keeps
the same data in a JSON file, which is the source of truth; the text files are generated
from it.

### `events.txt`

Tab-separated, one line per item, sorted by time. Header lines start with `#` and give the
Test name, device, creation time and a sync summary.

| Column | Example / meaning |
|---|---|
| `test_time` | `012:05.3`, empty if not synced |
| `local_time` | `2026-10-17 14:03:22.123456` |
| `unix_epoch` | `1792238602.123456` |
| `type` | `PHOTO` |
| `active` | `1` / `0` |
| `end_test_time` | for VIDEO / AUDIO |
| `duration_s` | for VIDEO / AUDIO, e.g. `83.412` |
| `file` | `photo_20261017_140322_123.jpg` |
| `text` | note text, newlines escaped |
| `phone_counter_ns` | raw device counter, for auditing |
| `after_reboot` | `1` if recorded after a reboot during the Test |

### `syncs.txt`

Tab-separated, one line per sync: `test_time` of the moment, `reading`, `adjustment_s`,
`delta_s`, `method`, `source_file`, `frame` (video), `active`, `local_time`, `unix_epoch`,
`phone_counter_ns`.

---

## 10. Export and deleting

### Export

- A zip of the whole Test folder (all media, inactive items included, both text files and the
  JSON) is saved to `Downloads/observ/`, then the share sheet opens.
- The first export is named `<test name>.zip`; later exports of the same Test are
  `<test name>_v.2.zip`, `<test name>_v.3.zip`, …
- Only active Tests can be exported.
- The Test remembers how often it was exported and whether it has **changed since the last
  export**. Any change to items, syncs, readings, note text or active state counts; the format
  switch does not.

### Deleting a Test

The only permanent deletion. Allowed when the Test is **inactive**, or has been **exported and
not changed since**. Always asks for confirmation.

---

## 11. Not in scope

NTP or any network time sync; drift correction or curve fitting; syncs from notes or audio;
tenths of a second in readings; change history; several devices sharing one Test; camera zoom;
translations (English only).

---

## 12. Open points

1. Minimum Android version: stay at Android 10, or raise it (e.g. Android 12)?
2. Landscape on phones: supported everywhere, or portrait-only on compact phones?
3. Expanded layouts (tablets): Item and Syncs beside the list, or full screen as on phones?
4. Is there any device class to exclude (e.g. very small phones, Android Go devices,
   Chromebooks)?
