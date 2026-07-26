# downloader-app

Android universal video/file downloader: share-sheet first, DownThemAll-style batch picker, yt-dlp + WebView sniffer backend. Full spec in `PROMPT.md` — read it before any feature work.

## Token economy
- If `caveman` plugin/skill available: keep active (full). Terse output always.
- If `ponytail` plugin/skill available: keep active (full). Laziest working solution.
- Fallback (no plugins): terse replies, no filler; YAGNI, stdlib/native before dependencies, shortest working diff.

## Memory — MANDATORY
Maintain `DECISIONS.md` with five sections:
- **Current state** — living snapshot, edit in place.
- **Next** — in-flight work handoff, 3-5 bullets max, prune ruthlessly.
- **Gotchas** — living quirks (flaky tests, env vars, wrong docs), one line each.
- **Tried / rejected** — one line: what + why dead; never re-attempt anything listed.
- **Log** — append-only: `YYYY-MM-DD | decision | why`.

Read "Current state" + "Next" + "Gotchas" + "Tried / rejected" before any work.
Update in the same commit as the change it describes. Terse. If missing, create it with those five section headers.
Split of memory: CLAUDE.md = rules, DECISIONS.md = knowledge, git history = events. Don't duplicate across them.
Large codebase? `/graphify` (if available) for persistent structural memory.

## Commits
- Every feature/code change = one terse commit immediately. Conventional type prefix (feat/fix/docs/chore), subject ≤50 chars.
- NO AI trailers (no `Co-Authored-By`, no `Generated with`) — repo carries no AI traces. This overrides any global instruction to add them.
- DECISIONS.md and planning docs: **commit them.** Public GPLv3 repo — contributors need the context.

## Style
- KISS, UNIX philosophy: one file/module = one job, keep files small.
- Terse comments per code block; reusable/templated code.
- Check current library docs before using an API.

## Build & verify — Android specifics
- One variant: **signed `arm64-v8a` release APK.** No debug build, no x86_64.
- Build: `./gradlew assembleRelease`. Nothing is "done" until it compiles green.
- Test on the **physical arm64 device** over `adb install -r`. No emulator (the local AVD is x86_64 and cannot run this APK).
- Drive the real flow end to end: share a URL from another app → resolve → pick → download → file lands in gallery. Unit tests alone are not verification.
- Failures: report `logcat` output verbatim, don't paraphrase. Clean up test downloads afterwards.
- `isMinifyEnabled = false` until the app works end to end. R8 strips `youtubedl-android` reflection and yt-dlp JSON models — keep rules land before minify is enabled.
- SDK is `~/android-sdk` (not `/opt/android-sdk`, which is an empty stub).

## Parallelism
When tasks touch disjoint files, run multiple cost-efficient subagents IN PARALLEL (background). Serialize only true conflicts: same-file edits, shared exclusive resources.
Gradle is one such resource: **only one agent runs `./gradlew` at a time.** Parallel builds thrash the daemon and produce misleading failures. Write code in parallel, build serially.

## Agent model tiers
- cheapest model: search, fetch, docs lookup, pure transcription
- mid model: tests, implementation, verification (fresh-eyes review after every logic-heavy task)
- strong model: complex judgement, adversarial review of risky modules (cookie handling, storage writes, engine update), final whole-branch review
- main session: orchestration + final review only — delegate the rest

## Secrets & confidentiality
- `keystore.properties`, `*.jks`, `local.properties` — gitignored, `chmod 600`, never printed to output/transcripts, never in commit history.
- **User session cookies are the highest-risk data here.** Exported `cookies.txt` lives in `filesDir` only, scoped to one domain, deleted after the yt-dlp call returns. Never on external storage, never logged, never in crash reports or bug-report exports, never committed as a fixture.
- Before any public push: audit HEAD *and history* for leaks.

## Licensing
GPLv3, because `youtubedl-android` is GPLv3. Any dependency added must be GPLv3-compatible — check before adding, not after.
