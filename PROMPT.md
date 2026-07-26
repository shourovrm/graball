Build an Android video/file downloader in this repo. Kotlin + Jetpack Compose, Material 3, compileSdk 35, minSdk 26, GPLv3, shipped via GitHub Releases + F-Droid/IzzyOnDroid. Copy the version catalog from `~/repos/visit-logs/android/gradle/libs.versions.toml` (AGP 8.13, Kotlin 2.4, Compose BOM 2026.06.01, Room 2.8.4, Work 2.11.2) — known-building on this machine. **Build one signed `arm64-v8a` release APK only: no debug variant, no x86_64 split.** Testing is on a physical arm64 device via `adb install`; add an x86_64 split later only if an emulator is ever needed. Keep `isMinifyEnabled = false` until the app works end-to-end — R8 strips `youtubedl-android`'s reflection and yt-dlp's JSON model classes, so keep rules must land before minify is turned on. Signing config reads from `keystore.properties` (pattern in `~/repos/omeron`), never hardcoded, and `keystore.properties` + `*.jks` go in `.gitignore`.

Primary entry is the share sheet: `ACTION_SEND text/plain` + `VIEW https` into a bottom-sheet activity (`android:exported="true"`) that extracts the URL with `android.util.Patterns.WEB_URL`, resolves, downloads, dismisses. Secondary entries: in-app WebView browser, clipboard paste. One resolver behind all three.

Engine: `yausername/youtubedl-android` (bundles yt-dlp + Python + FFmpeg — do not use the retired ffmpeg-kit). Expose `updateYoutubeDL()` as an explicit user-consented "Update engine" action, never silent (F-Droid policy), blocked while downloads run. FFmpeg only for muxing `bestvideo+bestaudio`, HLS remux, audio extraction.

Fallback engine: WebView sniffer — `WebViewClient.shouldInterceptRequest` plus injected JS scraping `<a href>`, `<video>/<source>`, and hooking `fetch`/`XHR`/`MediaSource` for blob/MSE streams. Route: try yt-dlp first, fall back to sniffed hits.

Result screen is DownThemAll-style: one flat list of everything found, filter chips (All/Video/Audio/Images/Docs/Archives) with counts, text filter, sort menu, long-press multi-select with contextual app bar (Select all / Invert). Adaptive-stream variants collapse into one expandable row. Long-press the sniff FAB = download best immediately, no sheet.

Cookies come only from the app's own WebView `CookieManager` — Chrome's and the YouTube app's jars are unreachable by design. On `NEEDS_LOGIN`, prompt in-app sign-in, persist per domain, export a domain-scoped Netscape `cookies.txt` to `filesDir` for the single yt-dlp call, delete immediately after. Never dump all cookies, never log it, never write to external storage.

State: Room is the single source of truth (`QUEUED/RESOLVING/RUNNING/MUXING/MOVING/DONE/FAILED` + progress, eta, formatId, errorClass, rawLog, engine version). UI is a `Flow` over the DB; a `dataSync` foreground service is the only writer. Must survive process death with no stuck bars.

Progress from `yt-dlp --progress-template`, never stderr regex (the engine self-updates and will break it). Render DownThemAll-style segmented bars from `--concurrent-fragments`. Cap concurrent downloads with `Semaphore(2)`.

Errors translate to one sentence plus one action (`NEEDS_LOGIN`, `DRM`, `GEO_BLOCKED`, `EXTRACTOR_BROKEN`, `NETWORK`, `STORAGE_FULL`, `UNKNOWN`); raw log only behind Details/Copy. yt-dlp writes to `cacheDir`, then one atomic `MediaStore` insert, then delete temp — `MOVING` is a recoverable state.

Out of scope: player, feed, subscriptions, transcoding, scheduler, playlist manager, DRM content. Build in order: resolver → share activity → result list → downloads service. Ship a runnable app before adding the browser.
