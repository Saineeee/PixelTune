# Changelog

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
