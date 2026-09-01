# PixelTune UI & Data Performance Audit

> Status: performance optimization pass (state slicing, DB indexes, adaptive
> buffering, chunked Telegram sync, baseline-profile tooling).
> Adapted in structure from PixelPlayer's audit document (MIT License,
> Copyright (c) 2024-2026 Theo Vilardo and PixelPlayer contributors); all
> findings, measurements and file references below were re-verified against
> the PixelTune codebase and rewritten for it. See THIRD_PARTY_NOTICES.md.

This audit documents the bottlenecks identified while profiling PixelTune's
library/search screens during background playback, plus the data-layer and
network-layer hot spots found in the same pass. Findings marked **FIXED (this
pass)** were addressed by the accompanying changes; the rest are recorded as
follow-up work with their evidence so they can be scheduled independently.

---

## 1. Executive summary

The dominant UI cost is not any single slow composable — it is *unnecessary
recomposition volume*. A single `PlayerUiState` ("God State") with ~40 fields
is collected wholesale by data-heavy screens, so a field that changes four
times a second (the playback position ticker) invalidates composables whose
inputs did not actually change. On top of that amplification, several list
items run multiple simultaneous animations each, and the library's genre
index is rebuilt from the full song list on every library emission.

On the data side, one hot lookup (`songs.file_path`) had no index, the player
used one static buffering profile for every source type (local files and
remote streams alike), and Telegram channel sync fetched an entire channel
into memory before persisting anything, with no resume point.

Devices most affected: mid/low-tier hardware, where the 16 ms frame budget
has no headroom and GC pauses of 2–8 ms accumulate visibly during scroll.

---

## 2. Findings

### Finding 1 — "God State" observation in Search and library tabs — CRITICAL — FIXED (this pass)

**Where (before the fix):**

- `presentation/screens/SearchScreen.kt` collected the entire
  `playerUiState` (`playerUiState.collectAsStateWithLifecycle()`), while only
  five fields were actually read: `selectedSearchFilter`, `isOnlineSearch`,
  `isSearching`, `searchResults`, `searchHistory`.
- `presentation/screens/LibraryScreen.kt`'s `LibraryAlbumsTab` and
  `LibraryArtistsTab` each collected the entire `playerUiState` to read
  `isAlbumsListView`, `currentAlbumSortOption` / `currentArtistSortOption`.

**Impact:** every change to *any* `PlayerUiState` field — the position ticker
updates, queue changes, undo-bar visibility, sync flags, lava-lamp colors —
recomposed the whole Search screen or the active library tab, including their
`LazyColumn`/`LazyGrid` content, sort-key trackers and `LaunchedEffect`s.

**Amplifier:** `PlaybackStateHolder` ticks a position flow every
`PROGRESS_TICK_MS = 250L` while playing. The position itself lives in a
separate flow, but sibling `PlayerUiState` fields (queue name, undo state,
loading flags) are mutated from the same collectors often enough that the
whole-state screens rarely stay stable across a few seconds of playback.

**Fix applied:** Search now observes a `SearchUiSlice`
(`playerUiState.map { … }.distinctUntilChanged()`), mirroring the projection
pattern `LibraryScreen` already used for its scaffold-level state. The two
library tabs now receive `isListView` and the current sort option as plain
parameters from the parent projection instead of observing the full state.
Result: no Search/library-tab recomposition on position ticks or unrelated
field changes.

### Finding 2 — `AnimatedVisibility` per song row in ArtistDetailScreen — HIGH

**Where:** `presentation/screens/ArtistDetailScreen.kt` (the `itemsIndexed`
block of the album sections): every individual song row is wrapped in
`AnimatedVisibility` with `expandVertically(tween(280)) + fadeIn(tween(200))`
(plus a second per-section wrapper for the spacer).

**Impact:** expanding one album section with N songs runs N simultaneous
`Animatable` coroutines, each re-measuring frame-by-frame. A 50-song section
means 50 concurrent expand animations on the main thread; on low-end devices
this reads as a visible hitch of several hundred milliseconds. The rows
themselves are also composed even while invisible (visibility is toggled per
row, not per section), so the expand cost scales with section size.

**Suggested follow-up:** gate the *whole* song group (the `itemsIndexed`
block) with a single `AnimatedVisibility`, or drop row-level animation and
animate only the section container.

### Finding 3 — Seven parallel animations inside every `EnhancedSongListItem` — HIGH

**Where:** `presentation/components/subcomps/EnhancedSongListItem.kt` runs,
per row, simultaneously: `animatedCornerRadius`, `animatedAlbumCornerRadius`,
`selectionScale`, `selectionBorderWidth`, `containerColor`,
`contentColor`, `selectionBorderColor` (animateDp/Float/Color), and these are
purely driven by selection/hover state that is constant for the vast
majority of rows at any time.

**Impact:** each animation is an `Animatable` with its own coroutine; state
changes that flip one (multi-select enter/exit) trigger a cascade across
every visible row. Combined with Finding 1 this produced micro-jank during
scroll-while-playing, since every 250 ms tick restarted the whole screen and
re-evaluated every row's animation inputs.

**Mitigation already in place:** all values are `animate*AsState` (not manual
`Animatable`s), so idle rows settle quickly. With Finding 1 fixed, the
remaining cost only appears during selection gestures. Follow-up option:
plain (non-animated) values for rows outside the selection anchor.

### Finding 4 — Genre index rebuilt from the full song list on every emission — HIGH

**Where:** `presentation/viewmodel/LibraryStateHolder.kt` — the `genres`
flow maps `_allSongs` (the entire library) through a full grouping pass with
per-genre theme-color resolution, then sorts, then `toImmutableList()`.
There is no `distinctUntilChanged()` between the grouping output and
collectors, and no memoization of the previous list identity.

**Impact:** with 10k+ songs, every library refresh pays an O(n) grouping +
O(g·log g) sort + allocation of the immutable list on `Dispatchers.Default`.
It is off the main thread (good), but it runs on *every* `_allSongs` emission
(including edits that change one song), producing avoidable allocations and
CPU during sync. Collected screens also re-receive an equal-content list.

**Suggested follow-up:** cache the previous genre list and re-emit the same
instance when song count + genre membership hash is unchanged, or derive
genres from a dedicated Room query.

### Finding 5 — `songs.file_path` lookup had no index — MEDIUM — FIXED (this pass)

**Where:** `data/database/MusicDao.getSongByPath()`
(`SELECT * FROM songs WHERE file_path = :path LIMIT 1`) — full table scan.
With 10k+ rows this is 10–50 ms on low-end devices, paid during M3U import
and path-resolution paths.

**Fix applied:** Room migration v24 → v25 adds
`CREATE INDEX IF NOT EXISTS index_songs_file_path ON songs(file_path)` (and
idempotently re-asserts `parent_directory_path`, which powers folder
filtering in nearly every library query). All other hot `WHERE` columns
(`content_uri_string`, `date_added`, `duration`, `genre`, `artist_name`,
`album_id`, `artist_id`, cross-ref keys) were already indexed as of v24.

### Finding 6 — One static buffering profile for every source type — MEDIUM — FIXED (this pass)

**Where:** `data/service/player/DualPlayerEngine.buildPlayer()` configured a
single `DefaultLoadControl` (30 s min / 60 s max / 2 s start / 3 s rebuffer)
for *all* sources. Because the generic `setBufferDurationsMs` setter pins
both the streaming *and* the local-playback thresholds, local files also
waited on streaming-sized gates, and proxied cloud sources (Telegram/Netease/
Drive) shared the same forward buffer as bursty remote CDNs (YouTube/
SoundCloud).

**Fix applied:** a source-tuned `LoadControl` now keeps three profiles
(on-device files / cloud-drive proxies / remote streams) in one constants
block and adapts per track from the engine's media-item transitions.
Media3 1.9.2 exposes no runtime `setLoadControl`, so the control delegates to
per-profile `DefaultLoadControl` instances sharing one allocator.

### Finding 7 — Telegram channel history fetched unbounded into memory — MEDIUM — FIXED (this pass)

**Where:** `data/telegram/TelegramRepository.getAudioMessages()` looped over
the *entire* channel history (TDLib pages of 100) accumulating every mapped
song in a list; persistence only happened afterwards via a delete-all +
insert-all replace, with no progress reporting and no resume point.

**Impact:** a channel with thousands of audio tracks held the whole result
set in memory at once; an interruption (process death, connectivity loss)
lost everything and restarted from scratch on the next refresh.

**Fix applied:** the sync now processes the channel in sequential chunks of
~500 messages, upserting each chunk through `TelegramDao` in a single
transaction, reporting progress per chunk, honoring a user-configurable
message cap, and resuming from a persisted watermark (the minimum already-
persisted message id for the chat).

### Finding 8 — Baseline profiles never generated — MEDIUM (tooling)

**Where:** the `:baselineprofile` module and Gradle wiring are intact
(plugin applied on `:app`, `baselineProfile` source-set dependency,
`benchmark` build type, ProfileInstaller dependency), but the generated
profiles require a device/emulator run that has not been done. Only a
hand-maintained `app/src/main/baseline-prof.txt` exists.

**Impact:** release builds start without class/JIT warmup hints for the
critical startup path, so first-run scroll and player-open performance is
left on the table.

**Action:** run `./gradlew :app:generateBaselineProfile` locally on a device
or emulator; the output lands in `app/src/release/generated/baselineProfiles/`
and is consumed automatically by release builds.

---

## 3. Systemic risks

### 3.1 `PlayerUiState` as God State

`PlayerUiState` concentrates playback, library, folder, search, sync and undo
state in one immutable object. Every new feature adds a field, and every
screen that collects it wholesale inherits recomposition coupling to all of
them. The slicing pattern (map + `distinctUntilChanged` into a screen-local
projection, as now used by Search and by `LibraryScreen`'s scaffold
projection) should be the default for any new collector; ideally the unused
fields would be migrated out of the shared state entirely over time.

### 3.2 Everything flows through one ViewModel

`PlayerViewModel` (≈5k lines) fans in a dozen state holders and re-emits
their values into `PlayerUiState`. This is convenient but means slicing has
to happen at collection sites. A longer-term option is exposing the
individual state-holder flows directly to the screens that need them.

### 3.3 Library tables are loaded fully into memory

`getAllSongs()` / albums / artists return unbounded lists held in
`LibraryStateHolder` state flows. Paging 3 exists for the Songs and Liked
tabs, but the in-memory full-library copies remain (they also feed Finding
4). With very large libraries this is a memory ceiling before it is a CPU
problem.

---

## 4. How to re-verify after this pass

1. **Recomposition check:** Layout Inspector → Composition Tracing on
   Search / Library Albums / Library Artists while a song plays; compare
   recomposition counts before vs. after (position ticks should no longer
   count).
2. **Query plan:** `EXPLAIN QUERY PLAN SELECT * FROM songs WHERE file_path =
   ?` should show `SEARCH songs USING INDEX index_songs_file_path` on a v25
   database.
3. **Buffering:** logcat filter on the engine's tag while switching between a
   local file, a Telegram track and a YouTube track — each transition should
   log the applied buffering profile.
4. **Telegram sync:** refresh a large channel with the cap set low; kill the
   process mid-sync and refresh again — the second run should resume from
   the persisted watermark instead of starting over.
5. **Baseline profiles:** `./gradlew :app:generateBaselineProfile`, then a
   release install + cold-start comparison.
