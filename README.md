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
