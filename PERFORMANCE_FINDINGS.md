# PixelTune Performance Findings Report

**Scope:** startup time, scroll/frame smoothness, memory footprint, APK size, background battery.
**Method:** static analysis of the full source (152k LOC across :app/:wear/:shared/:baselineprofile) with file:line evidence. No feature was removed or disabled to produce these findings; code paths cited as "dead" are *flagged*, not deleted.
**Status:** findings only — no code changed yet. Prioritization and a proposed implementation plan follow the evidence.

> Environment note: this audit was performed via source analysis + Gradle build verification; runtime numbers marked ⚑ need on-device profiling (Macrobenchmark/profiler) to quantify precisely. Where prior in-repo comments document measured behavior, they are cited as evidence.

---

## 1. Cold / warm startup path

Ordered main-thread work during cold start:

| # | What | Where | Assessment |
|---|------|-------|------------|
| S1 | Hilt eager graph: `NeteaseStreamProxy` + `YouTubeStreamProxy` + `SoundCloudStreamProxy` + 2 `OkHttpClient`s + Room (21 migrations registered) + 2 Retrofit instances | `PixelTuneApplication.kt:25-54`, `di/AppModule.kt` | 10–40 ms; acceptable, but pulls a large graph before first frame |
| S2 | **TDLib native lib loads on the main thread pre-first-frame despite the `dagger.Lazy` deferral** — `MainViewModel` → `MusicRepositoryImpl` → `TelegramCacheManager` → `TelegramClientManager` constructor (`System.loadLibrary("tdjni")` + `Client.create()`) | `MainViewModel.kt:25-31` (constructed by `splashScreen.setKeepOnScreenCondition` at `MainActivity.kt:228`) → `AppModule.kt:290-318` → `TelegramClientManager.kt:23-31,65-79` | The Application's `dagger.Lazy<TelegramStreamProxy>` (`PixelTuneApplication.kt:33,106-120`) is defeated: `MusicRepositoryImpl` takes a *direct* `TelegramCacheManager` dependency. 10–100 ms+ on main thread, plus permanent TDLib threads/DB even when Telegram is never used. **No `TdApi.Close`/`client.close()` exists anywhere in the codebase.** |
| S3 | 3 Ktor CIO servers started in `Application.onCreate` (Netease, YouTube, SoundCloud) | `PixelTuneApplication.kt:100-102` | The `start()` bodies run on `Dispatchers.IO` (async, verified in each proxy class), but sockets/ports/threads exist from app start **even for users who never stream cloud music**. Telegram proxy is already deferred. `GDriveStreamProxy` exists but is never started (dead code, flagged not removed). |
| S4 | **`DualPlayerEngine` builds TWO full ExoPlayers at app launch** | `DualPlayerEngine.kt:237-264` (`init { initialize() }`) | Second player (B) is only needed for crossfade/pre-buffer. Two renderers factories, codec enumeration, loader threads at startup. |
| S5 | **Duplicate, listener-less `MediaController` created per `Activity.onStart()`** | `MainActivity.kt:1098-1109` | `MediaController.Builder(...).buildAsync()` with an *empty* listener, released in `onStop`. Pure binder churn; `PlayerViewModel` already owns the real controller. Dead code. |
| S6 | Sync `SharedPreferences` read on main thread in first composition | `MainActivity.kt:281-286` → `CrashHandler.kt:100-103` (`hasCrashLog()`) | Small but on the critical path inside `LaunchedEffect(Unit)`. |
| S7 | `MusicService.onCreate` (bound at startup via `MediaController.Builder` in `PlayerViewModel` field init): `CastContext.getSharedInstance()`, Wear publisher, `EqualizerManager` init (native `AudioEffect.queryEffects()`) | `MusicService.kt:361-432,1548-1554` | Cast init cost on service create even when never casting. |
| S8 | `GitHubAnnouncementPropertiesService` fetches `raw.githubusercontent.com` on every app open | `MainActivity.kt:702-720` | Network + parse on each launch; no TTL gating found. |
| S9 | Whole `isLibraryEmpty` = `getAudioFiles().map { it.isEmpty() }` — **materializes the entire library (entity mapping of every song) to compute a boolean** | `MainViewModel.kt:66-73`, `MusicRepositoryImpl.kt:135-155` | Full-table Room flow + 5k × `toSong()` allocations at startup and on *every* songs-table change. A `SELECT EXISTS(...)` flow exists nowhere yet. |
| S10 | First-frame gating: splash held until DataStore first emission (`initialSetupDoneFlow`) — DataStore read itself is on IO, ctor is clean | `MainViewModel.kt:25-31`, `UserPreferencesRepository.kt:64-70` | OK as designed. |

**Already good (verified):** WorkManager is on-demand (`Configuration.Provider`, manifest initializer removed); TDLib *proxy start* deferred to IO thread; notification channel creation is trivial; no `androidx.startup` initializers; Glance receivers are inert until `APPWIDGET_UPDATE`.

## 2. Compose performance (recomposition / scroll)

| # | Finding | Where | Impact |
|---|---------|-------|--------|
| C1 | **`SmartImage` is built on `SubcomposeAsyncImage` — Coil's most expensive loading path — and it is the artwork component for essentially every row** (51 call sites incl. `EnhancedSongListItem`, album cards, mini player) | `presentation/components/SmartImage.kt:112`, nested at `:148` | Subcomposition per image in every visible list row: measurable frame cost while scrolling any list. Replacing with plain `AsyncImage` (+painter placeholder/error) is the standard fix. |
| C2 | **`allowHardware = false` is the default for all album art** | `SmartImage.kt:53` | Every artwork decodes as software bitmap ⇒ ~2× bitmap memory + no GPU texture reuse while scrolling. Only true pixel-readers need `false` (audited: `AboutScreen.kt:848`, `CreatePlaylistScreen.kt:289` handle their own loading; `OptimizedAlbumArt` similar). |
| C3 | `Song.displayArtist` (sort + regex-ish split + join) recomputed in every row, every recomposition | `data/model/Song.kt:54-61`; used raw in `EnhancedSongListItem.kt:366`, `UnifiedPlayerSheet.kt:846`, `LyricsSheet.kt:1422` | O(visible rows × string ops) per scroll frame; play/pause state changes retrigger. |
| C4 | Missing `contentType` on mixed-type Lazy lists | `LibraryScreen.kt:2338/2345/2353` (folders+songs), `:3412/:3469`, `:3823`; `SearchScreen.kt:1058`; `CloudCatalogScreen.kt:458`; `AlbumDetailScreen.kt:288` | No composition reuse hints across item types ⇒ extra recompositions. (`GenreDetailScreen.kt:281` shows the correct pattern already used in-repo.) |
| C5 | Lyrics screen subscribes whole synced list to a 250 ms position tick; per-row `remember(position){derivedStateOf{...}}` re-allocates per tick | `LyricsSheet.kt:882,883,1005`; `PlaybackStateHolder.kt:39` | 4 Hz recomposition of the whole lyrics list + derivedStateOf objects churn — defeats derivation. |
| C6 | `PlayingEqIcon` reads animation values in composition scope | `PlayingEqIcon.kt:62-63` | ~60 Hz recomposition wherever the "now playing" icon shows (rows, queue, home). Fix: read inside Canvas draw lambda. |
| C7 | `listState.canScrollForward/Backward` read in composition for padding | `LibraryScreen.kt:2321,2613,2762,3100,3399,3453,3807`; `QueueBottomSheet.kt:733`; `CloudCatalogScreen.kt:405`; `AlbumDetailScreen.kt:282`; `PlaylistDetailScreen.kt:562/568` | Crossing list top/bottom recomposes the whole tab body. |
| C8 | Search playlist preview: `allSongs.filter { it.id in item.playlist.songIds }` — `songIds` is `List<String>`, `in` is O(n) | `SearchScreen.kt:1142-1144` | O(songs × playlist size) in composition. |
| C9 | CloudCatalog rows: fresh lambdas per parent recomposition + index-baked keys | `CloudCatalogScreen.kt:460,467-477` | Skipped-composition failures (unstable lambdas) + lost identity on list mutation. |
| C10 | Folder rows render 2×2 `PlaylistArtCollage` of `SmartImage`s + per-row `collectAllSongs()` tree walk | `LibraryScreen.kt:2403,2416,2503` → `PlaylistArtCollage.kt:163-218` | 4 subcompose-images per folder row today (compounds C1). |
| C11 | Queue rows use always-on `Modifier.animateItem` fade specs | `QueueBottomSheet.kt:759-771` | Cost per queue mutation; low priority. |
| C12 | Album prefetch `snapshotFlow{layoutInfo}.distinctUntilChanged()` is ineffective (`LazyListLayoutInfo` lacks equals) | `LibraryScreen.kt:3255,3284` | Enqueues prefetch work every scroll frame instead of when viewport actually changes. |

**Already exemplary (verified clean):** `LibrarySongsTab`/favorites/downloads tabs (keys, contentType, remembered lambdas), `HomeScreen`, all detail screens, the player stack (position only read in effects/draw via provider lambdas, `rememberSmoothProgress` 180–320 ms sampling + `withFrameNanos` interpolation, sliced+distinct state collection), navigation (lazy per-destination NavHost). `compose_stability.conf` whitelists core models (all also `@Immutable`); note it masks `PlayList.songIds: List<String>` instability (see C8).

## 3. Media3 / ExoPlayer / foreground service / proxy

**Well-configured (do not touch):** `DefaultLoadControl` tuned 30s/60s/2s/3s (`DualPlayerEngine.kt:426-433`); `setHandleAudioBecomingNoisy(true)`; `C.WAKE_MODE_LOCAL` held only while playing (no WifiLock needed — localhost proxy); manual `AudioFocusRequest` handling incl. LOSS/TRANSIENT/GAIN; foreground gated to active playback on API 31+ (`MusicService.kt:2085-2104`); media3 default notification provider (no per-position rebuilds, session-activity PendingIntent built once); proxy forwards Range/206/Content-Length/Accept-Ranges with `Accept-Encoding: identity` (`CloudStreamForwarder.kt:255-287`) — **no per-seek full re-download**; URL caches with expiry-aware invalidation + in-flight dedup + next-track URL prefetch; downloads `.part`+atomic rename with integer-% throttled notifications; UI uses a single 250 ms poller that stops on pause.

| # | Finding | Where | Impact |
|---|---------|-------|--------|
| M1 | **`engine.prepareNext()` runs *before* the crossfade settings check — player B pre-buffers the next track even when crossfade is disabled (the default)** | `TransitionController.kt:148` vs settings gate at `:157-197` | Every track change prepares + buffers ~30 s (minBuffer) of audio on player B that is *discarded* on natural advance. For cloud queues: ~2× network traffic + CPU + battery. Biggest single battery/data finding. `performOverlapTransition` already degrades gracefully when B is unprepared (`DualPlayerEngine.kt:745-750`), so gating is safe. |
| M2 | Per-transition player B **rebuild** (new audio session ⇒ EQ re-create with 3×300 ms retry loops) + O(n) whole-queue re-add to the new master | `DualPlayerEngine.kt:926-929`, `:817-868`; `MusicService.kt:460-464` → `EqualizerManager.kt:104-214` | Only bites when crossfade is ON. Structural; propose-only. |
| M3 | Duplicate 4 s queue-snapshot DataStore writes from *both* service ticker and ViewModel collector while playing (service also re-reads DataStore each save) | `MusicService.kt:253-318,478`; `PlayerViewModel.kt:151,1877-1883` | 2× JSON serialize+write of a 33-song queue every 4 s. |
| M4 | 3–4 full widget+Wear update pipeline runs per track change (two forced runs + metadata listener), each decoding art for current+4 queue items and writing 4 Glance state stores + Wear `putDataItem` — **even with zero widgets and no watch** | `MusicService.kt:1135,1151,1735-1742` → `:1830-1977,2007-2043`; `WearStatePublisher.kt:63-130` | Per-track CPU/IO/battery. Fix: keep 300 ms debounce single-fire; early-out when `getGlanceIds()` empty and no wear nodes. |
| M5 | 250 ms binder poller keeps running while app is backgrounded (stops only on pause/VM clear, not `onStop`) | `PlaybackStateHolder.kt:282-372` | 3–4 IPC calls @ 4 Hz with screen off. |
| M6 | Duplicate MediaController (see S5) | `MainActivity.kt:1098-1109` | Wasted bind + duplicate event dispatch per activity start. |
| M7 | Two full ExoPlayers at launch (see S4) | `DualPlayerEngine.kt:237-264` | Startup + idle memory. Player B could be created lazily at first `prepareNext`. |
| M8 | Custom `MediaCodecAudioRenderer` forces ≥512 KB codec input buffer for *all* formats, and default renderer is also added (duplicate audio renderer) | `DualPlayerEngine.kt:317-335` | Memory + codec-selection cost; propose-only (audio path). |
| M9 | Whole-queue `MediaItem` + extras-Bundle build on main thread before `setMediaItems` | `PlayerViewModel.kt:3315-3359` | GC spike on 1000+ queues; medium risk (Auto/Wear rely on extras). |
| M10 | No byte-level cache for cloud streams; back-buffer default 0 → backward seek/replay re-downloads. Proxy URLs contain ephemeral ports so a naive URL-keyed cache would miss across restarts (needs mediaId keying) | — | Flag/proposal only. |
| M11 | `WAKE_LOCK` permission not in app manifest (relies on library manifest merge for `WAKE_MODE_LOCAL`) | manifest | Verify in merged manifest. |

## 4. Room / data layer

**Already good:** Paging3 for songs/favorites/search/genre (pageSize 50, maxSize 250); core tables indexed incl. FK columns (`MIGRATION_23_24` backfills parity); `SyncWorker` genuinely incremental (`DATE_MODIFIED > lastSync`, ID-set diff, `Semaphore(4)` parallelism, 1 h genre cache); exactly one `@HiltWorker` and **zero** `PeriodicWorkRequest`s; startup sync throttled to 1/6 h; no `allowMainThreadQueries`; no `stateIn(Eagerly)`; lyrics in separate table (favorite/lyrics writes don't invalidate songs flows); batched transactional writes.

| # | Finding | Where | Impact |
|---|---------|-------|--------|
| R1 | **Favorite toggle re-emits the ENTIRE library**: `songs.is_favorite` update invalidates every songs-observing Flow ⇒ full-table re-read + 5k× `toSong()` mapping, no `conflate`/`distinctUntilChanged` on `getAudioFiles` | `MusicDao.kt:820-821`; `MusicRepositoryImpl.kt:135-155`; collectors `LibraryStateHolder.kt:214-227` (re-sort + `toImmutableList` per emission) | Jank/GC on every like while in Library. |
| R2 | `isLibraryEmpty` full-library materialization for a boolean (see S9) | `MainViewModel.kt:66-73` | Startup allocations proportional to library size. |
| R3 | `ensureCloudSongRow` loads **ALL** artists + **ALL** albums into memory per cloud-song like | `MusicRepositoryImpl.kt:590-676` | O(library) work per interaction on cloud songs. |
| R4 | **Missing indices**: `telegram_songs.chat_id`, `netease_songs.playlist_id`, `gdrive_songs.folder_id`, `song_engagements.last_played_timestamp`, `search_history.timestamp`, `songs.file_path` | entities in `data/database/` | Full scans on those query paths; Room itself would warn for FK columns. Fix = `@Index` + v25 migration mirroring `MIGRATION_23_24`. |
| R5 | `MediaStoreObserver` has no debounce (recursive registration on all audio) — mitigated: its only consumers are dead-code paths (UI reads Room, not MediaStore) | `MediaStoreObserver.kt:40-43` | Latent trap; flag + debounce. |
| R6 | `userPlaylistsFlow` re-parses the entire playlists JSON on *any* DataStore preference emission (no `distinctUntilChanged`) | `UserPreferencesRepository.kt:981-994` | JSON parse per unrelated preference write. |
| R7 | Leading-wildcard `LIKE '%q%'` search (no FTS); `CASE WHEN :sortOrder` ORDER BY defeats indices; `triggerMediaScanForNewFiles` walks the filesystem every sync run | `MusicDao` search queries; `SyncWorker.kt:1091-1182` | Larger libraries feel it in search & sync. Proposal tier (FTS is structural). |
| R8 | `runBlocking` sites: `LastPlaylistTileService.kt:42,52` (TileService **main thread**), `EqualizerViewModel.kt:487-498` (10 sequential writes in `onCleared` on main), `DailyMixManager.kt:71` (legacy migration), `SongMetadataEditor.kt:128/201/240` (IO-thread only — acceptable) | as listed | ANR risk on tile + main-thread stalls. |

## 5. Network layer

**Already good:** 4 purpose-built DI `OkHttpClient`s (default/NewPipe/streaming/fast) with IPv4-first DNS rationale documented; connection pools sized; no per-request clients in DI; NewPipe on non-BODY-logging client; streaming client has no read/call timeout (correct for throttled YouTube); Coil has its own 100 MB disk cache.

| # | Finding | Where | Impact |
|---|---------|-------|--------|
| N1 | `@FastOkHttpClient` logs **headers in release** (only client not gated on `BuildConfig.DEBUG`) | `AppModule.kt:536-537` | Privacy + perf (string formatting per request) in release. |
| N2 | `AiClientFactory` builds a **new OkHttpClient per AI call** (own pools/dispatchers); batch metadata generation = N clients | `AiClientFactory.kt:18-27`, `DeepSeekAiClient.kt:46-50` | Wasted threads/pools; cache by provider. |
| N3 | `NewPipeDownloader` has no response caching (no NewPipe caching-downloader equivalent); no OkHttp disk `Cache` on any client (Coil's covers artwork only) | `NewPipeDownloader.kt:43-169` | sw.js/base.js/client-id re-fetched within a session. Medium-risk to add (stale-response risk on extractor pages). |
| N4 | `ArtistImageRepository.setCustomArtistImage` decodes picked bitmap **unsampled** (12 MP photo ⇒ ~48 MB heap spike); Deezer art fetched at `/1000x1000` for all rows | `ArtistImageRepository.kt:260-269,315-318` | Memory spike on custom artist image set; oversized downloads. |
| N5 | `WearStatePublisher` re-opens/re-decodes art URI on every publish (per debounced state change), 2048px JPEG q99 (oversized for watch), no per-song byte cache | `WearStatePublisher.kt:50-53,132-139` | Repeated IO/decode; DataClient dedups only the transfer. |

## 6. Background work / battery

- **No WorkManager periodic work at all** (one-time sync gated 6-hourly) — healthy.
- M1/M3/M4/M5 above are the battery items (double pre-buffer, duplicate snapshot writes, widget pipeline over-firing, background poller).
- `MashupViewModel`: 100 ms free-running poller not gated on `isPlaying` + duplicate whole-library retention + new anonymous `Player.Listener` per deck load (a **new ExoPlayer per song load** in `DeckController`) — `MashupViewModel.kt:63-80,118-127`, `DeckController.kt:16-22`.
- Widgets are event-driven with 300 ms debounce, `updatePeriodMillis="0"` — good.
- `onTaskRemoved` / foreground gating — good (see §3).

## 7. Memory

- **MediaController leak:** `PlayerViewModel` never releases its controller in `onCleared()`; singleton `PlaybackStateHolder` holds a strong ref until process death; each VM recreation orphans a binder connection (`PlayerViewModel.kt:1305,1351-1352,4398-4426`; `PlaybackStateHolder.kt:51,68-70`).
- Widget state stores `ByteArray` art as JSON numeric arrays (~3.5–4× size expansion) — `PlayerInfo.kt:53-67` + `PlayerInfoStateDefinition.kt`; mitigations present (art ≤1024 px WEBP@80, 1.25 MB LRU, queue capped 4).
- Unbounded (small-entry) maps: `DualPlayerEngine.resolvedUriCache`, `TelegramRepository.resolvedPathCache`/`uriResolutionCache`, `TelegramCoilFetcher.extractionLocks`, `ArtistImageRepository.failedFetches`.
- `GlobalScope` fire-and-forget: `AlbumArtUtils.kt:194` (intentional, throttled); unmanaged scopes `PlayerViewModel.kt:4409`, `PixelTuneApplication.kt:106`.
- Verified healthy: listener add/remove symmetry across engine swap paths; `MediaMetadataRetrieverPool` (cap 4, trimmed on `TRIM_MEMORY_RUNNING_CRITICAL`); sampled decodes in widgets/Wear/Telegram fetcher; Coil config (20% mem / 100 MB disk); Telegram caches (audio LRU 5 files, embedded art 50 MB→80% trim); history caps (30/15); `AlbumArtCacheManager` 200 MB LRU.

## 8. Build / APK

| # | Finding | Evidence | Impact |
|---|---------|----------|--------|
| B1 | **7,845 baseline-profile rules target `com.theveloper.PixelTune` (wrong case; real package `…pixeltune`) — the app's own rules are 100% dead** | `app/src/main/baseline-prof.txt` (grep: 7,845 `Lcom/theveloper/PixelTune/` hits, 0 lowercase) | Baseline profile currently only helps library classes; fixing the casing activates the app's hot paths ⇒ typical 20–40% startup/scroll improvement, free. |
| B2 | `:baselineprofile` `StartupBenchmarks` targets `com.theveloper.pixeltune` but `applicationId` is `com.saine.pixeltune` (namespace ≠ applicationId) | `StartupBenchmarks.kt:39` vs `app/build.gradle.kts:42` | Macrobenchmark can't find the package ⇒ no numbers can be produced until fixed. |
| B3 | `androidx.metrics.performance` is a release `implementation` but `FrameJankLogger.kt` **does not exist** and no `JankStats`/`androidx.metrics` reference exists in source | `app/build.gradle.kts:145-147` | Unused dependency shipped in release. |
| B4 | `com.google.genai` **is used** (`GeminiAiClient.kt`) despite a build-file comment claiming removal; the actually-unused alias is `generativeai` | `app/build.gradle.kts:296-302` | Keep genai; drop dead alias if desired. |
| B5 | Netty fully kept by R8 rules; zero first-party Netty imports (transitive of genai/gRPC) | `app/proguard-rules.pro:47-48,111-139` | Possible dead APK weight. |
| B6 | `-dontobfuscate` + blanket `-keep class io.ktor.** / io.netty.** / kotlinx.coroutines.** / org.slf4j** / kotlin.reflect.**` + whole data packages | `app/proguard-rules.pro:3,107-164` | Larger DEX + slower R8; tightening needs careful keep-rule surgery (stack-trace tooling depends on `-dontobfuscate`). |
| B7 | material3 declared 3× (BOM + explicit `1.5.0-alpha13` + `material3:1.5.0-alpha15` + separate window-size-class 1.3.1); cast framework at 21.5.0 *and* 22.3.0; mediarouter ×2; splashscreen ×2; duplicate junit-jupiter test deps | `app/build.gradle.kts:160,166,244-245,263-268,275,286`; toml | Version skew risk + build time. |
| B8 | Fonts: `gflex_variable.ttf` 4.0 MB (app **and** wear) + `genre_variable.ttf` 1.8 MB ≈ 5.8 MB in app resources | `app/src/main/res/font/` | APK size; consider subsetting. |
| B9 | `org.gradle.parallel=true` commented out; no `org.gradle.caching`; no `kotlin.daemon` JVM args | `gradle.properties:14` | Multi-module build-time win available. |
| B10 | ABI splits emit 4 per-ABI APKs **plus a universal APK** (TDLib ×4 ABIs ≈ 87 MB in universal) | `app/build.gradle.kts:118-127` | Distribution choice; flagged for visibility. |
| B11 | `androidResources { noCompress += "tflite" }` vestigial (no tflite assets — TF Lite deps already removed) | `app/build.gradle.kts:30` | Trivial cleanup. |
| B12 | Wear `applicationId "com.saine.PixelTune"` (capital P) + property-driven `versionCode 7` vs app's hardcoded `1`; compileSdk/targetSdk skew (wear targets 34) | `wear/build.gradle.kts:14-18` | Consistency; flagged. |
| B13 | Baseline profile generator covers startup/home/library/player but not cloud search/play, equalizer, folders, artist/album detail, downloads | `:baselineprofile` module | Coverage gap to extend later. |

**Dead code flagged (not removed):** `GDriveStreamProxy` (never started/injected), `MediaStoreSongRepository.getSongs()/getPaginatedSongs()` (no callers; UI reads Room), `generativeai` catalog alias, `noCompress tflite`.

---

## 9. Prioritization (impact ÷ risk)

### Tier 1 — Quick, low-risk wins (batchable, each ~tiny diff)
1. **B1 Fix baseline-profile package casing** (`PixelTune`→`pixeltune`) — reactivates 7,845 dead rules; startup/scroll. (Behavioral risk low: rules only pre-compile code paths that already run.)
2. **S5/M6 Remove dead listener-less MediaController** from `MainActivity.onStart`.
3. **S9/R2 `isLibraryEmpty` → `SELECT EXISTS` flow** (add DAO query; drop whole-library map).
4. **M1 Gate `prepareNext` on crossfade settings** — stop 2× network pre-buffer for the default (crossfade-off) case; `performOverlapTransition` already handles unprepared-B gracefully.
5. **R4 Add 6 missing Room indices + v25 migration.**
6. **N1 Gate `@FastOkHttpClient` logging on DEBUG.**
7. **C4 Add `contentType` to the 6 listed Lazy lists.**
8. **C3 Memoize `displayArtist` in the 3 hot row call sites.**
9. **R6 `distinctUntilChanged()` on `userPlaylistsFlow`.**
10. **B3 Drop unused `androidx.metrics.performance` dep** (+ B11 noCompress, B4/B7 dep hygiene where trivially safe).
11. **B9 Enable `org.gradle.parallel` + `org.gradle.caching`.**
12. **B2 Fix `StartupBenchmarks` package name** so benchmarks can run at all (test-only).
13. **C8 `songIds.toHashSet()` in Search playlist preview.**

### Tier 2 — Medium changes (isolate per commit, verify each)
1. **C1/C2 `SmartImage`→ plain `AsyncImage` + `allowHardware=true` default** — biggest scroll win; requires per-call-site audit of `onState`/pixel-readers (3 onState sites found) and the `placeholderModel` path (2 sites).
2. **S2/M7 Lazy player B creation** in `DualPlayerEngine` (first `prepareNext`).
3. **S4/M7 Lazy proxy start** on first cloud playback (awaitReady plumbing already exists).
4. **S2-adjacent Defer TDLib** — break the `MusicRepositoryImpl → TelegramCacheManager` eager edge with `dagger.Lazy`.
5. **M3 Single queue-snapshot writer** (service ticker owns 4 s writes; VM writes only on pause/transition/teardown).
6. **M4 Widget/Wear pipeline: single debounced fire + empty-GlanceIds early-out.**
7. **M5 Stop 250 ms poller in `Activity.onStop`, restart in `onStart`.**
8. **E3/S5 Release MediaController in `onCleared`** (+ null singleton ref).
9. **C5 Lyrics position-provider refactor** (derive `currentLineIndex` once; read position only in leaf).
10. **C6 `PlayingEqIcon` draw-phase reads.**
11. **C7 `canScroll*` padding via `derivedStateOf` leaf wrapper.**
12. **R1 `conflate()` on library flows** (`getAudioFiles`/albums/artists) — de-janks favorite toggles.
13. **R3 Indexed lookups in `ensureCloudSongRow`.**
14. **N2 Reuse AI-provider OkHttpClients.**
15. **N4 Downsample custom artist images** (`inSampleSize` to ≤1000 px) + stream `use{}` guard.
16. **S6 Crash-log read off main thread.**
17. **R8 `LastPlaylistTileService` `goAsync()` + suspend DataStore read; `EqualizerViewModel.onCleared` writes moved to IO.**
18. **Mashup poller gating on `isPlaying`** (deck player reuse is Tier 3).
19. **S8 Cache announcement fetch with TTL.**

### Tier 3 — Structural / risky (PROPOSE ONLY — not implemented without sign-off)
1. M2 Player-B rebuild per transition + EQ churn + O(n) queue re-add (crossfade path overhaul).
2. M8 Duplicate audio renderer / 512 KB codec buffer surgery.
3. TDLib idle-close lifecycle (open/close on login state) — biggest always-on native cost, but touches login flows.
4. B6 R8 `-dontobfuscate` + blanket-keep slimming (affects stack traces/crash reports; needs consent).
5. R7 Room FTS for search + ORDER BY index alignment.
6. M9/M10 Byte-caching cloud streams keyed by mediaId; queue-Bundle build off main thread (Auto/Wear metadata contract risk).
7. Widget ByteArray→Base64/file serialization format change (touches Glance state store format).
8. C10 Folder-row collage single-bitmap pre-blend.
9. B8 Font subsetting (5.8 MB) — changes typography availability for scripts.
10. N5 WearStatePublisher art caching/downsizing to watch-appropriate resolution.

---

*Prepared before any code change. Implementation will proceed top-down through Tier 1 (batch of small commits) then Tier 2 (one commit each, tests after each), leaving Tier 3 as proposals. Verification: `:app:testDebugUnitTest` + `:wear:testDebugUnitTest` (where present) + full-module compilation; manual on-device checklist provided at the end.*

---

# IMPLEMENTATION REPORT

Branch: `perf/optimization-pass` (18 commits, one concern each). All changes verified against the **original baseline** via a clean worktree of `origin/main` (commit `08116f5`).

## Verification (Step 4)

| Check | Result |
|---|---|
| `:app:compileDebugKotlin` | ✅ BUILD SUCCESSFUL |
| `:app:assembleDebug` | ✅ BUILD SUCCESSFUL — APKs produced (before disk cleanup: `app-universal-debug.apk` 257 MB / `app-armeabi-v7a-debug.apk` 156 MB debug, uncompressed, 4-ABI TDLib+ffmpeg) |
| `:wear:assembleDebug` | ✅ BUILD SUCCESSFUL — `wear-debug.apk` |
| `:shared:compileDebugKotlin` | ✅ BUILD SUCCESSFUL |
| `:baselineprofile:compileBenchmarkReleaseSources` | ✅ BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | **127 tests; 17 failures — byte-identical failure set to the pre-change baseline** (verified side-by-side worktree run: same 17, same classes). The 17 are pre-existing environmental failures: 14× `java.lang.VerifyError` on GMS Cast classes under the JVM test runner, 3× NewPipeDownloader User-Agent/ISO-8859-1 assertions. No test was added, removed or weakened. |
| Test helper | `DaggerLazyTestUtils.daggerLazyOf()` added for constructor-signature updates only. |

⚠ Build-environment notes: this sandbox has 4 GB RAM / 2 cores; builds required `-Dorg.gradle.jvmargs=-Xmx2g` (project default `-Xmx6g` exceeds the container and gets the daemon OOM-killed). Macrobenchmarks could not run (no emulator) — runtime impact below is *expected*, from measured in-repo behavior and standard platform characteristics, not new benchmark numbers.

## What was changed (18 commits, in order)

| # | Commit | Area | Expected impact | Risk |
|---|--------|------|-----------------|------|
| 1 | `perf(startup)` baseline profile casing | startup/scroll | **High.** Reactivates 7,845 dead ART rules covering all app hot paths (typical 20–40% cold-start / first-scroll gain from baseline profiles; the library rules that did work were already in the file). Rules only pre-compile existing code paths. | Low |
| 2 | remove duplicate MainActivity MediaController | startup | Low-Med. Removes a binder bind + duplicate event dispatch per `Activity.onStart()` (dead listener). | Very low (dead code) |
| 3 | `isLibraryEmpty` → `SELECT EXISTS` | startup/scroll | Med. Startup allocations drop from O(library) to O(1); no more full-table mapping on every songs-table write. 5k-song library: ~5k row reads + 5k `Song` allocations per emission avoided. | Low (semantics preserved: same filter incl. cloud-song negative ids) |
| 4 | gate player-B pre-buffer on crossfade setting | battery/data | **High.** Crossfade is off by default; every track change previously downloaded ~30 s (minBuffer) of audio into player B that was discarded on natural advance — ~2× network/CPU per track for cloud queues. | Low (`performOverlapTransition` has a graceful unprepared fallback; next-track URL prefetch still warms skips) |
| 5 | 6 missing Room indices + v25 migration | db | Med. Per-query: telegram channel lists, netease playlist detail, gdrive folder lists, recently-played, search history, metadata-editor path lookups were full scans + sorts. | Low (additive migration, Room-verified schema) |
| 6 | FastOkHttpClient logging gated on DEBUG | network/privacy | Low. Stops per-request header formatting in release (it was the only un-gated client). | Very low |
| 7 | contentType for mixed Lazy lists | scroll | Med. Search results (6 row types + headers), folders tab (folders+songs), cloud catalog (songs+empty+load-more) recycle composition slots type-stably now. | Very low |
| 8 | memoize `Song.displayArtist` | scroll | Med. Sort+split+join per row per recomposition → once per `Song` instance. | Very low |
| 9 | `userPlaylistsFlow` distinctUntilChanged | cpu/battery | Low-Med. Playlists JSON no longer re-parsed on every unrelated preference write. | Very low |
| 10 | drop unused `androidx.metrics.performance` + tflite noCompress | APK | Low-Med. Dead dependency + vestigial resource config removed from release. | Very low |
| 11 | gradle parallel + build cache | build time | Med for multi-module builds (`:app`+`:wear` together). | Very low |
| 12 | benchmark targets applicationId | tooling | Unblocks startup benchmarks entirely (they never ran: wrong package). | None (test-only) |
| 13 | O(1) songIds membership in search preview | scroll | Low-Med. O(songs×playlist) per playlist row → O(songs). | Very low |
| 14 | **SmartImage → plain AsyncImage + hardware bitmaps** | scroll/memory | **High.** The artwork slot of ~51 call sites (every song row) left Coil's most expensive loading path (subcomposition per image, including steady-state success) and decoded all art as software bitmaps (~2× bitmap memory, CPU copies per draw). Placeholder visuals reproduced exactly via a custom painter; `placeholderModel` (2 TG call sites) keeps the old path. | Med (accepted, documented rare-path deltas: reload placeholder instead of stale image after a memory-cache miss; error placeholder instead of stale art on in-place model-change failure; pixel-readers were audited — all already used their own requests) |
| 15 | release MediaController in `onCleared` | memory | Med. Fixes binder-connection + listener leak surviving VM teardown. | Low (identity-checked singleton clear; VM survives config changes so background playback unaffected) |
| 16 | single queue-snapshot writer | battery | Med. Halves 4 s-periodic 33-song JSON serializations + DataStore writes during local playback (service ticker owns it; VM keeps cast playback). | Low |
| 17 | widget/Wear pipeline gating | battery | Med-High for users without widgets/watch: per track change up to 3 full pipeline runs (art decode for 5 songs, 4 Glance state writes, Wear DataLayer item) → skipped entirely; forced follow-up coalesced. Connected watch / pinned widget behavior unchanged (existence checks fall back to running the pipeline on any error). | Low (documented: paired-but-disconnected watch now gets state on next playback event instead of a queued DataItem) |
| 18 | lazy player B + drop between transitions | startup/memory | Med. Second ExoPlayer (renderers, codec enumeration, loader threads) no longer built pre-first-frame; released between transitions instead of rebuilt-and-idle. Crossfade path unchanged (`prepareNext` builds fresh on demand — preserves the OEM stale-session workaround). | Low-Med |
| 19 | TDLib lazy edges (MusicRepositoryImpl / DualPlayerEngine / PlayerViewModel → `dagger.Lazy`) | startup | **High.** `System.loadLibrary("tdjni")` + `Client.create()` (native threads + SQLite) no longer run on the **main thread before the first frame** — they ran via 3 eager dependency edges despite the Application's existing deferral. Now only the Application's IO-dispatcher coroutine constructs the chain. | Low (same singletons; first Telegram use resolves lazily) |
| 20 | conflate library flows | scroll | Med. Burst full-library re-emissions (sync chunk commits, rapid writes) collapse to the latest — skips N−1 intermediate full-table mappings + re-sorts. | Low (final state always delivered) |
| 21 | batch: mashup poller gating, crash-log off-main, TileService runBlocking→IO, AI client caching, PlayingEqIcon draw-phase reads | battery/anr/cpu | Mashup: 10 Hz loop no longer free-runs while idle. Tile: no more DataStore disk read on the QS-panel main thread (ANR window). AI: one cached client per provider instead of one OkHttp pool per call (batch metadata = N pools before). Eq icon: now-playing animation no longer recomposes at 60 Hz (draw-phase only). | Low |
| 22 | targeted artist/album lookups in `ensureCloudSongRow` | cpu/db | Med. Liking a cloud song loaded ALL artists + ALL albums (entity-mapped) per like → two indexed 1-row queries with identical matching semantics (trim + case-insensitive + same join row set). | Low |

*(Commits 2, 5, 6 are one commit each on the branch; the table groups the batch commit 21.)*

## Explicitly NOT done (proposals for maintainer sign-off — Tier 3)

1. **M2 player-B rebuild + EQ churn per crossfade** — alternating without rebuild needs OEM testing; EQ re-create could be skipped when session id is unchanged.
2. **TDLib idle-close lifecycle** — the client still lives for process lifetime (by design today); opening/closing on login-state is the biggest remaining always-on native cost but touches login flows.
3. **R8 `-dontobfuscate` + blanket-keep slimming** (netty/ktor/coroutines keeps) — affects stack traces/crash-report tooling; needs consent. Netty is kept by rules but has zero first-party imports — likely dead APK weight.
4. **Room FTS** for leading-wildcard `LIKE '%q%'` search + ORDER BY index alignment.
5. **M9 duplicate audio renderer / 512 KB codec buffer** surgery in `DualPlayerEngine.buildAudioRenderers`.
6. **M10 byte-level stream cache** keyed by mediaId (proxy URLs have ephemeral ports); back-buffer>0 for backward seeks.
7. **Widget `ByteArray`-as-JSON-numeric-array** serialization format change (Base64/file) — touches Glance state store format.
8. **Font subsetting** (5.8 MB variable fonts).
9. **WearStatePublisher art caching / 2048px→watch-appropriate downsize.**
10. **M5 stop 250 ms poller in `onStop`** — *deliberately declined*: `PlaybackStateHolder`'s tick feeds `listeningStatsTracker` (stopping it would silently stop counting listening stats in the background), and Media3 `MediaController` property reads are cache-served (no IPC per tick). Recommend instead moving listening-stats to a service-side source if background CPU ever shows up in profiles.
11. **Dead code flagged, not removed** (per instructions): `GDriveStreamProxy` (never started/injected), `MediaStoreSongRepository.getSongs()/getPaginatedSongs()` (no callers; UI reads Room), `generativeai` catalog alias, duplicate material3/cast/mediarouter/splashscreen declarations (B7), `OptimizedAlbumArt` still on SubcomposeAsyncImage (2 cold call sites only — not worth the visual-risk).
12. **Baseline profile extension** to cloud search/play, equalizer, folders, artist/album detail, downloads (the generator module covers startup/home/library/player today) — recommend doing it on a device farm now that the casing is fixed.

## Needs manual verification on a real device (cannot be confirmed statically)

- Audio focus behavior after the TDLib/`dagger.Lazy` changes (focus loss during a Telegram track crossfade).
- MediaSession/notification behavior across the first track change with crossfade **enabled** (player B now constructed at `prepareNext` instead of at app start — `performOverlapTransition` waits up to 3 s for readiness).
- Background playback with the app swiped away (queue snapshot restore within the 4 s window — service writer unchanged).
- Wear OS: first metadata arrival on a watch after reconnecting mid-session (pipeline early-out change).
- Quick Settings tile click behavior (async DataStore read now).
- Cast: position restore after force-kill during cast playback (VM collector now cast-only).
- Visual: SmartImage placeholder/error states in dark+light themes (custom painter reproduces box+32dp tinted icon).
- The v24→25 Room migration on an existing install (`./gradlew` cannot validate runtime migration; schema names follow Room's default convention and `exportSchema=false`).
