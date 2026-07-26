# DECISIONS

## Current state
- No code yet. Spec in PROMPT.md. UI mockups in `design/mockups.html` (5 screens: share-resolve sheet, picker, downloads, sniffer browser, settings).
- Design system chosen: dark Material 3, burnt-orange primary `oklch(0.76 0.13 40)` on neutral near-black `oklch(0.115 0 0)`; teal accent for info; radius: sheets 28 / cards 12 / chips 8 / buttons pill. Tokens live at top of mockups.html.

## Next
- Scaffold Gradle project (copy version catalog from ~/repos/visit-logs/android).
- Build order per PROMPT.md: resolver → share activity → result list → downloads service.
- Translate mockup tokens into Compose M3 theme.

## Gotchas
- SDK at ~/android-sdk; /opt/android-sdk is empty stub.
- Preview mockups: `python -m http.server` from repo root; open design/mockups.html (Google Fonts needs network).

## Tried / rejected
- Light theme for mockups: rejected — media-consumption context, downloader audience expects dark.

## Log
- 2026-07-27 | Dark M3 + burnt-orange primary design system; 5-screen mockups committed | share-first flow validated visually before any Kotlin
