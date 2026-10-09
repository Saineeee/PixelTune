# Changelog

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- **Confirmation dialog for clearing search history.** The "Clear All" button
  on the online recent-searches landing now asks for confirmation before
  wiping the history (same Material 3 dialog pattern the Listening History
  clear action uses), so one accidental tap can no longer destroy every
  recent query.
- **Max Search History slider** (Settings -> Appearance -> History Collection).
  Controls how many recent online searches the app collects, from 5 to 50 in
  steps of 5 (default 15 — the previous hard-coded limit). Lowering the slider
  immediately trims the stored history; new searches push the oldest entries
  out once the cap is reached. Applies live, no restart needed.
- **Max Listening History slider** (Settings -> Appearance -> History
  Collection). Controls how many distinct songs the Listening History (and the
  Recently Played surfaces it feeds) collects — local and online/cloud songs
  alike — from 10 to 100 in steps of 5 (default 30 — the previous hard-coded
  cap). Raising the cap pulls older entries back in; listening stats are never
  affected. Applies live, no restart needed.

### Fixed

- **Centered the "No recent searches" empty state.** The placeholder on the
  online search landing sat directly under the "Recent Searches" header
  (visibly top-heavy). It now centers in the middle of the visible body area,
  accounting for the bottom navigation bar and mini player overlay.
- **Centered the "No history yet" empty state.** The Listening History
  placeholder was centered against the physical screen bottom, which sits
  underneath the navigation bar and mini player — making it appear lower than
  the middle of the screen. It now centers within the actually visible area.

## [0.1.0-beta] - YYYY-MM-DD

**First public beta.** PixelTune is a fork of
[PixelPlayer](https://github.com/PixelPlayerHQ/PixelPlayer) with an original streaming and cloud-catalog layer on top.

### Added

- **YouTube Music & YouTube streaming.** Search the whole catalog and play
  instantly with no account or API key. Extraction uses NewPipeExtractor, and
  streams are routed through a local Ktor proxy so ExoPlayer gets a stable HTTP
  source with proper seeking and range support.
- **SoundCloud streaming.** The same instant-play experience, surfaced in
  search and the Cloud Catalog.
- **Cloud Catalog.** A single browsing surface for online playlists and
  artists, with cover art, uploader info, track counts, online/offline filter
  chips and infinite pagination.
- **Offline downloads.** Download any streaming track for offline listening.
  Files are kept in private app storage, progress shows in notifications, and a
  dedicated Downloads library keeps them organized.
- **Playlist import with preview.** Paste a playlist URL from Spotify, Apple
  Music or YouTube. Tracks are matched to YouTube by fuzzy title and artist
  similarity (Jaccard-based scoring), and a preview screen shows each candidate
  with a reason chip so mismatches can be swapped or dropped before saving.

### Changed

- Updated Kotlin to 2.4.21.
- PixelTune is licensed under GPL-3.0. Code derived from PixelPlayer's
  pre-May-2026 MIT-licensed snapshot remains under MIT, and the notices are
  kept in `THIRD_PARTY_NOTICES.md`.

### Removed

Compared with the PixelPlayer base:

- Wear OS companion app, along with the module used for phone-to-watch
  communication.
- NetEase Cloud Music integration.
- Telegram channel streaming (TDLib).
- Google Drive streaming.
- All AI-driven features, including AI playlist generation and AI metadata
  (Gemini and DeepSeek).
- Chromecast (Cast) support.
- Firebase, including Crashlytics crash reporting.
- Google Play Billing.
