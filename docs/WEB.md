# Web edition

Build with Node.js 22+ and Java 21:

```sh
npm ci --prefix web
./gradlew :app:webArchive -Pdistribution=web
```

The **build** GitHub Action builds the web edition alongside the native apps and runs
fast unit checks. Browser UI tests are separate and do not delay these builds or releases.
Tagged releases include `mLabeler-<version>-web.zip` in addition to the native downloads.
The separate manual **Web deployment archive** workflow builds only the web edition;
it does not publish a GitHub release or deploy a website. Download the `mLabeler-web`
Actions artifact and extract the ZIP inside it. Locally, the archive is at
`app/build/outputs/web/mLabeler-<version>-web.zip`.

Upload its **contents** to an HTTPS directory, for example `msan.pw/mlabeler/web/`.
Keep the directory structure, including `inference/` and `mcfl/`. No backend,
Python installation, account or database server is needed. Serve `.wasm` files as
`application/wasm` and `.js`/`.mjs` as JavaScript. Serve `index.html` with revalidation
(`Cache-Control: no-cache`) so updates do not leave an old page pointing at removed
bundles. Do not install a catch-all HTML fallback for missing asset requests.
Enable gzip or Brotli for JavaScript and WebAssembly on the host to reduce the initial download.
No cross-origin isolation headers are required. Local testing needs HTTP, not `file://`:

```sh
cd web
node serve.mjs
# http://127.0.0.1:4173/mlabeler/
```

## Files and privacy

Import a folder, select multiple files (WAV plus labels), or import a project ZIP.
**Open folder**, the File menu, and Ctrl/Cmd+O open the browser's directory picker;
**Import files / ZIP** is a separate file picker. Browsers without directory selection
show a translated explanation instead of silently substituting a file picker.
File selection is filtered to WAV, label formats and project/voicebank sidecars.
Folder and ZIP imports skip unsupported files (including compressed audio, executables,
scripts and model weights), with a notice; an unsupported-only import leaves the
current project intact. Project metadata and label backups remain importable.
The web edition edits a copy in browser storage; it does not modify the source folder.
**Download project** first saves pending edits and recordings, then downloads a ZIP
containing the project, recordings, labels and project metadata. Download before
switching projects or clearing browser data. A browser can evict its own storage;
private browsing and storage quotas can also prevent persistence. Storage failures
leave the working copy in memory and show a translated message offering manual download.
Only one project is kept in browser storage; avoid editing it in multiple tabs.

Recordings and labels stay on the device. The site loads the editor and, only when
requested, the built-in MCFL model. It has no analytics, remote inference or toolkit
connection. Hosting access logs still follow the hosting provider's configuration.

The in-memory workspace is limited to **256 MB and 20,000 files**, including settings
and temporary saves. ZIP extraction checks paths and actual decompressed size before
replacing a project. Large datasets should use the native app.

## Browsers and capabilities

Requires WasmGC: Chrome/Edge 119+, Firefox 120+, or Safari 18.2+ (including iOS/iPadOS 18.2+).
Use current browser releases. Older browsers get a readable upgrade message.
The same import/export path works without `showDirectoryPicker`, including Safari
and Firefox. Folder selection depends on the browser's picker; multiple files and ZIP
are alternatives. Microphone recording requires HTTPS or localhost and permission.
Audio stops when the page is hidden; keep the tab visible while recording.

The editor reuses the phone/desktop adaptive layouts, touch gestures and all nine
languages: English, Russian, Japanese, Simplified Chinese, Korean, French, German,
Spanish and Brazilian Portuguese. The browser shell and errors use these languages too.
The shell follows the editor's theme, accent, corner radius and interface scale.
The archive includes an AOSP fallback font for Chinese, Japanese and Korean, plus
Noto fonts for phonetic characters and interface symbols. All fonts
are served by the same website and do not contact a third-party font service.

The first web edition supports WAV audio and local editing/exports, automatic oto,
the built-in MCFL model and microphone recording. MCFL runs in a dedicated worker
so inference does not block the interface. Native JavaScript plugins and custom check
scripts, toolkit features, Discord integration and application self-updates are excluded.
Opening local executables and arbitrary native file paths is unavailable. Updates come
from deploying a new website archive and reloading the page.

## Checks

In Actions, run **Web UI tests (manual)** → **Run workflow** when browser checks are
needed. Choose `all` or a single browser/layout. This workflow builds the web archive,
unpacks that exact artifact and tests it with at most two workers. Screenshots and
traces from failures are available in `web-browser-test-results`. It never runs
automatically on pushes, pull requests or release tags.
On Linux CI, Firefox runs with a window under Xvfb and Mesa software rendering:
the headless runner can refuse WebGL2 before the editor starts. Startup errors are
reported immediately, and the browser suite has a 15-minute overall timeout.

```sh
npm test --prefix web
cd web
npx playwright install chromium firefox webkit
npm run test:browsers
```

Browser checks exercise Chromium, Firefox and WebKit, with desktop layouts plus touch
phone layouts in Chromium and WebKit, under a subdirectory URL. These cover real
browser engines, not a physical iPhone or every browser version. Safari on a real
iPhone and Edge should also receive a release smoke check.

The initial Windows verification passed Chromium, installed Edge and WebKit layout,
editing, ZIP round-trip, storage failure and worker-inference checks. Audio output and
synthetic microphone capture passed in Chromium; output also passed in Edge. Windows
Playwright WebKit does not expose Web Audio, so that part needs the Linux CI or real Safari.
Its Windows screenshots also omit the WebGL canvas even though semantic controls and
editing tests work. This resembles the upstream [Windows WebKit screenshot issue](https://github.com/microsoft/playwright/issues/42885);
these screenshots do not verify Safari rendering. Chromium/Edge screenshots were inspected,
including phonetic labels and interface symbols.
The local Firefox binary could not start due to a Windows side-by-side/mozglue error;
Firefox remains a non-skipped project in the optional browser suite. A physical iPhone has not been tested.
