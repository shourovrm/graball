# DECISIONS

## Current state
- Full app implemented, compiles green: signed arm64-v8a release APK (~69MB, ffmpeg+python bundled), 15/15 unit tests pass.
- Packages: `resolve` (yt-dlp -J → models, error taxonomy), `share` (bottom-sheet entry), `ui.picker` (DownThemAll list), `download` (Room SoT + dataSync service, progress-template, Semaphore(2), MediaStore publish), `browser` (WebView sniffer + JS hooks + FAB), `cookies` (hardened export + SignInActivity), `engine` (update check/banner/consent), `ui.settings`, `ui.downloads`.
- MainActivity = 3 fixed tabs (Downloads/Browser/Settings), update banner on Downloads tab. Theme: light+dark from mockup tokens, follows system.
- Engine: io.github.junkfood02.youtubedl-android **0.18.1** (Maven Central, not JitPack).
- Design system: dark M3, burnt-orange primary; tokens at top of design/mockups.html.

## Next
- Device E2E: adb install, share URL → resolve → pick → download → gallery (blocked: no device attached).
- Real repo URL in SettingsScreen SOURCE_URL placeholder.
- R8 keep rules before ever enabling minify.
- Device-verify address bar rework + adblock + https-only (no gradle run yet, review-only pass).

## Gotchas
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
- 2026-07-27 | browser: address bar rework (placeholder/lock/Go/clear/empty-state), AdBlocker.kt hosts-set blocking, https-only redirect | user reported "no address bar" — it existed but had no affordance; adblock/https-only wired into existing Prefs flows and SniffingClient
- 2026-07-27 | download/ui.downloads: delete-file + delete-all, notif tap opens app, SAF custom folders (Prefs.folderFor) with MediaStore fallback | user-requested feature wave, scoped to Db/DownloadService/DownloadsScreen/DownloadCard only
- 2026-07-27 | 3 review waves (adversarial cookie/opus, fresh-eyes/sonnet, whole-branch/opus): 2 blockers + backup-leak critical fixed pre-device | reviews cheaper than device debugging
- 2026-07-27 | Full app v0.1.0 implemented in parallel subagent wave, single integration build pass | disjoint packages let 6 agents write concurrently, gradle stayed serial
- 2026-07-27 | Cookie flow: WebView-only source, one getCookie(https://host) query, per-call Netscape file in filesDir | host query already returns applicable domain cookies; widening the export buys nothing and leaks sibling subdomains
- 2026-07-27 | Dark M3 + burnt-orange primary design system; 5-screen mockups committed | share-first flow validated visually before any Kotlin
