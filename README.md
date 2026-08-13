# graball

Android video and file downloader. Share a link to it, pick what you want, get the file.

**[Download the latest APK](https://github.com/shourovrm/graball/releases/latest)** · arm64-v8a · Android 8.0+

---

## What it does

Share any URL to graball — from a browser, a chat app, anywhere — and it resolves the page into a
flat list of everything downloadable: video variants, audio, images, documents, archives. Pick with
checkboxes, download in the background.

Three ways in, one resolver behind all of them:

- **Share sheet** — the primary path. `ACTION_SEND` or an `https` link from any app.
- **Built-in browser** — navigate, then hit Grab to collect what the page loaded.
- **Clipboard** — paste a URL directly.

## Features

**Picker** — DownThemAll-style. Filter chips with counts (All / Video / Audio / Images / Docs /
Archives), text filter, sort by title, size or kind, select all, invert. Adaptive-stream variants
collapse into one expandable row so you pick the resolution you actually want.

**Downloads** — background service, resumable. Pause and resume mid-file, retry failures, cancel.
Progress survives closing the app. Finished files land in your gallery or a folder you choose per
media kind.

**Browser** — ad blocking (StevenBlack hosts), HTTPS-only mode, search engine choice, history
clearing. It sniffs network requests, the DOM, and `fetch`/`XHR`/`MediaSource` for stream URLs
yt-dlp can't see.

**Google Drive** — folders are listed natively, with real filenames, types and sizes. Google Docs,
Sheets and Slides download as `.docx` / `.xlsx` / `.pptx`.

**Sign-in** — for content that needs an account, sign in through the in-app browser. Cookies stay
scoped to that one domain.

## Install

Grab the APK from [Releases](https://github.com/shourovrm/graball/releases/latest) and install it.
You will need to allow installing from unknown sources.

Only `arm64-v8a` is published — that covers essentially every phone made since 2017. The APK is
large (~69 MB) because Python, yt-dlp and FFmpeg are bundled; nothing is downloaded at first run.

## Privacy

- No analytics, no telemetry, no crash reporting, no accounts.
- Cookies are read only from graball's own WebView. Other apps' cookie jars are unreachable by
  design.
- When a download needs your login, cookies for that **one** domain are written to app-private
  storage for the duration of a single call, then deleted. Never logged, never written to external
  storage, never included in a bug report.
- The engine updates only when you explicitly ask it to.

## Build

Needs the Android SDK and a JDK. Signing reads from `keystore.properties` (gitignored) — see
`app/build.gradle.kts`.

```sh
./gradlew assembleRelease     # signed arm64-v8a release APK
./gradlew testReleaseUnitTest # unit tests
```

There is no debug variant by design. Output lands in `app/build/outputs/apk/release/`.

## Built on

[youtubedl-android](https://github.com/junkfood02/youtubedl-android) (yt-dlp + Python + FFmpeg),
Kotlin, Jetpack Compose, Material 3, Room.

## Licence

GPLv3 — see [LICENSE](LICENSE). Required by youtubedl-android, which graball depends on.
