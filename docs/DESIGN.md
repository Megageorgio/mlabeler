# mLabeler — design

Editor for singing-voice labels: phoneme and word tiers, notes and pitch, UTAU oto. One app for Windows, macOS,
Linux, Android and iOS (Kotlin + Compose Multiplatform).

This file is the working plan. It changes as the app grows.

## Principles

1. **Quiet by default.** The first screen is a waveform and the labels. Everything else is one click away, not on
   screen. No hardware readouts, no status noise.
2. **Keyboard first on desktop, thumb first on phones.** Every edit can be done without dragging. Every edit can
   be done with one finger.
3. **What you see is what is written.** Labels are saved straight to the label files. No separate export step to
   forget. The first overwrite of a file in a session keeps a backup.
4. **Behaviour is visible.** Linked moves, ripple moves and snapping are explicit toggles in the toolbar, with the
   modifier shown in the tooltip. Nothing moves that the user did not expect.
5. **Same features everywhere.** Phone and desktop share one model and one set of actions; only the layout and the
   input differ. A phone is never a reduced mode.
6. **Big folders stay fast.** Audio analysis is cached and done in the background, chunk by chunk.

## Layout

No docking. A fixed arrangement of panels whose sizes can be changed and which can be hidden.

```
┌ top bar: file ▾ · mode tabs · undo/redo · play · search · menu ────────────┐
├ files ┃ timeline (lanes, stacked)                              ┃ inspector ┤
│       ┃  overview strip                                        ┃           │
│       ┃  waveform                                              ┃           │
│       ┃  spectrogram (+ pitch, energy overlays)                ┃           │
│       ┃  tiers: words · phonemes · notes …                     ┃           │
├───────┸────────────────────────────────────────────────────────┸───────────┤
└ status: position · selection · zoom · problems                            ┘
```

Width classes decide the arrangement, not the platform:

| Width | Files | Inspector | Toolbar |
|---|---|---|---|
| ≥ 1100 dp | side panel | side panel | top |
| 700–1100 dp | side panel, collapsible | overlay sheet from the right | top |
| < 700 dp (phones) | full-screen list, back to editor | bottom sheet | bottom bar with large targets |

Lane heights and side panel widths are draggable and remembered per layout preset. Presets: *Phonemes*,
*Notes & pitch*, *oto*, *Review*, plus user presets.

Phones: one finger edits, two fingers pan and zoom, long press opens the item menu. Modifier keys are sticky
toggles in the bottom bar (Shift = ripple, Alt = ignore links, etc.).

## Modes

Modes are views of the same document, not separate apps.

- **Phonemes** — interval tiers (words, phonemes, any custom tier), boundary editing, text editing.
- **Notes & pitch** — piano roll over the spectrogram: notes (pitch, duration, slur), f0 curve, voiced/unvoiced.
- **oto** — UTAU entries: offset, overlap, preutterance, consonant, cutoff, one entry or all entries of a sample.
- **Review** — a queue of things to check: low-confidence boundaries from the aligner, problems from the checks,
  unfinished files. Next/previous jumps through the queue.

## Data model

```
Workspace (a folder)
 └ Item (one audio file + its labels)
    ├ LabelDoc
    │   ├ IntervalTier (name, intervals: start, end, text, confidence?)
    │   ├ PointTier    (name, points: time, text)
    │   └ NoteTier     (name, notes: start, end, pitch (midi, float), slur, text)
    ├ Curves: f0, energy, voicing (computed or loaded)
    └ Marks: done, star, tag, note
 └ OtoSet (oto.ini of a folder: entries sample, alias, offset, consonant, cutoff, preutterance, overlap)
```

Times are seconds (Double) inside the app, converted on read/write. Tiers can be linked (word boundaries are
phoneme boundaries); a linked boundary moves in all tiers that share it.

Workspace state lives in `<folder>/.mlabeler/workspace.json`: marks, last position, chosen formats, layout.
Backups in `<folder>/.mlabeler/backup/`.

## Formats

| Format | Read | Write | Notes |
|---|---|---|---|
| HTK / Sinsy / NNSVS `.lab` | ✓ | ✓ | 100 ns units; words tier from `.lab` pairs where present |
| Praat TextGrid (long and short) | ✓ | ✓ | all tiers |
| Audacity labels `.txt` | ✓ | ✓ | seconds, tab separated |
| DiffSinger `transcriptions.csv` | ✓ | ✓ | ph_seq, ph_dur, ph_num, note_seq, note_dur, note_slur |
| DiffSinger `.ds` | ✓ | ✓ | per sentence |
| UTAU `oto.ini` | ✓ | ✓ | encoding detection, negative/positive cutoff, negative overlap |
| vLabeler project `.lbp` | ✓ | — | import entries |
| MIDI | ✓ | ✓ | notes tier |
| UTAU sequence `.ust` | ✓ | — | notes and words; oto entries a song uses |

Audio: WAV in every PCM variant (8-bit unsigned, 16/24/32-bit, float, extensible, RF64) in common code; mp3,
flac, ogg, m4a through the platform decoder.

## Editing

- Select a boundary or an interval by click/tap or by keys (←/→ previous/next boundary, ↑/↓ tier).
- Move by drag or by keys: `,` / `.` nudge by one step (step = one pixel or a set time), with Shift ×10.
- Set to cursor: the selected boundary jumps to the playhead or the mouse position (`Q`/`W` = left/right of the
  interval under the cursor).
- Split at cursor (`S`), merge with next (`M`), delete boundary (`Delete`), rename (`Enter` or type).
- Move modes: **single** (default), **ripple** (this and everything after), **linked** (same boundary in all
  linked tiers). Shown in the toolbar.
- Snapping to boundaries of other tiers and to zero crossings, optional.
- Multi-select intervals; bulk rename, delete, mark.
- Copy and paste values between oto entries (all fields or chosen ones).
- Undo/redo per file, unlimited within memory limits, survives file switches.

## Playback

Play interval, selection, screen, file; loop; play from cursor; preview while dragging; speed 0.25–1×
with pitch kept; preview volume normalisation; click on the overview to jump.

## Views

- Waveform with optional normalisation.
- Spectrogram: one dB scale across the whole file, contrast and brightness sliders, colour maps, mel or linear.
- f0 curve and energy over the spectrogram, semitone grid.
- Overview strip with the whole file and the visible range.

## Checks

Built-in, configurable, shown in the problems list and the review queue:

- phoneme not in the phoneme set of the language,
- interval shorter than a limit (default 50 ms), empty text, overlap or gap,
- no pause at the start/end,
- `ph_num` does not match the word tier,
- oto: cutoff past the end of the file, preutterance before overlap, duplicate aliases, missing samples.

## Batch

Actions over the selection, the file or the whole workspace: rename by rule (regex or table), replace phonemes,
shift times, set marks, convert formats, regenerate f0. Rules can be saved and bound to keys (`F1`–`F8`).

## Plugins

JavaScript plugins:
QuickJS on Android and iOS, the same engine on desktop for identical behaviour. A plugin is a folder with
`plugin.json` and scripts; parameters are typed (number, text, choice, file, entry filter) and the dialog is
generated from them. Plugins can read and change the current document and the workspace.

## Toolkit

mVocalToolkit is optional. When it is configured (local or remote URL), these actions appear:

- *Label* — align the selected files (language → model), with recognised lyrics when no text exists.
- *Recognise phonemes* — no lyrics.
- *Notes* and *Pitch* — fill the notes tier and the f0 curve.
- *Review* uses the aligner's per-boundary confidence.

Progress is a small indicator in the status bar. Model management stays in the toolkit's own tools.

## Appearance

Themes are tokens (colours, corner radius, borders, density, fonts) in JSON:
*Modern* (light/dark), *Retro* (square corners, 1 px borders, no shadows), *Contrast*. Users can copy and edit a
theme file. Colours for tiers, markers and the spectrogram map are part of the theme.

## Text

Short, plain, verbs on buttons, no exclamation marks, no "Oops", no emoji. A glossary keeps terms consistent
(boundary, interval, tier, item, workspace). English is the source; Russian from the start; more languages as
files.

## Platform notes

| | Desktop | Android | iOS |
|---|---|---|---|
| Files | java.io | java.io with all-files access | app Documents (visible in Files) + picked folders |
| Audio out | javax.sound | AudioTrack | AVAudioEngine |
| Decode non-WAV | ffmpeg if present | MediaCodec | AVAudioFile |
| Scripts | QuickJS (JNI) or GraalJS | QuickJS | QuickJS |

## Milestones

1. **Core slice** — open folder, WAV, waveform + spectrogram, interval tiers, lab/TextGrid/Audacity/csv, editing
   by mouse, touch and keys, undo, playback, marks, adaptive layout, themes, en/ru.
2. **oto mode** — oto.ini, entry list, linked parameters, oto checks, batch value edits.
3. **Notes & pitch** — f0, notes tier, piano roll, MIDI, .ds.
4. **Toolkit and review** — label/notes/pitch actions, confidence queue, checks panel.
5. **Plugins** — script runtime, parameter dialogs, quick slots, built-in batch plugins.
6. **Polish** — keymap editor, custom themes, layout presets, more languages, vLabeler import.
