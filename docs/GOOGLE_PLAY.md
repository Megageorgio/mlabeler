# Google Play build

The manual **Android Play bundle** workflow (`.github/workflows/android-play.yml`) builds an Android App Bundle
for Play Console. It never creates a GitHub release, tag or Play rollout. It has read-only repository permissions.
The existing `build` workflow and its release APKs are unchanged.

## Run

1. Push the workflow and its supporting changes to the repository's default branch.
2. Open **Actions → Android Play bundle → Run workflow** and select the desired ref.
3. Set `version_code` to an unused integer greater than every version code already uploaded to Play Console.
   The current proposed code for 0.1.2 is `100301`; do not reuse it after uploading that bundle.
4. Download the successful run's **mLabeler-play-CODE** artifact. Upload its `.aab` to the intended Play track yourself.

The workflow requires the existing repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`
and optionally `ANDROID_KEY_ALIAS` (default `mlabeler`). No temporary/debug signing-key fallback is permitted
for a Play release. The artifact includes a SHA-256 checksum, and expires after 30 days.

The workflow runs core and targeted state/UI tests, exercises document IO and failed-save recovery on Android,
validates the actual AAB with pinned bundletool, inspects its manifest, confirms the built-in quick labelling
model and 16 KB alignment of 64-bit native libraries, and verifies the JAR signature.
Test provider code is in the instrumentation APK only and is checked not to appear in the release manifest.

## Behaviour

| Feature | Regular builds | Play build |
|---|---|---|
| Application ID | `pw.msan.mlabeler` | `pw.msan.mlabeler` |
| Local labels, recording, audio editing, quick labels and refinement | Available | Available |
| Built-in auto-oto (loudness/voicing), preview, aliases and profiles | Available | Available |
| mVocalToolkit connection, aligner, transcription, synthesis and note/transition audition | Available | Hidden and blocked |
| GitHub APK update checks/installer | Existing behaviour | Hidden and blocked; updates come from Play |
| Shared project folders | Existing direct file access | System document-tree picker, persisted read/write grant |
| App network permission | Present on Android | Absent |
| Broad storage / package-install permissions | Existing behaviour | Absent |

The Play variant is selected at build time with `-Pdistribution=play -Pplay.versionCode=CODE`.
Feature flags are regenerated when switching distributions; the default remains the regular distribution.
There is no runtime switch to re-enable networking in the Play variant.

On Android, choose a project subfolder through the system picker and grant access. Android restricts selecting
certain roots (for example the root of Downloads on Android 11+); use a subfolder instead. New exports outside
that tree require selecting their destination folder too. Previous direct-path recent folders need to be
selected again. Shared project data is not moved or deleted by changing builds.

Project writes use a temporary document, a renamed original backup and commit/rollback through the document
provider. Providers that cannot safely rename/replace documents fail the save instead of truncating the only
copy. A failed save retains pending edits. Use a writable provider with rename support, such as local storage.

## Local builds and tests

With Android SDK and JDK 21 configured:

```sh
./gradlew :app:assembleDebug :app:connectedDebugAndroidTest -Pdistribution=play -Pplay.versionCode=100301
./gradlew :app:bundleRelease -Pdistribution=play -Pplay.versionCode=100301
python tools/check-play-package.py app/build/outputs/bundle/release/app-release.aab --bundletool /path/to/bundletool.jar --version-code 100301
```

Release signing reads `MLABELER_KEYSTORE`, `MLABELER_KEYSTORE_PASSWORD`, `MLABELER_KEY_ALIAS`, and optional
`MLABELER_KEY_PASSWORD` from the environment, like the regular release workflow. Do not put passwords in Gradle
properties or commit keys. In PowerShell quote dotted properties, e.g. `'-Pplay.versionCode=100301'`.

## Play Console and listing

The previously prepared general Android AAB is not this build: use the newly generated artifact and exclude
old bundles from the new release. If restricted-permission declarations still appear, check older active tracks.
Policy compliance and store approval are not guaranteed by successful compilation or manifest validation.

The Play listing must describe the offline edition and must not advertise toolkit features. The local kit in
`store/google-play/` contains updated copy and a policy explaining both Android distributions. Publish its
updated policy before submitting; the Play app's About page links to
`https://msan.pw/mlabeler/privacy/index.html`. Publishing a workflow does not publish that web page.

For an exclusively offline Play edition, the app does not transmit project data or analytics. Data safety
answers must be based on the versions actually distributed in Play, including any other active tracks; do
not reuse the previous network-toolkit declarations blindly. External links open the user's browser.
