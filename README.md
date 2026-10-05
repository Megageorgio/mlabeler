# mLabeler

Editor for singing voice labels: phoneme and word tiers, notes and pitch, UTAU oto. Windows, macOS, Linux,
Android and iOS from one codebase (Kotlin, Compose Multiplatform).

[Русский](README.ru.md)

**Status:** early. The phoneme editor works; oto, notes and pitch, plugins and autolabeling are next.
The plan is in [docs/DESIGN.md](docs/DESIGN.md).

## What works now

- Open a folder: audio files are listed with their labels (`.lab`, `.TextGrid`, Audacity `.txt`), found next to
  the audio or in a `lab/` folder beside `wav/`.
- Waveform and spectrogram, zoom and scroll (wheel, Ctrl+wheel, pinch on touch screens).
- Interval tiers: move boundaries by mouse, finger or keys, split, merge, rename, add/rename/reorder tiers.
- Ripple and linked moves as visible toggles; Shift/Alt switch them while dragging.
- Undo/redo per file, saving straight to the label file with a backup of the original in `.mlabeler/backup`.
- Done/star/tag marks, file filters, checks for short and empty intervals and unknown phonemes.
- Playback of an interval, a selection, the screen; loop.
- Command list (Ctrl+K), keyboard shortcuts for everything.
- Layout adapts to the window: side panels on wide screens, sheets on phones. Panels can be resized and hidden.
- Themes: dark, light, retro (square), high contrast. Interface size. English and Russian.
- oto mode (Ctrl+M or the switch in the toolbar; folders with oto.ini open in it): entry list with search
  (`alias:`, `sample:`), offset/overlap/preutterance/consonant/cutoff on the waveform, preutterance drags
  the whole set (Shift switches), Q W E R T put a marker at the cursor, numeric fields, new/duplicate/delete
  entries, done/star per entry, Shift_JIS and other encodings kept on save.
- Entries tab: every label of the file or of the whole folder, search (`name:`, `file:`, `tier:`), counts per label.
- Pitch (over the spectrogram or in its own lane) and loudness lanes.
- Compare: labels of the same files from other folders shown under yours, boundaries coloured by distance,
  statistics, take them over in one step.
- Autolabel with mVocalToolkit (Ctrl+Shift+A): a selected part or the whole recording, by lyrics/phonemes
  (SOFA, HubertFA) or without lyrics (WFL); put the result into the labels or show it next to them to compare models.
  On a computer mLabeler installs the toolkit (with uv) and starts it when needed, then stops it on exit;
  a toolkit that's already running is used as is. Phones and tablets connect to a computer: turn on
  "Let phones connect" there (Settings → Autolabel) and enter the address and token it shows.
- Slow playback with pitch kept (Y: 1×, 0.75×, 0.5×, 0.25×), autosave, reload of labels changed elsewhere.
- Keys can be rebound in Settings → Shortcuts. F1 shows how to work.
- Folder settings (click the folder name): what is labelled in the folder, format for new labels, extra label folders.
- Overlaid view (V): waveform outline over a darkened spectrogram, solid boundaries with a dark edge and a solid
  label band, so labels stay readable; or separate lanes; labels above or below.
- Menu bar (File, Edit, View, Go, Tools, Help) with the shortcut next to every item; View has check boxes for
  panels, lanes and toolbar groups. Toolbar groups can be shown, hidden and reordered, with names under the
  buttons or as compact icons. On the first start: "Simple" or "Everything". Phones keep the button menus.
- Every slider in the settings has a number field next to it for an exact value.
- Automatic oto (Ctrl+Shift+A in oto folders): entries from file names (kana, romaji, Cyrillic) and the recordings,
  CV / VCV / CVVC, optional tempo, or syllables placed by an aligner model from mVocalToolkit.
- Recording samples from a list (reclist.txt): big current line with romaji, level meter, take preview, click track
  with count-in, guide WAV, previous takes kept in `.mlabeler/takes`.
- Plugins (Ctrl+Shift+P): JavaScript run by QuickJS on every platform, a parameter form made from `plugin.json`,
  four quick slots (Ctrl+1…4). Built in: replace labels by a table, shift, merge short intervals, name pauses,
  prefix/suffix; for oto: set a value by expression, sort, duplicates, alias prefix/suffix, remove by pattern.
  "New plugin…" makes a template in the app's plugin folder.
- Rename by pattern (Ctrl+H) for oto aliases or the active tier; sound while dragging a boundary.

## Keys

| | |
|---|---|
| Space / Shift+Space | play selection or interval / play from cursor |
| ← → | previous / next boundary |
| Tab, Ctrl+← → | previous / next interval |
| ↑ ↓ | tier above / below |
| , . (Shift ×10) | nudge the selected boundary |
| Q / W | left / right boundary of the interval to the cursor |
| S, M, Del | split at cursor, merge with next, remove boundary |
| Enter, F2 | rename |
| R, G, L | ripple, linked, loop |
| PgUp / PgDn | previous / next file |
| D, B | done, star |
| Ctrl+K | all commands |

## Build

JDK 17+.

```
./gradlew :app:run                     # desktop
./gradlew :app:packageMsi              # or packageDmg, packageDeb
./gradlew :app:assembleDebug           # Android APK (needs the Android SDK)
./gradlew :core:jvmTest                # tests
```

iOS: `cd iosApp && xcodegen`, open `mLabeler.xcodeproj` in Xcode and run. The app works in its Documents folder,
which is visible in the Files app.

Android asks for access to all files: label files are written next to the recordings in the folders you open.

## License

MIT
