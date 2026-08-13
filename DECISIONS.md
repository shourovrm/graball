# DECISIONS

## Current state
- v0.3.0: direct files (DIRECT_FORMAT) bypass yt-dlp entirely — `DirectDownloader.kt` (HttpURLConnection, 4-way chunking above 8 MB, Range resume, no new deps). Pause/resume across both backends. 35/35 unit tests pass.
- Full app implemented, compiles green: signed arm64-v8a release APK (~69MB, ffmpeg+python bundled), 15/15 unit tests pass.
- Packages: `resolve` (yt-dlp -J → models, error taxonomy), `share` (bottom-sheet entry), `ui.picker` (DownThemAll list), `download` (Room SoT + dataSync service, progress-template, Semaphore(2), MediaStore publish), `browser` (WebView sniffer + JS hooks + FAB), `cookies` (hardened export + SignInActivity), `engine` (update check/banner/consent), `ui.settings`, `ui.downloads`.
- MainActivity = 3 fixed tabs (Downloads/Browser/Settings), update banner on Downloads tab. Theme: light+dark from mockup tokens, follows system.
- Engine: io.github.junkfood02.youtubedl-android **0.18.1** (Maven Central, not JitPack).
- Design system: dark M3, burnt-orange primary; tokens at top of design/mockups.html.

## Next
- Drive folder fix is device-verified end to end. Still open: nested Drive folders are skipped (no
  recursion) and a Drive file whose yt-dlp extraction fails has no direct-download fallback.
- Direction A picked (Links/Media tabs): still to build — detection sweep (drop naturalWidth gate, srcset/data-src/picture/CSS backgrounds, MutationObserver rescan, HEAD probe, wider ext maps) + Links tab chips.
- Paused row shows no percentage ("Paused" only) — add "Paused at 62% · 47 MB/75 MB".
- Torrent (aria2c, +5.4 MB) deferred; multi-file torrents need a publish path that handles a directory.
- User re-test wave 2 (v0.2.0 apk at repo root): delete/delete-all, notification tap, address bar+search, adblock, https-only, folders, theme, clear history.
- Real repo URL in SettingsScreen SOURCE_URL placeholder.
- R8 keep rules before ever enabling minify.
- Device-verify address bar rework + adblock + https-only (no gradle run yet, review-only pass).

## Gotchas
- yt-dlp's `-o "...%(title)s.%(ext)s"` doubles the extension whenever the extractor's title already
  ends in one (every Google Drive filename does): `clip.webm` -> `clip.webm.webm`. `publishName()`
  collapses one repeated tail; a real chain like `title.f137.mp4` must survive.
- Extension parsing must run on the **last path segment**, not the whole URL. `SniffStore.kt:49` and
  `Resolver.kt:93` both do `url.substringAfterLast('.')`, so an extensionless URL returns the tail of
  the *host* — `https://lh3.googleusercontent.com/u/0/d/<id>` yields ext `com/u/0/d/<id>`.
- Google Drive folder pages embed the whole listing in `window['_DRIVE_ivd']`: per item
  `[0]=fileId, [2]=filename-with-ext, [3]=mimeType, [13]=exact bytes`. One plain GET, no API key.
  yt-dlp's own folder path (scraped key + clients6 batch API) is currently dead.
- Drive thumbnails live on `lh3.googleusercontent.com/u/0/d/<id>=w522-h391-...` — 522x391 crops, never
  the file. The sniffer must never offer them as downloads.
- A WebView that is attached but never told to load anything paints black **over the address bar strip above it** — the bar is laid out and functional (uiautomator sees the EditText and typed text) but completely invisible. Always `loadUrl("about:blank")` at creation, and filter that URL out of address-bar/title/empty-state state.
- DirectDownloader chunking only starts on a real **206** probe response — an `Accept-Ranges: bytes` header alone is a promise servers break, and a 200 full body written into chunk slot 2+ silently corrupts the merge.
- Every DirectDownloader request sends `Accept-Encoding: identity`. With gzip on, HttpURLConnection transparently decodes, byte offsets stop matching Content-Length, and every resume lands at the wrong offset.
- A fully-downloaded chunk on resume must be detected with `start + already > chunkEnd` (not `> chunkEnd + 1`), else it re-requests `bytes=(end+1)-end` and takes a 416.
- `pause()` writes PAUSED to the DB *before* killing the process, so `runItem`'s cancellation catch can tell pause from cancel and leaves the partial files alone.
- Address bar looked broken because it had no placeholder/hint and no visible Go affordance beyond
  the keyboard IME action, and the WebView started fully blank — fixed with placeholder text, an
  https-only lock icon, an always-visible Go button + focused clear (X), and an empty-state start page.
- DownloadService DB writes: ONLY through dbCtx (limitedParallelism(1)) + updateIfLive guard — a throttled progress write must never resurrect a terminal row.
- UI-side cancel/retry/delete write the DB from outside the service (spec says service = only writer). Accepted deviation: same process, writes serialized via Room; revisit if a second writer race ever shows.
- Cookies: only `com.graball.cookies.CookieExport` touches values. File = noBackupFilesDir/cookies/c-<uuid>.txt (excluded from backup), deleted in `finally` + swept on app start. Never log it, never widen scope past the exact host, never move it off private storage.
- yt-dlp's cookie jar loads with `ignore_expires=True` and needs exactly 7 tab fields, so expiry `0` (session cookie) is kept.
- Engine UpdateChannel accessors are literally `_STABLE`/`_NIGHTLY` (verified in 0.18.1 bytecode).
- Kotlin 2.4: `kotlinOptions{}` is a hard error — use `kotlin { compilerOptions { jvmTarget.set(...) } }`.
- `import kotlinx.coroutines.resume` is internal — use `kotlin.coroutines.resume` with suspendCancellableCoroutine.
- No gradle wrapper dist download needed: gradle-8.13 cached in ~/.gradle/wrapper/dists; first build took ~36min (dep downloads).
- SDK at ~/android-sdk; /opt/android-sdk is empty stub.
- Preview mockups: `python -m http.server` from repo root; open design/mockups.html (Google Fonts needs network).

## Tried / rejected
- Light theme for mockups: rejected — media-consumption context, downloader audience expects dark.
- JitPack for youtubedl-android: rejected — 0.18.1 lives on Maven Central under io.github.junkfood02.
- NavHost for main nav: rejected for now — 3 fixed tabs, plain index state; add when deep links needed.

## Log
- 2026-08-13 | Drive folder fix: `extOf()` shared helper, `resolve/GoogleDrive.kt` folder lister, sniffer drops `lh<N>.googleusercontent.com` + `drive.google.com/thumbnail` | Resolver.resolve() owns the Drive branch, so share sheet and browser FAB are both fixed by one edit. 46/46 unit tests green, release APK builds. `IVD_RE` + unescaper checked against the live folder page: 3 items, exact names/mimes/sizes
- 2026-08-13 | Drive folder diagnosed on device, 3 mockups in design/mockups-drive.html | picker showed 21 rows named after Drive thumbnail CDN ids with ext `com/u/0/d/...`; two independent causes (whole-URL ext parse + sniffer treating previews as files), plus yt-dlp's folder extractor dead upstream. Fix chosen: list the folder ourselves from `_DRIVE_ivd`, per-file yt-dlp
- 2026-08-12 | v0.3.0: DirectDownloader (own HTTP downloader for direct files) + pause/resume + published filenames no longer carry the row id | 42 images meant 42 Python spawns via yt-dlp; resume was impossible because cancel() deleted the .part. Device-verified: 78 MB archive paused at 15%, resumed at 62%, sha256 matches upstream
- 2026-08-12 | aria2c rejected for now | yt-dlp resumes on its own and detection/queue work needs no new binary; aria2c is only required for torrent, deferred as its own change
- 2026-08-12 | 3 grab-panel mockups (design/mockups-grab.html) for non-video files; scope set to full detection sweep + reuse of the existing picker sheet | images/pdf/zip were never surfacing: naturalWidth>=200 gate kills lazy images, extensionless a[href] dropped, Accept header used as mime hint
- 2026-07-27 | Wave 2 (user feedback): delete perm/all, notif tap intent, address bar+search engines, StevenBlack adblock (93k hosts asset), https-only, SAF folders per kind, theme pref, clear history | v0.2.0
- 2026-07-27 | browser: address bar rework (placeholder/lock/Go/clear/empty-state), AdBlocker.kt hosts-set blocking, https-only redirect | user reported "no address bar" — it existed but had no affordance; adblock/https-only wired into existing Prefs flows and SniffingClient
- 2026-07-27 | download/ui.downloads: delete-file + delete-all, notif tap opens app, SAF custom folders (Prefs.folderFor) with MediaStore fallback | user-requested feature wave, scoped to Db/DownloadService/DownloadsScreen/DownloadCard only
- 2026-07-27 | 3 review waves (adversarial cookie/opus, fresh-eyes/sonnet, whole-branch/opus): 2 blockers + backup-leak critical fixed pre-device | reviews cheaper than device debugging
- 2026-07-27 | Full app v0.1.0 implemented in parallel subagent wave, single integration build pass | disjoint packages let 6 agents write concurrently, gradle stayed serial
- 2026-07-27 | Cookie flow: WebView-only source, one getCookie(https://host) query, per-call Netscape file in filesDir | host query already returns applicable domain cookies; widening the export buys nothing and leaks sibling subdomains
- 2026-07-27 | Dark M3 + burnt-orange primary design system; 5-screen mockups committed | share-first flow validated visually before any Kotlin
