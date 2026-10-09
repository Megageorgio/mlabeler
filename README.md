# mLabeler

A labeling editor for singing voice data: phoneme and word tiers, notes and pitch, UTAU oto.
One codebase for Windows, macOS, Linux, Android and iOS (Kotlin, Compose Multiplatform).

English · [中文](README.zh.md) · [Русский](README.ru.md)

![mLabeler: waveform, spectrogram and phoneme labels](docs/screenshot.png)

## Download

Ready builds for every platform are on the [Releases](https://github.com/Megageorgio/mlabeler/releases) page:

- **Windows** — a `.zip` that needs no installation (unpack and run `mLabeler.exe`), the `.msi` installer, or the fully
  portable `-portable.zip`: the settings, the toolkit, its Python and models all stay in the program folder and nothing
  is written anywhere else (a USB stick or a separate disk will do; the toolkit and models take several gigabytes);
- **macOS** — `.dmg`;
- **Linux** — `.deb` for Debian and Ubuntu, or a `.tar.gz` for any distribution (unpack and run `bin/mLabeler`);
- **Android** — `.apk`;
- **iOS** — unsigned `.ipa` (see below).

### System requirements

| System | Minimum |
|---|---|
| Windows | Windows 10 or 11, 64-bit; the `.zip` builds also run on 7 SP1 and 8.1 through `mLabeler (Windows 7, 8.1).cmd` (experimental, without the toolkit) |
| macOS | macOS 12 or newer, a Mac with Apple Silicon (M1 or newer) |
| Linux | a current 64-bit (x86-64) distribution |
| Android | Android 8.0 or newer |
| iOS / iPadOS | iOS 15 or newer: iPhone 6s, iPhone SE (1st generation) and newer; iPad (5th generation), iPad Air 2, iPad mini 4 and newer |

Automatic labelling (the toolkit) needs a computer with Windows 10+, macOS or Linux. Phones and tablets can use the
toolkit of a computer in the same network.

There are three release channels: **stable**, **beta** (new features a little earlier) and **alpha** (the latest
development builds). The program can check for a newer version at start and always asks before downloading;
the channel is chosen on the first start or in Settings → About. On Android the new `.apk` is downloaded and
installed by the system installer over the old version, keeping the settings.

For **iOS** there is an unsigned `.ipa`. Install it with [AltStore](https://altstore.io), [SideStore](https://sidestore.io), Sideloadly or TrollStore: they sign it with your own Apple ID. With a free Apple ID the signature lasts 7 days and has to be renewed (AltStore and SideStore do it by themselves). The program does not install iOS updates by itself; it shows that a new version is out and opens the release page. On iOS the program works in its own Documents folder, which is visible in the Files app.

Android asks for access to all files, because label files are written next to the recordings in the opened folders.

## Features

**Labels**
- Opens a folder with recordings and finds the labels next to them: `.lab`, `.TextGrid`, Audacity labels,
  DiffSinger `.ds` and `transcriptions.csv`, UTAU `oto.ini`. vLabeler projects (`.lbp`) can be imported.
  Label files of a folder can be converted to another of these formats at once.
- Phoneme and word tiers: moving boundaries by mouse, finger or keys, cutting, joining, renaming,
  ripple and linked moves, bulk renaming across the whole folder.
- Waveform, spectrogram, pitch, loudness and optional formants, separately or overlaid in one picture.
- Checks for typical mistakes (short or empty parts, unknown phonemes, problems that break DiffSinger training),
  done / star marks, filters and a folder tree.
- Comparing with labels from another folder, with statistics of the differences.

**Notes and pitch**
- A piano roll with notes from phoneme groups, pitch drawing, key snapping, MIDI.
- Listening with the drawn pitch (WORLD or the DiffSinger vocoder) and saving the recording sung with it;
  voiced and unvoiced parts marked by hand; the loudness drawn and written into the sound.
- Notes and lyrics from a UTAU sequence (`.ust`).
- Export of a DiffSinger dataset (`wavs/` + `transcriptions.csv`), with long recordings cut at pauses.

**Sound**
- A sound editing mode in which the labels are locked: cutting, silence, normalising, fades, trimming silence,
  level change, click repair and noise reduction. Every change can be undone, and the original file is kept.

**oto and recording**
- An oto editor with automatic oto for CV / VCV / CVVC banks: the next entry or a done mark after an edit,
  copies of entries under new names by a pattern, the differences from another oto.ini entry by entry, the entries
  a `.ust` uses, file names from a Mac put in the composed form (NFC).
- NiaoNiao voicebanks: the marks of each sound (.inf) edited as boundaries, placed automatically, packed into
  `voice.d` and `inf.d`; a built bank can be unpacked back. A long take is cut into sounds named from a syllable
  list, each sound can be sung again on one pitch, the bank is checked for missing syllables and pitches out of
  range, and marks go to and from a CV oto.ini.
- Recording from a reclist with a level meter, click track and guide.

**Automatic labelling**
- Through [mVocalToolkit](https://github.com/Megageorgio/mVocalToolkit), a separate helper program: aligning
  by lyrics or phonemes, labelling without lyrics, lyrics from the recording, segmentation, refining the
  boundaries of ready labels with a refiner model (off by default). The Whisper model can be chosen; the texts of
  many files can be checked before aligning; words missing in a model's dictionary get a G2P guess and can be kept
  as the model's own words; the phonemes of the words tier can be made by the dictionary and G2P.
- The toolkit can work on the graphics card or the processor, as chosen in the settings.
- On a computer mLabeler installs and starts the toolkit when needed. One running toolkit is shared by every
  program that uses it and stops when none of them needs it any more. Phones and tablets can use the toolkit
  of a computer in the same network.

**Interface**
- Work environments (ready sets of panels, lanes and buttons), themes with full colour editing,
  rebindable keys and mouse buttons, a command list (Ctrl+K), searchable settings and help (F1).
- Plugins in JavaScript that run on every platform: labels, notes, marks, oto entries, the pitch and the text
  files of the folder.
- An optional sound when long work such as autolabelling ends.
- Languages: English, Russian, Japanese, Chinese (Simplified), Korean, French, German, Spanish, Portuguese.

## Building from source

Ready builds for every platform, iOS included, are made automatically and published on the
[Releases](https://github.com/Megageorgio/mlabeler/releases) page, so building by hand is not needed.
For development: JDK 17 or newer.

```
./gradlew :app:run                     # desktop
./gradlew :app:packageMsi              # or packageDmg, packageDeb
./gradlew :app:assembleDebug           # Android APK (needs the Android SDK)
./gradlew :core:jvmTest :app:desktopTest
```

## Acknowledgements

Special thanks to **HHS_kt** ([YouTube](https://www.youtube.com/@HHS_kt), [Telegram](https://t.me/hhs_kt_666)),
**Gitreti** and **XHR0ME** ([X](https://x.com/ExChroma), [Telegram](https://t.me/xhr0m1),
[YouTube](https://www.youtube.com/@chr0ma313)).

## A note on AI tools

AI tools were used for a part of the work: mainly for the interface translations and for drafting texts such as
the help, and also for writing part of the code. The architecture and planning were done without them.
If this matters to you, there are other good tools for the same job, for example
[vLabeler](https://github.com/sdercolin/vlabeler) and [SLabeler](https://m-lo7.itch.io/slabeler).

## License

[MIT](LICENSE)
