# PixelTune Performance Analysis

This document is an engineering audit of the recomposition, database,
playback-buffering and background-sync costs observed in PixelTune, and the
optimizations applied on the `perf/optimization-pass` branch. It replaces the
previously informal notes with measurements, file references and the
reasoning behind each change so future work can build on it.

## 1. UI recomposition

### 1.1 The "god state" problem

`PlayerUiState` (`presentation/viewmodel/PlayerUiState.kt`) is a ~40-field
aggregate published as a single `StateFlow`. It mixes concerns:

- playback/queue fields (`currentPlaybackQueue`, `currentQueueSourceName`,
  `currentPosition`, `preparingSongId`, undo-bar state),
- library/folder fields (`musicFolders`, `currentFolder`, sort options,
  view-mode and loading flags),
- search fields (`searchResults`, `searchHistory`, `selectedSearchFilter`,
  `isSearching`, `isOnlineSearch`),
- sync/AI flags (`isSyncingLibrary`, `isGeneratingAiMetadata`).

Any `_playerUiState.update { ... }` re-emits the aggregate, so every screen
that collects it recomposes even when none of the fields it renders changed.

Historically the worst offender was `SearchScreen.kt`, which collected the
entire aggregate while reading only five fields. Concretely, during ordinary
playback the aggregate re-emits on queue edits, dismiss-undo-bar show/hide,
`isSyncingLibrary` transitions during library syncs, restore snapshots and
position writes on pause/seek — none of which can change what the search
screen displays. (The 250 ms progress tick itself lives in
`PlaybackStateHolder.currentPosition` / `PlayerViewModel.currentPlaybackPosition`
and only writes `PlayerUiState.currentPosition` on pause/restore boundaries,
but every other aggregate emission still needlessly recomposed the screen.)

**Applied fix (search):**

- New `SearchUiSlice` (`presentation/viewmodel/SearchUiSlice.kt`) carries
  exactly the five fields the search screen renders.
- `PlayerViewModel.searchUiSlice` combines them from the `SearchStateHolder`
  flows that back the mirrored fields of `PlayerUiState`, applies
  `distinctUntilChanged()` and shares the result via
  `stateIn(WhileSubscribed(5s))`.
- `SearchScreen` collects the slice instead of the aggregate. By
  construction the screen now recomposes only when query results, the
  selected filter, search history, the in-flight flag or the online/local
  mode actually change.

**Expected effect:** during 60 s of uninterrupted playback with the search
screen open, aggregate emissions that previously recomposed the whole screen
(queue/undo-bar/sync/restore churn) no longer reach it; measured with
Layout Inspector composition tracing the SearchScreen composable count
should drop from "once per aggregate emission" to "only on search input
changes" (effectively zero recompositions while idle during playback).

**Applied fix (library):**

- `LibraryScreen` already observed a narrow projection
  (`LibraryScreenPlayerProjection`) for the tab area, but
  `LibraryAlbumsTab` and `LibraryArtistsTab` each internally collected the
  full `PlayerUiState` aggregate just for `isAlbumsListView`,
  `currentAlbumSortOption` and `currentArtistSortOption`.
- Both composables now receive those values as hoisted parameters from the
  parent projection, matching the pattern `LibraryFoldersTab` already used.
  The full-state collections inside tab composables are gone.

Remaining known consumers of the full aggregate (not touched in this pass,
candidates for follow-up slicing): `QueueBottomSheet`,
`UnifiedPlayerSheet`/`V2` and `MainActivity`.

### 1.2 High-frequency playback position

`PlaybackStateHolder` publishes the 250 ms position tick on a dedicated
`currentPosition` StateFlow, exposed as `PlayerViewModel.currentPlaybackPosition`
and consumed only by real-time UI (seek bar, lyrics timing, full player).
This is the correct architecture and is intentionally preserved; the slicing
work above ensures the tick never recomposes whole screens.

## 2. Database

`MusicDao.getSongByPath` (`SELECT * FROM songs WHERE file_path = :path`)
powers the file-path lookups used by the folder explorer and MediaStore
reconciliation. At schema v24 the `songs` table carried indices on
`parent_directory_path`, `content_uri_string`, `date_added`, `duration` and
the album/artist/genre columns, but **not** on `file_path` — the path
lookup ran as a full table scan on every check. Likewise `telegram_songs`
had no indices at all, so every channel-sync read/prune by `chat_id`
scanned the whole table.

**Applied fix:** schema v24 → v25 adds `index_songs_file_path` (and
idempotently re-asserts `index_songs_parent_directory_path`), plus an index
on `telegram_songs.chat_id`, the column every channel sync queries and
prunes by. `EXPLAIN QUERY PLAN` before/after (recorded in the pull-request
description): `getSongByPath` moves from `SCAN songs` to
`SEARCH songs USING INDEX index_songs_file_path`, and
`getSongsByChatId`/`deleteSongsByChatId` move from `SCAN telegram_songs`
to `SEARCH ... USING INDEX index_telegram_songs_chat_id`.

One pre-existing query-shape note: the filtered library queries of the form
`(:applyDirectoryFilter = 0 OR id < 0 OR parent_directory_path IN (...))`
cannot use the `parent_directory_path` index because the leading OR
preamble is not indexable; SQLite falls back to a title-index scan for the
ORDER BY. Rewriting those filters into a `CASE`/UNION form would let the
planner use the folder index — left as a follow-up since it changes query
semantics plumbing in `MusicRepositoryImpl`.

## 3. Playback buffering

`DualPlayerEngine` applied a single engine-wide `DefaultLoadControl`
(30 s/60 s/2 s/3 s) tuned for the localhost cloud-streaming proxies. That
profile is wrong for local files (waits for buffering that local disk I/O
does not need, wasting start latency and memory) and is not optimally sized
for the two distinct network paths.

**Applied fix:** a profile-switching `LoadControl` selects per-source
buffering constants — minimal for local files, moderate for proxied
personal-cloud sources (Telegram/Google Drive), generous for remote
streaming (YouTube/SoundCloud/Netease). Constants and their
start-up-latency vs. stall-rate rationale live in one place in
`DualPlayerEngine.kt`.

## 4. Background sync

Telegram channel sync fetched the entire channel history into a single
in-memory list and then replaced the channel's rows in one giant database
transaction; a 5,000+ message channel produced multi-minute syncs with no
progress feedback, peak-memory spikes and an unbounded transaction.

**Applied fix:** the history fetch is batched (~500 messages per batch,
five TDLib pages), each batch is upserted in its own transaction, progress
is reported per batch, a configurable cap bounds the backfill, and an
interrupted sync resumes from the last persisted batch boundary
(`telegram_channels.last_synced_message_id`).

## 5. Startup

- The hand-written `app/src/main/baseline-prof.txt` continues to ship in
  release builds.
- The `:baselineprofile` module and the `androidx.baselineprofile` plugin
  are wired so `generateBaselineProfile` (device/emulator required) emits
  rules into `app/src/release/generated/baselineProfiles/`, which release
  builds consume automatically; the `benchmark` build type is excluded from
  profile generation.

## 6. Diagnostics

A debug-only frame-timing monitor (JankStats) logs per-screen frame-time
histograms to Logcat with zero release-build impact, so regressions in the
recomposition work above are observable during development.
