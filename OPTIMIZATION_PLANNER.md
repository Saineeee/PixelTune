# PixelTune Optimization Planner

> **Purpose:** This is a self-contained, phased implementation plan to bring PixelTune's performance up to (and beyond) the level of **PixelPlayerOSS** — a highly-optimized sibling codebase sharing the same lineage (both descend from the same original PixelPlayer project, with near-identical file names and architecture).
> **Reference repo:** https://github.com/PixelPlayerHQ/PixelPlayerOSS (`com.lostf1sh.pixelplayeross`, default branch `main`)
> **Target repo:** https://github.com/Saineeee/PixelTune (`com.theveloper.pixeltune`, default branch `fix/library-downloads-sort-playlists-filter-button-provider-ux`)
> **Produced:** 2026-09-20, via deep static analysis of both codebases (file:line evidence throughout) + reconciliation with PixelTune's own `PERFORMANCE_FINDINGS.md` audit and its unmerged `perf/*` branches.
> **Status of this document:** planning only — **no code has been changed** by the author of this planner.

---

## 0. How to Use This Planner (READ FIRST)

You are likely an AI coding session or an engineer picking this up cold. Follow these rules:

1. **Verify before you change.** The codebase moves fast (172+ commits since the analysis was made). For every task below, re-confirm the cited file:line still exhibits the problem before fixing it. If it's already fixed, skip and mark the task `[DONE IN CODE]`.
2. **One concern per commit.** Commit message convention: `perf(<area>): <what>` (matches the repo's existing convention on `perf/*` branches).
3. **Compile + test after every commit:**
   ```bash
   ./gradlew :app:compileDebugKotlin
   ./gradlew :app:testDebugUnitTest        # 127 tests; 17 PRE-EXISTING failures are expected (see §2.4)
   ./gradlew :app:assembleDebug
   ./gradlew :wear:assembleDebug           # when touching :wear / :shared
   ```
   On memory-constrained machines the project default `-Xmx6g` daemon gets OOM-killed — use `./gradlew ... -Dorg.gradle.jvmargs=-Xmx2g`.
4. **Never weaken tests.** The 17 pre-existing failures are environmental (14× GMS Cast `VerifyError` under the JVM runner, 3× NewPipeDownloader User-Agent/ISO-8859-1 assertions). If your change adds failures beyond that byte-identical set, revert.
5. **Respect the "do not touch" list in §8.** Those paths were audited as already-optimal; careless edits there cause regressions.
6. **Expected total effort:** Phases 0–2 ≈ 2–4 days each; Phases 3–5 ≈ 3–7 days each; Phases 6–7 are background/structural. Work strictly top-down by priority within each phase.
7. **After each phase**, append a short implementation report to this file's tracking table (§10) — commit style mirrors `PERFORMANCE_FINDINGS.md`'s report section.

---

## 1. Executive Summary

PixelTune is a feature-rich fork (Wear OS, YouTube/SoundCloud/Telegram/Netease cloud streaming, AI metadata) built on the same core architecture as PixelPlayerOSS. A previous optimization pass (`perf/optimization-pass-v3`, merged into HEAD) already landed ~22 high-value fixes. What remains, verified against the current HEAD and the PixelPlayerOSS reference:

| Gap class | Count of open items | Expected benefit if closed |
|---|---|---|
| Un-merged in-repo perf branches (proven code sitting on side branches) | 21 commits across 2 branches | Scroll smoothness, startup, battery — near-free to adopt |
| Compose recomposition waste (raw `playerUiState` collectors, `canScroll*` reads, 4 Hz lyrics, theme crossfade spring, per-item animations) | ~15 items | Eliminates most frame drops during playback + scroll on mid/low-end devices |
| Playback engine gaps vs OSS (offload policy, adaptive buffers, snapshot cache, windowed crossfade queue, wake modes) | ~8 items | Battery life (offload + WAKE_MODE_NONE), big-queue O(n) protection, faster restore |
| Data layer (FTS search, sync-plan classification, projection discipline, unbounded caches) | ~6 items | Search latency, sync CPU, memory safety on 20k+ libraries |
| Memory (trim ladder, library release/restore, unbounded caches) | ~5 items | Prevents LMK kills on 3–4 GB devices |
| Build/deps (Kotlin 2.1.20 + BOM bump, dep dedupe, font dedupe, R8 log stripping, baseline-profile generation wiring) | ~8 items | 20–40% startup/first-scroll from working baseline profiles; smaller APK; faster builds |
| PixelTune-specific (Wear art pipeline, Telegram backfill chunking, TDLib lifecycle, cloud byte-cache) | ~7 items | Battery/data on cloud users; no OOM on big Telegram channels |

**Strategy:** Phase 0 harvests the unmerged branches (cheapest wins, code already written & partially reviewed). Phases 1–2 finish the Compose work those branches started. Phases 3–5 port the *architectural* patterns that make PixelPlayerOSS fast (offload policy, snapshot cache, trim ladder, FTS). Phases 6–7 are maintenance-grade modernization. Phase 8 builds the measurement harness so every later claim is provable.

---

## 2. Repository Context — Critical Facts

### 2.1 Identity
| Property | Value |
|---|---|
| Namespace (Kotlin/manifest) | `com.theveloper.pixeltune` |
| applicationId (app) | `com.saine.pixeltune` ← **differs from namespace**; benchmark/profile code must target this |
| applicationId (wear) | `com.saine.PixelTune` (capital P — known inconsistency, B12) |
| Modules | `:app` (~142k LOC, 451 Kotlin files), `:wear` (~7.2k LOC), `:shared`, `:baselineprofile` |
| Room DB | Version **25**, 22 migrations (3→25), `exportSchema=false` (no schema JSONs committed) |
| Default branch | `fix/library-downloads-sort-playlists-filter-button-provider-ux` |
| Baseline profile file | `app/src/main/baseline-prof.txt` (46,230 lines, casing `pixeltune` already correct) |

### 2.2 Current toolchain vs PixelPlayerOSS
| Dependency | PixelTune (HEAD) | PixelPlayerOSS | Note |
|---|---|---|---|
| AGP | 8.13.2 | 9.3.1 | OSS on AGP 9 with `android.builtInKotlin`; big jump — don't rush |
| Kotlin | 2.1.0 | 2.4.10 | Bump to **2.1.20** first (proven on unmerged branch `de4c863`) |
| Compose BOM | 2025.05.00 | 2026.06.01 | Same commit `de4c863` bumps to 2026.06.01 |
| media3 | 1.9.2 | 1.10.1 | Contains offload/HAL fixes relevant to Phase 3 |
| Room | 2.7.1 | 2.8.4 | Multimap queries etc. |
| kotlinx-collections-immutable | **0.3.7** | 0.5.1 | Cheap bump; needed for `persistentListOf` defaults |
| OkHttp | 5.3.2 | 5.4.0 | — |
| coroutines | 1.8.0 | 1.11.0 | — |
| Gradle daemon | `-Xmx6g -XX:+UseParallelGC` | `-Xmx4096m G1GC + MaxGCPauseMillis=200` | OSS's G1GC config gives faster builds (no long GC pauses) |

### 2.3 Gradle state (already good — do not redo)
`org.gradle.parallel`, `org.gradle.caching`, `org.gradle.configuration-cache` are all **enabled** in `gradle.properties` (the B9 finding was fixed in HEAD).

### 2.4 Test baseline
`:app:testDebugUnitTest` → 127 tests, **17 pre-existing failures** (identical set before/after any change — treat as the floor, not a regression). Compose compiler reports/metrics are currently emitted on **every** build (`app/build.gradle.kts:94-101`) — move behind a property like OSS does (`pixelplayer.enableComposeCompilerReports`), see Task B6.

### 2.5 Known environment constraints
- Builds in ≤4 GB RAM sandboxes need `-Dorg.gradle.jvmargs=-Xmx2g`.
- Macrobenchmarks need an emulator/device — `StartupBenchmarks.kt:39` currently targets the wrong package (`com.theveloper.pixeltune` instead of `com.saine.pixeltune`) so it has **never run**; fix first (Task B2).

---

## 3. What Is ALREADY DONE in HEAD (verified — do NOT redo)

These were verified present in the current default branch by direct code inspection. Re-doing any of them wastes sessions and risks regressions:

| Fix | Evidence in HEAD |
|---|---|
| `SmartImage` main path → plain `AsyncImage` + hardware bitmaps (51 call sites) | `SmartImage.kt:178` (SubcomposeAsyncImage retained only for `placeholderModel` paths at `:213/:249`) |
| `isLibraryEmpty` → `SELECT EXISTS` (O(1) startup check) | `MusicDao.kt:849` |
| Baseline profile casing fixed (`pixeltune` lowercase) | `app/src/main/baseline-prof.txt` — 7,845 lowercase rules, 0 uppercase |
| Room v25 migration with all 6 missing indices (file_path, telegram chat_id, netease playlist_id, gdrive folder_id, last_played_timestamp, search_history.timestamp) | `MIGRATION_24_25` at `PixelTuneDatabase.kt:463-474` |
| Lazy player-B creation + drop between transitions | `DualPlayerEngine.kt:121-138` |
| TDLib (`tdjni`) off the main thread (dagger.Lazy edges) | `PixelTuneApplication.kt:106-120`, `DualPlayerEngine.kt:64-98` |
| `conflate()` on library flows (favorite-toggle de-jank) | `MusicRepositoryImpl.kt:176` |
| MediaController released in `onCleared()` | `PlayerViewModel.kt:4455-4473` |
| Single queue-snapshot writer (4 s, service owns) | `MusicService.kt:246-252` |
| Widget/Wear pipeline gating (early-out when no pinned widgets / no watch) | `MusicService.kt:1838-1867` |
| Crossfade `prepareNext` gated on crossfade-enabled setting | `TransitionController.kt:198-206` |
| Mashup 100 ms poller gated on deck activity | `MashupViewModel.kt:119-130` |
| Crash-log read off main thread | `MainActivity.kt:277-290` |
| TileService `runBlocking` → async DataStore | `LastPlaylistTileService.kt:38` (comment) |
| AI client caching (one OkHttp client per provider) | `AiClientFactory` (commit 5500f81 lineage) |
| `PlayingEqIcon` reads animation in draw phase | `PlayingEqIcon.kt` |
| Position ticker separated from `PlayerUiState` (48-field aggregate; `currentPosition` written only on pause/restore) | `PlaybackStateHolder.kt:39,57-58`, `PlayerViewModel.kt:2813+` |
| Lyrics stored in a separate table (songs flows not invalidated by lyric writes) | Room schema, `PERFORMANCE_FINDINGS.md` §4 |
| LibraryScreen main scope uses projection slice | `LibraryScreen.kt:670-673` (`toLibraryScreenProjection() + distinctUntilChanged`) |
| `UnifiedPlayerSheetV2` uses `PlayerUiSheetSliceV2` + position-free collection | `UnifiedPlayerSheetV2.kt:139-164` |

---

## 4. The Reference: PixelPlayerOSS Optimization Patterns (what to copy)

Every pattern below is **verified present** in the OSS repo with file:line. These are the "inspiration" sources for the plan tasks in §6.

### 4.A Build / Startup
- **A1 Baseline profile pipeline, fully wired.** Dedicated `:baselineprofile` module (`com.android.test` + `androidx.baselineprofile` plugin, `baselineprofile/build.gradle.kts:1-48`) whose generator drives 7 real UI flows with `maxIterations=5, stableIterations=3` (`BaselineProfileGenerator.kt:24-98`); app-side DSL: `automaticGenerationDuringBuild=false`, `saveInSrc=true`, **`dexLayoutOptimization=true`** (startup dex ordering, `app/build.gradle.kts:166-170`); generated profiles **committed** at `app/src/release/generated/baselineProfiles/{baseline,startup}-prof.txt` (80k+ rules); `profileinstaller` dependency wired (`app/build.gradle.kts:199-200`).
- **A2 Startup benchmarks** with A/B compilation modes (None vs Partial+BaselineProfile) — `baselineprofile/.../StartupBenchmarks.kt:27-51`, plus `PlayerSheetAnimationBenchmarks.kt` using `FrameTimingMetric` over real sheet gestures (55-step slow swipe, cancelled drags, 3× toggles).
- **A3 Benchmark buildType** (`initWith(release)`, non-debuggable) + app-side `is_benchmark` intent extras to force deterministic library state (`MainActivity.kt:241-249`) + CrashHandler skipped in benchmark builds (`PixelPlayerApplication.kt:100-102`).
- **A4 R8 discipline.** `isMinifyEnabled` + `isShrinkResources` + `proguard-android-optimize.txt`; **log stripping in release** via `-assumenosideeffects` for Timber/Log v/d/i (`proguard-rules.pro:59-74`); `android.r8.optimizedResourceShrinking=true`, `android.r8.strictFullModeForKeepRules=true` (`gradle.properties:21,26`).
- **A5 APK size.** `androidResources.localeFilters` to the 12 shipped languages (`app/build.gradle.kts:51-61`); ABI splits **without** universal APK (`:145-160`).
- **A6 Build speed.** Gradle config-cache, 4 GB G1GC daemon with `MaxGCPauseMillis=200`, `workers.max=6`, parallel + tooling-parallel, KSP2 (`gradle.properties:5-33`).
- **A7 App startup path.** Application.onCreate does only: CrashHandler (skipped for benchmarks), Timber, notification channel, observers; album-art cache migration on `Dispatchers.IO` startupScope; **all heavy collaborators injected as `dagger.Lazy<>`** (`PixelPlayerApplication.kt:42-137`).

### 4.B Playback / Audio Engine
- **B1 Windowed crossfade queue.** Player B (aux) gets a **200-item window** centered on the transition target, not the whole queue — `DualPlayerEngine.kt:232` (`MAX_AUXILIARY_TIMELINE_ITEMS=200`), `:1308-1318`, `:1526-1534`; absolute index bookkeeping `:612-626`. Generation-tagged transitions prevent stale async runs (`TransitionRunTracker`, `:70-83`).
- **B2 Audio offload policy suite.** Device denylist (Xiaomi SDK 36+, Pixel SDK 37+, LAVA SDK 35+) `:124-156`; enabled only for SYSTEM_DEFAULT output mode `:158-163`; enabled **with `setIsGaplessSupportRequired(true)`** `:1140-1152`; **4 s stall watchdog** that falls back by rebuilding players *preserving state* `:731-759, :820-916`; early-buffering HAL-reset heuristic with seek/crossfade guards `:195-211, :502-531`. All pure functions unit-tested (`AudioOffloadPolicyTest`, 13 tests).
- **B3 Dynamic wake mode.** `WAKE_MODE_NETWORK` for remote sources, `WAKE_MODE_LOCAL` for local (`:771-778`); when ExoPlayer reports the HAL is offloading (`onSleepingForOffloadChanged`), wake mode drops to **`WAKE_MODE_NONE`** so the SoC sleeps — `:413-436`.
- **B4 Memory-tier load control.** Low-RAM devices get 15 s/30 s buffer ceilings vs 30 s/60 s; `bufferForPlaybackMs=2500` kept at ExoPlayer default (comment: 5 s doubled first-audio latency) — `:947-971`.
- **B5 Shared audio session + lean renderers.** Process-wide `generateAudioSessionId()` pinned across A/B swaps so EQ survives (`:973-990`); renderer factory **strips video/text/camera renderers entirely** (`:1035-1061`) → faster player construction; `setEnableDecoderFallback(true)` + FFmpeg ext renderer (`:1062-1065`).
- **B6 Cloud URI resolution on the loading thread.** `ResolvingDataSource.Resolver` resolves `navidrome://`/`jellyfin://` schemes on ExoPlayer's loader thread with an `LruCache<String,Uri>(100)` (`:663, :1072-1118`); failures → `IOException` = retryable; **adjacent pre-resolution** 600 ms after each transition (`:466-486`); canonical durable URI kept in the timeline so dead proxy ports can't strand the persisted queue (`:1222-1278`).
- **B7 Playback snapshot persistence.** Debounced 1,500 ms persist (`:1308-1319`); **`PlaybackSnapshotItemCache`** caches per-item metadata and is invalidated only on `TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED` (`:1103-1109`) so next/prev never rebuilds the whole queue's metadata; paused restores only `prepare()` when queue ≤ **50** items (`PAUSED_RESTORE_PREPARE_QUEUE_LIMIT`, `:236, :1475`); unload/destroy flush with `playWhenReadyOverride=false` (`:2155-2189`).
- **B8 Widget update diffing.** 250/300 ms debounce **plus a `shouldUpdateWidget` diff gate** (title/artist/isPlaying/art/queue/theme/shuffle/repeat or position drift >3 s) — `MusicService.kt:1581-1596`; artwork work on `Dispatchers.IO` (`:1618-1652`).
- **B9 Session wrappers.** `FadingPlayer` (volume ramp on play/pause, `FadingPlayer.kt:22-95`) + `MappingPlayer` (rewrites artwork URIs for external controllers only, `MappingPlayer.kt:19-81`) wrap the active player — UX without engine complexity. Alarm-based sleep timers replace polling (`PlaybackTimerController.kt:23-79`).

### 4.C Data Layer / Sync
- **C1 `SONG_LIST_PROJECTION`** — list queries select only needed columns, alias `NULL AS lyrics` — prevents 2 MB `CursorWindow` overflow (`MusicDao.kt:81-89`; used at `:367-375, :442-453, :574-581`).
- **C2 FTS4 search, trigger-synced.** Virtual table `songs_fts(title, artist_name, tokenize=unicode61)` created at runtime and kept in sync **by SQL triggers** on INSERT/UPDATE/DELETE — zero re-index passes after sync (`PixelPlayerDatabase.kt:85-137`); search merges FTS + LIKE-fallback results dedup-by-id (`MusicDao.kt:467-488`); query builder caps at 6 prefix tokens (`:15-42`). Favorite flag is denormalized onto `songs` via triggers too (`PixelPlayerDatabase.kt:49-83`).
- **C3 SQL-side sorting & paging.** `ORDER BY` via parameterized `CASE WHEN :sortOrder` (`MusicDao.kt:650-676`); `ORDER BY RANDOM() LIMIT` for shuffle-all without loading the library (`:550-560`); single-pass aggregate stats query, never materializing rows (`getLibraryAudioStats`, `:517-540`).
- **C4 Batch discipline.** Upsert batches bounded by SQLite's 999-variable limit (`SONG_BATCH_SIZE=500`, cross-ref 333 — `:2024-2039`); set-based counter rebuilds `UPDATE ... (SELECT COUNT ...)` instead of per-row loops (`:258-276`).
- **C5 WAL journal mode** — concurrent reads during sync writes (`di/AppModule.kt:143`).
- **C6 Incremental sync engine.** `SyncExecutionPlan` classifies 4 scan modes (INCREMENTAL_CHANGES / LOCAL_RESCAN / DEEP_RESCAN / LOCAL_REBUILD) from mode+flags (`SyncExecutionPlan.kt:12-54`); timestamp cutoff = last-sync minus a **1 s overlap** (`:56-64`); deleted-song detection via ID-set diff before metadata work (`SyncWorker.kt:197-218`); **parallel tag reading** `Semaphore(4)` + 200-item chunks + `awaitAll` (`:985-1048`) with unchanged-song skip via chunked `getSongsByIdsListSimple` lookups (`:956-973`); embedded metadata read is **selective** (`:65-96`); scan-time artwork is URI-only, never decoded (`:1070-1078`); playback-aware deferral: `Result.retry()` while playing, max 5 (`:142-151`); whole worker traced for Perfetto (`:126`).
- **C7 Sync scheduling.** 6 h min interval, **1.5 s MediaStore debounce**, 60 s foreground catch-up cooldown; all status flows `shareIn(WhileSubscribed(5000), replay=1) + distinctUntilChanged` (`SyncManager.kt:87-91, 372-390, 410-411`); ContentObserver registered only while foregrounded (`MediaStoreObserver.kt:65-71`); heavy maintenance on 24 h periodic work with `charging + UNMETERED + storage-not-low` constraints (`SyncWorker.kt:1277-1292`).

### 4.D Compose / UI
- **D1 Stability config, always wired.** `app/compose_stability.conf` declaring `Song/Artist/Album/Genre/MusicFolder/PlayerUiState/StablePlayerState` + whole `data.model.**` package; passed to the compiler as an always-on free-arg (`app/build.gradle.kts:191-193`); **430 `ImmutableList` uses across 83 files** with `persistentListOf()` defaults in state classes.
- **D2 Adaptive, subscription-gated position polling.** Poll = 250 ms only while the seek-bar UI is mounted (`_sliderUiMounted`), 1000 ms for mini-player, **1000 ms when screen is off** (`powerManager.isInteractive`), and the entire loop is `collectLatest`-gated on subscription count — **zero polling with no subscribers**; writes are change-gated; media-id mismatch guard during crossfades (`PlaybackStateHolder.kt:44-48, 65-73, 451-518`).
- **D3 Slices everywhere.** `queueFlow = playerUiState.map { it.currentPlaybackQueue }.distinctUntilChanged().stateIn(WhileSubscribed(5000))` (`PlayerViewModel.kt:288-295`); composite `fullPlayerSlice`/`playerConfigSlice` via layered `combine` + `distinctUntilChanged` (`:1124-1230`); **grep for raw `playerUiState.collectAsStateWithLifecycle` in OSS = 0 matches** — no screen collects the aggregate.
- **D4 Deferred full-player composition.** The expensive full-player tree composes only when sheet is EXPANDED, expansion fraction > 0.015, or after a 650 ms warm delay — fraction read via `Animatable` inside `derivedStateOf`/`snapshotFlow`, never as a remember key (`scoped/FullPlayerCompositionPolicy.kt:22-73`); 32 ms composition **prewarm** on song start, disabled on low-RAM devices (`scoped/PrewarmFullPlayerState.kt:14-40`).
- **D5 graphicsLayer-lambda animations.** Sheet drag/scale/alpha read `Animatable.value` **inside** `Modifier.graphicsLayer {}` lambdas — draw-phase only, zero recomposition (`UnifiedPlayerSheetV2.kt:568-574`; 130 uses across 43 files).
- **D6 Single-transition item animations.** `EnhancedSongListItem` uses **one `updateTransition`** with 3 `animateFloat` children keyed on an `@Immutable` target; Dp/color values derived from the fractions (`EnhancedSongListItem.kt:63-67, 111-182`).
- **D7 Key + contentType discipline on every Lazy list** (`LibraryScreen.kt:2768-2783`, `HomeScreen.kt:330-464` with 10 distinct content types, paged lists with separate loading/footer types).
- **D8 Artwork loading.** `OptimizedAlbumArt`: 2048 px safety cap so `Size.ORIGINAL` can't decode camera images, size-derived memory-cache keys shared between list thumbs and full art, **disk cache disabled for local app-scheme art** (it's already a file) (`OptimizedAlbumArt.kt:39-40, 77-113, 224-253`); **neighbor prefetch** enqueues Coil requests for ±1 pager pages off `snapshotFlow { pagerState.currentPage }.distinctUntilChanged()` (`scoped/PrefetchAlbumNeighbors.kt:56-103`); list target sizes standardized 96/128/300 px with requests `remember`ed on all perf-relevant keys (`SmartImage.kt:41-117`).
- **D9 Queue bulk-replace.** Mutations above 80 items use **`setMediaItems`/`replaceMediaItems`** instead of O(n) `moveMediaItem` binder calls; segments built on `Dispatchers.Default`; current item + position preserved (`PlaybackStateHolder.kt:54, 588-683, 723-725`).

### 4.E Memory / Caching
- **E1 `onTrimMemory` ladder** (`PixelPlayerApplication.kt:149-180`): always trim Coil memory cache → ≥MODERATE/BACKGROUND/UI_HIDDEN: trim theme state → ≥LOW: clear artist-image cache + MediaMetadataRetriever pool → always: `libraryStateHolder.trimMemory(level)` → ≥CRITICAL/COMPLETE: full Coil clear.
- **E2 Library state release/restore.** On BACKGROUND/COMPLETE, `allSongs/allSongsById/albums/artists/musicFolders` are released to empty persistent lists + `needsReloadAfterTrim=true`; re-observation restarts on next foreground (`LibraryStateHolder.kt:531-572`, wired at `PixelPlayerApplication.kt:83`).
- **E3 Coil loader tuning.** Fixed 40 MB memory cache (deterministic across devices), 100 MB disk cache, `dispatcher(Dispatchers.Default)` (off main), `allowHardware(true)`, `respectCacheHeaders(false)` (no conditional re-fetches) (`di/AppModule.kt:230-253`).
- **E4 Album-art disk cache.** 200 MB LRU by `lastModified`, deletes oldest **25%** when over limit, cleanup rate-limited to 5 min + mutex, snapshot iteration to avoid TOCTOU during delete (`utils/AlbumArtCacheManager.kt:28-131`).
- **E5 Artist images.** `LruCache<String,String>(100)` + negative-result cache + in-flight dedup (Mutex) + `Semaphore(3)` prefetch; Deezer art upscaled to 1000×1000 by URL rewrite (bigger art, zero extra requests); custom-image decode: bounds-first + sample-size for ≤2048 px/≤4 MP + `RGB_565` + OOM catch (`data/repository/ArtistImageRepository.kt:44-95, 121-156, 285-341, 377-380`).

### 4.F Network
- **F1 Purpose-built clients.** Default client: ConnectionPool 5×30 s, 8 s timeouts, logging HEADERS debug-only with redaction; `@FastOkHttpClient` forces `HTTP_1_1` (no ALPN cost) + MODERN_TLS for latency-sensitive lyrics/Deezer lookups (`di/AppModule.kt:358-456`).
- **F2 Proxy architecture.** Ktor CIO embedded servers (no Netty in the runtime path), URL caches with ~30 min expiry, `warmUpStreamUrl` pre-resolution, scheme-based virtual URIs resolved by the player itself (see B6).
- **F3 Scrobble batching.** Listens persisted to Room, flushed by a `ScrobbleFlushWorker` (50/request, max 100 batches, backoff honoring `Retry-After`) — no per-track network (`ScrobbleManager.kt`, `ScrobbleFlushWorker.kt:39-105`).

### 4.G Test / Observability Infrastructure
- **105 unit-test files** + 7 androidTest + 4 macrobenchmark classes. Perf-critical logic has dedicated pure-function tests: `AudioOffloadPolicyTest` (13), `PlayerRebuildPolicyTest`, `TransitionRunTrackerTest`, `PlaybackTimerControllerTest`, `PlaybackSnapshotItemCacheTest`, `MusicDaoTest`, `PlaybackStateHolderTest`, `OptimizedAlbumArtTest`, `ExpressiveScrollBarMetricsTest`…
- **In-app `MainThreadStallMonitor`**: Choreographer frame-gap monitor recording stalls into `AdvancedPerformanceDiagnostics`, user-togglable (`data/diagnostics/MainThreadStallMonitor.kt:11-50`).

---

## 5. Un-Merged Work Already Sitting in PixelTune's Own Branches

These branches were written against an earlier HEAD; the default branch has since moved ~172 commits, so **expect conflicts — treat them as reference implementations and re-apply the pattern, don't blind-cherry-pick**. Verify each pattern is still missing first (all were verified missing as of 0aeec42).

### 5.1 `perf/optimization-pass` (8 unmerged commits)
| SHA | Subject | Re-apply value |
|---|---|---|
| `de4c863` | Kotlin 2.1.0 → 2.1.20 + Compose BOM 2025.05.00 → 2026.06.01 | **High** — runtime perf fixes; the conservative KSP1-compatible path (documented Hilt/KSP2 pitfalls to avoid) |
| `b2d3c87` | Slice search-screen state out of PlayerUiState aggregate | **High** — `SearchScreen.kt:165` still collects the full 48-field aggregate |
| `d57ad83` | Per-source-type adaptive buffer profiles | **High** — engine still has one static 30 s/60 s profile for local *and* cloud sources |
| `2630fa8` | Chunk Telegram channel backfills into transactional batches | **High** — current path accumulates the whole channel in memory, deletes+inserts non-transactionally |
| `280f352` | Wire baseline profile generation for release builds | **High** — the `:baselineprofile` module sources live in `src/main` so the plugin has nothing to run; no `app/src/release/` generated profiles exist |
| `4d6dada` | Per-screen JankStats frame-time histograms (debug) | **Medium** — zero JankStats instrumentation exists |
| `092ea4f` | Rename QueueStateHolder → QueueOrderStore | Cosmetic — skip unless conflict-prone |
| `bb55069` | Index songs.file_path + telegram chat_id (v24→v25) | **SKIP — already in HEAD via a broader `MIGRATION_24_25`** |

### 5.2 `perf/ui-smoothness-pass` (13 unmerged commits)
| SHA | Subject | Re-apply value |
|---|---|---|
| `042edbb` | derivedStateOf for 16 canScroll* composition reads | **High** — now 28 raw reads across 12 files (code grew) |
| `9aafb53` | LyricsSheet → provider-lambda position reads | **High** — sheet still recomposes at 4 Hz while lyrics show |
| `ead333f` | Constraint-based SmartImage request sizing | **High** — fixed `Size(300,300)` still disables Coil constraint sizing (`SmartImage.kt:57`) |
| `27c8cea` | Deferred topBarHeight reads on 3 collapsing-header screens | **High** — whole-screen recomposition per gesture frame |
| `ba16759` | Queue reorder-bookkeeping + draw-phase swipe-dismiss + sliced external player | **High** — swipe offset still read in composition (`QueueBottomSheet.kt:1894`) |
| `76c1fe9` | 300 ms tween for album-art color scheme transition | **High** — current `spring(StiffnessLow)` ≈1 s settle over 36 `animateColorAsState` |
| `01687f5` | Build queue MediaItems on Dispatchers.Default | **Medium-High** — 'Play all' on a big library stalls Main at tap (`PlayerViewModel.kt:3357-3396`) |
| `8a508dd` | Key derived states on their backing state objects | **Medium** — correctness/perf hygiene for derivedStateOf |
| `f68b95c` + `9011eda` | Tier-1 smoothness batch + compile fixes | Review as a set |
| `a1276d0` | Scroll frame-timing benchmarks + baseline profile coverage | **Medium** — measurement infra |
| `3d97d50` + `50bdd00` | Docs (findings + report) | Read for context before starting Phase 2 |

---

## 6. THE PLAN — Phased Tasks

Task format: **ID — Title** · Priority (P1>P2>P3) · Effort (S/M/L) · Risk (Low/Med/High) · Files · Change · Reference · Verify.
Work phases in order; within a phase, work by priority. Every task ends with a compiling, tested commit.

### PHASE 0 — Harvest the un-merged branches + unblock measurement (≈2–3 days)

> **Goal:** land the already-written work, fix the tooling that proves future work.

- **P0.1 — Slice SearchScreen state out of the 48-field `playerUiState`** · P1 · S · Low
  Files: `presentation/screens/SearchScreen.kt:165-210`
  Change: replace `playerUiState.collectAsStateWithLifecycle()` with a dedicated `SearchScreenSlice(searchResults, searchHistory, selectedSearchFilter, isOnlineSearch, ...)` built via `playerViewModel.playerUiState.map { ... }.distinctUntilChanged()`, exposed as a `stateIn(WhileSubscribed(5000))` flow on the ViewModel. Keep the existing `derivedStateOf` reads.
  Reference: OSS `PlayerViewModel.kt:288-295` pattern + `b2d3c87` (unmerged).
  Verify: playback running + typing in search → no recomposition storms in Layout Inspector; search behaves identically.

- **P0.2 — Fix `StartupBenchmarks` package target** · P1 · S · Low
  Files: `baselineprofile/src/main/java/.../StartupBenchmarks.kt:39`
  Change: target `com.saine.pixeltune` (the applicationId), not `com.theveloper.pixeltune` (namespace). Optionally also fix wear's `com.saine.PixelTune` casing (B12) separately.
  Reference: `PERFORMANCE_FINDINGS.md` B2.
  Verify: benchmark assembles; (with a device) it runs and emits numbers.

- **P0.3 — Kotlin 2.1.20 + Compose BOM 2026.06.01 + kotlinx-collections-immutable 0.5.1** · P1 · M · Med
  Files: `gradle/libs.versions.toml:26,37` (+ KSP `2.1.20-*`).
  Change: apply `de4c863`'s bump (NOT the full OSS jump to 2.4.10/AGP 9 yet). Also bump `kotlinxCollectionsImmutable` 0.3.7 → 0.5.1 so `persistentListOf` defaults are available for later tasks.
  Reference: OSS `libs.versions.toml:20,27,31`.
  Verify: full compile + unit tests; watch for Hilt/KSP2 pitfalls documented in that branch.
  Rollback: revert toml bump only.

- **P0.4 — Move Compose compiler reports behind a flag** · P2 · S · Low
  Files: `app/build.gradle.kts:94-101`
  Change: emit reports/metrics only when a project property (e.g. `pixeltune.enableComposeCompilerReports`) is set — OSS does exactly this (`app/build.gradle.kts:36-38, 183-189`). Speeds up every normal build.

- **P0.5 — JankStats per-screen histograms (debug builds only)** · P2 · M · Low
  Change: re-apply `4d6dada`. Add `androidx.metrics` (JankStats) behind a debug flag; log frame-time histograms per screen tag. This is the measurement harness for Phases 1–2.
  Note: the release `implementation` of `androidx.metrics.performance` was previously dropped as unused (B3) — reintroduce as `debugImplementation`.

- **P0.6 — Wire baseline-profile generation for release builds** · P1 · M · Med
  Files: `baselineprofile/build.gradle.kts`, `app/build.gradle.kts`.
  Change (from `280f352` + OSS A1): move generator/benchmark sources to `src/androidTest` (or apply the plugin's expected source set — currently they're in `src/main`, so the plugin has nothing to run); add `baselineProfile { automaticGenerationDuringBuild = false; saveInSrc = true; dexLayoutOptimization = true }` on `:app`; exclude the `benchmark` buildType from generation; generate on a device once and **commit** `app/src/release/generated/baselineProfiles/{baseline,startup}-prof.txt` (OSS keeps 80k+ committed rules so release builds never depend on a device).
  Keep the hand-written `app/src/main/baseline-prof.txt` until the generated one lands, then compare coverage.
  Verify: `./gradlew :app:generateReleaseBaselineProfile` produces files; release APK contains compiled profile (check with `apkanalyzer` or bundletool).
  Impact: typically **20–40% cold-start / first-scroll** — the single biggest free win available.

### PHASE 1 — Compose quick wins (≈2 days, all Low risk)

- **P1.1 — `rememberCanScrollMore()` helper + apply to all 28 raw `canScroll*` reads** · P1 · S · Low
  Files: new `presentation/components/RememberCanScrollMore.kt`; then `LibraryScreen.kt:2278,2582,2745,2990,3083,3382,3436,3790`, `ExpressiveScrollBar.kt` (5), `FileExplorerBottomSheet.kt` (3), `CloudCatalogScreen.kt` (2), `LibraryActionRow.kt` (2), `PlaylistDetailScreen.kt` (2), + QueueBottomSheet/ArtistDetail/LibrarySongsTab/AlbumDetail/PlaylistContainer/SongPickerBottomSheet (1 each).
  Change: `val canScroll by rememberCanScrollMore(listState)` = `remember(state) { derivedStateOf { state.canScrollForward } }`; pass the Boolean down (Booleans are stable → leaf-only recomposition). Crossing list top/bottom currently recomposes whole tab bodies.
  Reference: `042edbb` + OSS `derivedStateOf` discipline.

- **P1.2 — Theme crossfade: `spring(StiffnessLow)` → 300 ms `FastOutSlowIn` tween** · P1 · S · Low
  Files: `presentation/components/scoped/SheetThemeState.kt:113-115` (spec fed to `animateColorScheme` over 36 `animateColorAsState` at `:160-195`).
  Change: adopt `76c1fe9`. Cuts the per-track-change whole-player invalidate window from ~1 s to 300 ms.

- **P1.3 — Constraint-based `SmartImage` sizing** · P1 · M · Med
  Files: `presentation/components/SmartImage.kt:57,109`.
  Change: make `targetSize: Size? = null` (null → derive from incoming `Constraints` via `onSizeChanged`/`BoxWithConstraints` or Coil's constraint-aware sizing); keep explicit sizes only for the audited outliers (collapsing-header hero art, collage quadrants). Prevents ~1.8× oversized decodes on 56 dp rows.
  Reference: `ead333f` + OSS D8 (96/128/300 px standardized targets, requests `remember`ed on all keys).

- **P1.4 — Remaining raw `playerUiState` collectors → slices** · P1 · M · Low
  Files: `LibraryScreen.kt:3206` (`LibraryAlbumsTab` reads only `isAlbumsListView` + `currentAlbumSortOption`), `LibraryScreen.kt:3713` (`LibraryArtistsTab` same for artists), `QueueBottomSheet.kt:1014` (undo bar reads only `showQueueItemUndoBar`), `ExternalPlayerOverlay.kt:73` (4 Hz position).
  Change: per-tab slice flows (data-class slices, `.map{}.distinctUntilChanged()`, `WhileSubscribed(5000)`); position reads become provider lambdas in the overlay.
  Target: **zero** raw `playerUiState.collectAsStateWithLifecycle()` matches outside the player sheet (OSS = 0).
  Verify: `grep -rn "playerUiState.collectAsStateWithLifecycle" app/src/main/java | wc -l` → minimal set.

- **P1.5 — `contentType` for mixed-type Lazy lists** · P2 · S · Low
  Files: (verify current state, previously flagged) `LibraryScreen.kt` folders+songs sections, `SearchScreen.kt` results, `CloudCatalogScreen.kt`, `AlbumDetailScreen.kt`.
  Change: add distinct `contentType` strings per row type (pattern already exists in-repo in `GenreDetailScreen.kt:281`).
  Reference: OSS D7.

- **P1.6 — Build queue `MediaItem`s on `Dispatchers.Default`** · P1 · S · Low
  Files: `presentation/viewmodel/PlayerViewModel.kt:3357-3396`.
  Change: wrap the `songsToPlay.map { MediaItemBuilder.build(song) }` in `withContext(Dispatchers.Default)`. 'Play all' on a 5k+ library currently stalls Main ~100–300 ms.
  Reference: `01687f5`; OSS D9 builds queue segments on Default.

- **P1.7 — `HomeScreen.kt:118` full-library collection → narrow flows** · P2 · M · Low
  Change: Home needs recently-played + favorites + daily-mix inputs, not `allSongsFlow`. Add targeted DAO/StateHolder flows (e.g. recently-played window, favorites id-set). Avoids mapping the entire library on Home entry.

### PHASE 2 — Compose structural (≈3–4 days)

- **P2.1 — LyricsSheet: provider-lambda position reads** · P1 · M · Med
  Files: `presentation/components/LyricsSheet.kt:181-182, 717, 882-890`, `presentation/components/player/PlayerSeekBar.kt:43`.
  Change: current-line derivation computed **once** (`derivedStateOf` keyed on lyric data), position read only in the visible-line leaf via `() -> Long` provider or a draw-phase/snapshot read; `PlayerSeekBar` takes a position provider instead of a `Long` value. Kills the 4 Hz recomposition of the whole sheet + per-line `remember(position){derivedStateOf{...}}` re-allocation.
  Reference: `9aafb53`; OSS `LyricsSheet.kt:1134,1177` samples a `playbackPositionFlow` and slices consumers.

- **P2.2 — Deferred `topBarHeight` reads on the 3 collapsing-header screens** · P1 · M · Med
  Files: `CloudCatalogScreen.kt:397,402,507,520-521`, `AlbumDetailScreen.kt:274-320`, `GenreDetailScreen.kt:249-351,583`.
  Change: collapse fraction/topBar height read via `derivedStateOf` producing Booleans/quantized values consumed by leaves; the big screen `Box` scope must not read the animated height per frame. Reference: `27c8cea`.

- **P2.3 — Queue swipe-dismiss to draw phase + reorder bookkeeping** · P1 · M · Med
  Files: `presentation/components/QueueBottomSheet.kt:1894-1918, 1966, 429-434`.
  Change: move `dismissOffsetAnimatable.value` reads **inside** `Modifier.graphicsLayer {}` (currently read in composition at `:1894` then captured at `:1966`); reveal-icon tweens read the fraction via draw lambda or `derivedStateOf`; fix `onMove` allocating 2 N-element lists per drag event (remember the key lists). Reference: `ba16759`.

- **P2.4 — `ArtistDetailScreen` per-item `AnimatedVisibility` → section-level expand** · P1 · M · Med
  Files: `presentation/screens/ArtistDetailScreen.kt:329-333` (every song row in expandable album sections wrapped in `AnimatedVisibility`); check `GenreDetailScreen` for the same pattern.
  Change: expand/collapse the section as one unit (`animateContentSize` on the section container or conditional composition + `Modifier.animateItem()`), not per-row animations (50 rows = 50 parallel `expandVertically` + frame-by-frame re-layout).
  Reference: OSS performance audit Phase-1.3 recommendation (same lineage).

- **P2.5 — `EnhancedSongListItem`: 4 `animate*AsState` → single `updateTransition`** · P2 · M · Med
  Files: `presentation/components/EnhancedSongListItem.kt:101,152,162,173`.
  Change: one `updateTransition` keyed on an `@Immutable EnhancedSongAnimationTarget(isSelected, isCurrent)` with 3 `animateFloat` children; derive Dp/color values from fractions (OSS D6 exact pattern at `EnhancedSongListItem.kt:63-67, 111-182`).

- **P2.6 — Deferred full-player composition + prewarm** · P2 · M · Med
  Files: new `presentation/components/scoped/FullPlayerCompositionPolicy.kt` + `PrewarmFullPlayerState.kt`; wire into `UnifiedPlayerSheetV2`.
  Change: port OSS D4 — full player tree composes only when EXPANDED / fraction > 0.015 / after 650 ms warm; fraction read via `Animatable` inside `derivedStateOf`/`snapshotFlow` (never a remember key); 32 ms prewarm on song start, disabled on `isLowRamDevice`.

- **P2.7 — Album-art neighbor prefetch** · P3 · S · Low
  Change: port OSS `scoped/PrefetchAlbumNeighbors.kt` (Coil prefetch of ±1 pager pages / next queue items off `snapshotFlow { pagerState.currentPage }.distinctUntilChanged()`).

- **P2.8 — `toImmutableList()` discipline in state holders** · P3 · M · Low
  Change: 51 usages today; keep conversions in ViewModels/StateHolders only (never in composables), prefer `persistentListOf` defaults + `toPersistentList()` on already-converted data (persistent append/remove is O(log n) vs full copy). Bump dep to 0.5.1 first (P0.3).

### PHASE 3 — Playback engine, OSS-inspired (≈4–7 days)

- **P3.1 — Per-source-type adaptive buffer profiles** · P1 · M · Med
  Files: `data/service/player/DualPlayerEngine.kt:459-466` (single static `DefaultLoadControl` 30s/60s/2s/3s for all sources).
  Change: build load control per source kind — local files: short buffers (e.g. 15 s/30 s) for faster start + less memory; localhost-proxy/cloud: keep 30 s/60 s network profile; low-RAM devices: OSS B4 tiering (15/30). Rebuild players' load control on source-kind switch or at media-item transition.
  Reference: `d57ad83` (in-repo, proven) + OSS B4.
  Do **not** change `bufferForPlaybackMs` from the ExoPlayer default (2500 ms; repo evidence: 5 s doubled first-audio latency).

- **P3.2 — Audio offload policy suite** · P1 · L · High
  Files: new `data/service/player/AudioOffloadPolicy.kt` (pure functions) + `DualPlayerEngine.kt` integration.
  Change: port OSS B2 — device denylist (`shouldDisableAudioOffloadByDefaultForDevice`), output-mode gating, enable with `setIsGaplessSupportRequired(true)`, 4 s stall watchdog → state-preserving player rebuild, early-buffering HAL-reset heuristic with seek/crossfade guards. PixelTune currently disables offload entirely (`DualPlayerEngine.kt:473-480`) — offload is the biggest battery lever for background playback.
  **MUST** land with unit tests for every pure function (OSS ships `AudioOffloadPolicyTest` with 13 tests — adapt them).
  Manual verification: playback for 30+ min on a physical device, force a stall (airplane mode during cloud playback), confirm fallback rebuild preserves queue/position.

- **P3.3 — Dynamic wake mode + offload-aware sleep** · P1 · S · Low (after P3.2)
  Change: `WAKE_MODE_NETWORK` for cloud-proxy sources, `WAKE_MODE_LOCAL` for local files, and **`WAKE_MODE_NONE` in `onSleepingForOffloadChanged`** so the SoC races to sleep during offloaded playback (OSS B3, `DualPlayerEngine.kt:413-436`).

- **P3.4 — Windowed auxiliary queue for crossfade player B** · P2 · M · Med
  Files: `data/service/player/DualPlayerEngine.kt`, `TransitionController.kt`.
  Change: cap player B's timeline at a 200-item window centered on the transition target with absolute-index bookkeeping (OSS B1: `MAX_AUXILIARY_TIMELINE_ITEMS=200`, `auxiliaryWindowBounds`). Protects crossfade from O(n) timeline costs on 1000+ queues. Add `TransitionRunTracker`-style generation tags to invalidate stale async transitions.

- **P3.5 — Playback snapshot item cache + paused-restore prepare cap** · P1 · M · Med
  Files: new `data/service/PlaybackSnapshotItemCache.kt`; `MusicService.kt` snapshot writer.
  Change: cache per-item snapshot metadata; invalidate only on `TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED` so next/prev doesn't rebuild metadata for the whole queue (OSS B7). Paused restores only `prepare()` when queue ≤ 50 items. Debounce persist to 1,500 ms if not already (current writer ticks every 4 s — check `MusicService.kt:246-252`).

- **P3.6 — Queue bulk-replace above 80 items** · P2 · M · Med
  Files: `presentation/viewmodel/PlaybackStateHolder.kt` (queue mutation paths).
  Change: for queue mutations > 80 items (shuffle, reorder, remove-many), use `setMediaItems`/`replaceMediaItems` with segments built on `Dispatchers.Default`, preserving current item + position (OSS D9: `buildQueueSegments`, `:588-683`).

- **P3.7 — Widget update diff gate** · P2 · S · Low
  Files: `MusicService.kt` widget refresh path (currently 300 ms debounce + pinned-widget gating).
  Change: add OSS B8's `shouldUpdateWidget` diff (title/artist/isPlaying/art/queue/theme/shuffle/repeat or position > 3 s) before building player info; keep all artwork work on `Dispatchers.IO`.

- **P3.8 — Lean renderer factory** · P3 · M · Med
  Change: strip video/text/camera renderers from the players' `RenderersFactory` (music player never needs them) → faster player construction, less codec enumeration (OSS B5 `:1035-1061`). Consider sharing one `audioSessionId` across A/B players if the existing equalizer wiring allows.

### PHASE 4 — Data layer (≈3–5 days)

- **P4.1 — FTS4 search with trigger-synced index** · P1 · L · Med
  Files: `data/database/PixelTuneDatabase.kt`, `MusicDao.kt` (leading-wildcard `LIKE '%q%'` queries today).
  Change: port OSS C2 — runtime `CREATE VIRTUAL TABLE songs_fts USING FTS4(title, artist_name, tokenize=unicode61)` + INSERT/UPDATE/DELETE triggers (zero re-index after sync); `searchSongs` merges FTS + LIKE-fallback dedup-by-id; query builder caps at 6 prefix tokens. Backfill the index once for existing installs in the next migration (v26).
  Verify: search results identical for ASCII + CJK queries; large-library search latency before/after.

- **P4.2 — `SyncExecutionPlan` scan-mode classification** · P2 · M · Low
  Files: new `data/worker/SyncExecutionPlan.kt`; `SyncWorker.kt`.
  Change: port OSS C6's 4-mode plan (INCREMENTAL_CHANGES / LOCAL_RESCAN / DEEP_RESCAN / LOCAL_REBUILD) + 1 s timestamp overlap + playback-aware `Result.retry()`. PixelTune's sync is already incremental + `Semaphore(4)` — verify unchanged-song skip via chunked ID lookups exists (OSS `getSongsByIdsListSimple` chunks); add if missing.

- **P4.3 — WAL journal mode** · P1 · S · Low
  Files: `di/AppModule.kt` (Room builder).
  Change: `.setJournalMode(JournalMode.AUTOMATIC)` → explicit `WRITE_AHEAD_LOGGING` (OSS C5) so reads interleave with sync writes.

- **P4.4 — Unbounded caches → bounded LRU** · P1 · S · Low
  Files: `DualPlayerEngine.kt:269` (`resolvedUriCache: ConcurrentHashMap` → `LruCache<String,Uri>(100)`), `TelegramRepository.kt:405-411` (resolvedPathCache, uriResolutionCache, activeDownloads), `TelegramCacheManager.kt:62` (failedArtCache), proxy urlCaches.
  Change: swap for `LruCache` with sensible caps; keep expiry logic.

- **P4.5 — Room misc** · P3 · S · Low
  - `getRandomSongs`-style `ORDER BY RANDOM() LIMIT` for shuffle-all if the current path materializes the library.
  - Set-based counter rebuilds (`UPDATE ... (SELECT COUNT ...)`) where per-row loops exist.
  - Enable `exportSchema=true` + commit schema JSONs (Room-verified migrations; OSS commits 6 schemas). Requires adding the schemas dir; do it before the *next* migration (P4.1's v26).

### PHASE 5 — Memory (≈2–3 days)

- **P5.1 — Full `onTrimMemory` ladder** · P1 · M · Low
  Files: `PixelTuneApplication.kt:131-136` (today only clears `MediaMetadataRetrieverPool` at CRITICAL).
  Change: port OSS E1 ladder — always trim Coil memory cache; ≥MODERATE/BACKGROUND/UI_HIDDEN: trim theme state; ≥LOW: artist-image cache + MMR pool; always: `libraryStateHolder.trimMemory(level)`; ≥CRITICAL: full Coil clear.

- **P5.2 — Library state release/restore on background trim** · P1 · M · Med
  Files: `presentation/viewmodel/LibraryStateHolder.kt` (+ Application wiring).
  Change: on BACKGROUND/COMPLETE trim, release `allSongs/allSongsById/albums/artists/musicFolders` to empty persistent lists, set `needsReloadAfterTrim`, restart Room observation on next foreground (OSS E2, `LibraryStateHolder.kt:531-572`). Critical for 3–4 GB devices with 10k+ song libraries (baseline memory currently grows linearly with library size and never releases).

- **P5.3 — Coil loader deterministic config** · P2 · S · Low
  Files: Coil `ImageLoader` construction.
  Change: evaluate fixed 40 MB memory cache vs current 20%-of-heap (on 8 GB devices 20% is 400+ MB; on 2 GB devices it's tiny); `dispatcher(Dispatchers.Default)`; `respectCacheHeaders(false)` for artwork (OSS E3). Decide fixed-size vs percentage with on-device memory profiling; document the choice.

- **P5.4 — Widget `ByteArray`-as-JSON serialization → file/Base64** · P3 · M · Med
  Change: Glance state stores art `ByteArray` as JSON numeric arrays (~3.5–4× size expansion). Move to a file under `filesDir` referenced by path (or Base64 in a single string). Tier-3 in the prior audit — propose only.

### PHASE 6 — Build / dependency modernization (≈2 days)

- **P6.1 — Dependency dedupe** · P2 · S · Low: material3 declared 3× (`app/build.gradle.kts:158,164,242`), cast framework 2× (`:162,266`), splashscreen 2× (`:273,284`), junit-jupiter api/engine 2× (`:165-170`). Keep one version each (BOM-managed where possible).
- **P6.2 — Font dedupe** · P2 · S · Med: `gflex_variable.ttf` 3.9 MB exists in **both** `:app` and `:wear` (7.8 MB total) + `genre_variable.ttf` 1.7 MB. Subset to used glyphs (fonttools `pyftsubset` with `--unicodes-file`) or drop the wear copy if unused there; verify scripts (gflex covers symbol ligatures — check coverage before subsetting).
- **P6.3 — R8 log stripping** · P2 · S · Low: add `-assumenosideeffects` for Timber/Log v/d/i in release (`proguard-rules.pro`) — OSS A4. Keeps release hot paths free of string formatting.
- **P6.4 — `localeFilters`** · P3 · S · Low: limit `androidResources.localeFilters` to actually-shipped languages (OSS A5) — check which `values-*/` dirs exist and mirror that set.
- **P6.5 — ABI splits + universal APK review** · P3 · S · Med: today 4 per-ABI APKs **plus a universal APK** (TDLib ×4 ABIs ≈ 87 MB in universal). Decide distribution policy (OSS ships no universal); at minimum make universal opt-in.
- **P6.6 — Gradle daemon tuning** · P3 · S · Low: evaluate `-Xmx4096m -XX:+UseG1GC -XX:MaxGCPauseMillis=200` (OSS A6) vs current `-Xmx6g + UseParallelGC` — G1 with pause cap gives smoother daemon behavior during long builds.

### PHASE 7 — PixelTune-specific subsystems (≈3–5 days, unique to this fork)

- **P7.1 — Telegram channel backfill: chunked + transactional + resumable** · P1 · M · Med
  Files: `data/telegram/TelegramRepository.kt:150-184`, `data/repository/MusicRepositoryImpl.kt:248-258`, callers in `TelegramDashboardViewModel.kt:45-47` / `TelegramChannelSearchViewModel.kt:94-97`.
  Change: stream messages in pages; per-batch `withTransaction { delete-range + insert }`; persist a resume watermark so interrupted backfills continue; never accumulate the whole channel in memory. Reference: `2630fa8` (v1, simpler) or v2's `43a2d05` (watermark).

- **P7.2 — Wear art pipeline downsize + unchanged-skip** · P1 · M · Low
  Files: `wear` publishing side `WearStatePublisher.kt:50-53,119-128` (`ART_MAX_DIMENSION=2048`, `ART_QUALITY=99`, `MAX_URI_BYTES=12MB`).
  Change: watches render ≤ 450 px round displays — 640 px/q85 is visually indistinguishable and ~10× fewer bytes; skip re-publishing the art asset when its source URI is unchanged (keep a last-sent signature). Currently every track change re-decodes + re-sends up to 12 MB.

- **P7.3 — TDLib idle lifecycle** · P2 · L · High (propose-only)
  Change: TDLib native threads + SQLite live for process lifetime even when Telegram is never used. Open on login, `TdApi.Close` on logout/idle-timeout. Touches login flows — needs maintainer sign-off (flagged Tier-3 previously).

- **P7.4 — Cloud stream byte-cache keyed by mediaId** · P2 · L · High (propose-only)
  Change: proxy URLs contain ephemeral ports, so cache at the byte level keyed by song id (bounded, e.g. 100 MB, LRU) to make back-seeks/replays not re-download. Backward seek today re-downloads. Design first; risk of stale stream data.

- **P7.5 — `runBlocking` eliminations** · P1 · S · Low
  Files: `EqualizerViewModel.kt:487-498` (9 sequential DataStore writes in `onCleared` on Main), `DailyMixManager.kt:71` (init migration), `SongMetadataEditor.kt:128,201,240` (IO-thread only — acceptable, verify).
  Change: NonBlocking scope / `goAsync`-style async flush; DataStore exposes `edit` suspend API.

- **P7.6 — Announcement fetch TTL** · P2 · S · Low: `GitHubAnnouncementPropertiesService` fetches raw.githubusercontent.com every app open (`MainActivity.kt:705-724`) — cache with a 12–24 h TTL keyed by ETag or timestamp.

- **P7.7 — Proxy start lazy for cloud-only users** · P2 · M · Med: 3 Ktor CIO servers (Netease/YouTube/SoundCloud) start in `Application.onCreate` on IO threads even for users who never stream (`PixelTuneApplication.kt:100-102`). Start on first cloud playback intent (awaitReady plumbing exists per prior audit); keep ports warm during a streaming session.

### PHASE 8 — Test & benchmark infrastructure (continuous)

- **P8.1 — Pure-function unit tests for every ported policy** (P3.2 offload functions, P3.4 window math, P3.5 cache invalidation, P4.1 query builder) — mirror OSS's test-per-pattern suite (105 test files vs PixelTune's ~20 today).
- **P8.2 — Macrobenchmarks** (needs device/emulator): `StartupBenchmark` cold/warm with None vs Baseline compilation; scroll `FrameTimingMetric` on Library songs tab (5k songs, playback running); player-sheet open/close gestures (port OSS `PlayerSheetAnimationBenchmarks` flows); queue sheet open with 500-song queue.
- **P8.3 — In-app `MainThreadStallMonitor`** (OSS G): Choreographer frame-gap monitor behind a diagnostics toggle — field data from real devices.
- **P8.4 — Compose compiler metrics review** after P0.3/P0.4: `pixeltune.enableComposeCompilerReports=true ./gradlew :app:assembleDebug`, then check skippable/restartable ratios and unstable params in the reports.

---

## 7. Verification & Measurement Protocol

### 7.1 Per-commit gate (mandatory)
```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest     # 127 tests, 17 pre-existing failures = floor
./gradlew :app:assembleDebug
./gradlew :wear:assembleDebug         # for :wear/:shared-touching changes
```

### 7.2 Per-phase gate
1. **Compile + tests green** (same 17-failure floor).
2. **Manual smoke checklist** (physical device, ideally mid-range — the perf analysis targets Snapdragon 680-class hardware):
   - Cold start → first frame → Library tab rendered (time it with `adb shell am start-W -n com.saine.pixeltune/.MainActivity`)
   - Scroll Library songs tab (5k+ songs) while music plays — no visible jank
   - Player sheet open/close ×5, queue sheet open with 500+ songs
   - Track change with crossfade ON and OFF (player B is lazy — watch first crossfade readiness)
   - Favorite toggle in Library (no re-emission jank)
   - Search: ASCII + CJK queries
   - Widget present + absent; watch connected + disconnected
   - Process death → relaunch → queue restore (paused restore ≤ 50 items prepare-capped)
3. **Metrics capture** (once P0.5/P8 tooling exists): JankStats histograms before/after the phase; Macrobenchmark `StartupTimingMetric` + `FrameTimingMetric`.

### 7.3 Definition of Done per phase
- Phase 0: benchmarks runnable; baseline profiles generated & committed; all listed commits re-applied or consciously skipped with rationale.
- Phase 1: `grep -rn "playerUiState.collectAsStateWithLifecycle" app/src/main/java | wc -l` ≈ player-sheet only; zero raw `canScrollForward/Backward` reads outside `derivedStateOf`.
- Phase 2: Layout Inspector recomposition counts **stable** during 1 min of playback on Library/Search/Lyrics (no 4 Hz counters).
- Phase 3: 30-min background playback battery drain measurably lower; offload fallback tested; queue restore works after force-kill.
- Phase 4: search p95 latency down on 10k+ library; sync wall-time recorded before/after.
- Phase 5: 10-min background + 1-min foreground navigation on a 3 GB device → no LMK kill; heap stable after trim/restore cycle.
- Phase 6: APK size delta recorded; build time delta recorded.
- Phase 7: Telegram channel with 5k+ messages backfills without OOM; Wear art asset bytes down ≥5×.

### 7.4 Rollback strategy
Every task is a single commit → `git revert <sha>` restores behavior. Structural tasks (P3.2, P3.4, P4.1, P5.2, P7.1) additionally keep a feature-flag or config kill-switch for one release cycle where feasible (e.g. offload policy behind a preferences toggle defaulting to current behavior, flipped after burn-in).

---

## 8. Risk Register & Guardrails

### 8.1 DO NOT touch (audited as already optimal — `PERFORMANCE_FINDINGS.md` §3/§4)
- `DefaultLoadControl` `bufferForPlaybackMs=2500` value (2 s; 5 s doubled first-audio latency) — only the *ceilings* change in P3.1.
- Foreground-service gating on API 31+ (`MusicService.kt:2085-2104`).
- Proxy Range/206/Content-Length forwarding + `Accept-Encoding: identity` (`CloudStreamForwarder.kt:255-287`) — no per-seek re-download.
- URL caches with expiry-aware invalidation + in-flight dedup + next-track URL prefetch.
- Downloads `.part` + atomic rename + integer-% throttled notifications.
- **The 250 ms position poller while backgrounded** — deliberately kept: it feeds `listeningStatsTracker`; stopping it silently stops listening stats. (OSS solved this differently; if background CPU shows in profiles, move stats to a service-side source instead.)
- No `PeriodicWorkRequest`s (only one-shot sync throttled 6-hourly) — keep it that way.
- `MediaMetadataRetrieverPool` (cap 4, trimmed at CRITICAL).
- Manual `AudioFocusRequest` handling incl. LOSS/TRANSIENT/GAIN.
- `MUTATOR_MUTEX` / layout-phase drag reads in the player sheet (`UnifiedPlayerSheetV2` is already exemplary).

### 8.2 High-risk areas needing extra care
| Area | Risk | Mitigation |
|---|---|---|
| Room migrations (P4.1 v26, P4.5) | `exportSchema=false` today — no Room-verified migration safety net | Turn ON `exportSchema` + commit schemas BEFORE writing v26; test migration on a real old install |
| Offload policy (P3.2) | HAL bugs on specific OEMs (why OSS has a denylist) | Port the denylist as-is; watchdog fallback; ship behind a default-off preference first |
| Player B windowing (P3.4) | Crossfade index math errors → wrong track plays | Port OSS's absolute-index bookkeeping + unit tests for window math |
| Library trim/restore (P5.2) | Empty-state flash if restore fails | Restore before UI reads; loading skeleton already exists |
| Kotlin/BOM bump (P0.3) | Hilt/KSP2 incompatibilities | Use the exact 2.1.20 path proven on `de4c863`; full test suite; do not jump to Kotlin 2.4/AGP 9 in this pass |
| Telegram chunking (P7.1) | Data loss vs. old full-replace semantics | Keep delete+insert semantics per batch range; watermark only advances after committed batch |

### 8.3 Watch for upstream movement
PixelPlayerOSS is actively developed (offload policy, windowed crossfade, FTS, trim ladder all landed recently). Re-check its `main` for new patterns before starting each phase — particularly `data/service/player/`, `data/worker/`, and its own `app/performance_analysis.md` updates.

---

## 9. Suggested Branch & PR Strategy

- Work branch: `perf/optimization-pass-v4` off the current default branch.
- One PR per phase, tasks batched by concern (the repo's convention from the v1–v3 passes).
- PR descriptions: mirror `PERFORMANCE_FINDINGS.md`'s implementation-report table format (Commit / Area / Expected impact / Risk) — the maintainer clearly reads those.
- Keep `OPTIMIZATION_PLANNER.md` updated in each PR (checkbox the tasks, append the per-phase report to §10).

---

## 10. Progress Tracking Table (fill as you go)

| Task | Status | Commit | Phase PR | Notes |
|---|---|---|---|---|
| P0.1 | ☐ | | | |
| P0.2 | ☐ | | | |
| P0.3 | ☐ | | | |
| P0.4 | ☐ | | | |
| P0.5 | ☐ | | | |
| P0.6 | ☐ | | | |
| P1.1 | ☐ | | | |
| P1.2 | ☐ | | | |
| P1.3 | ☐ | | | |
| P1.4 | ☐ | | | |
| P1.5 | ☐ | | | |
| P1.6 | ☐ | | | |
| P1.7 | ☐ | | | |
| P2.1 | ☐ | | | |
| P2.2 | ☐ | | | |
| P2.3 | ☐ | | | |
| P2.4 | ☐ | | | |
| P2.5 | ☐ | | | |
| P2.6 | ☐ | | | |
| P2.7 | ☐ | | | |
| P2.8 | ☐ | | | |
| P3.1 | ☐ | | | |
| P3.2 | ☐ | | | |
| P3.3 | ☐ | | | |
| P3.4 | ☐ | | | |
| P3.5 | ☐ | | | |
| P3.6 | ☐ | | | |
| P3.7 | ☐ | | | |
| P3.8 | ☐ | | | |
| P4.1 | ☐ | | | |
| P4.2 | ☐ | | | |
| P4.3 | ☐ | | | |
| P4.4 | ☐ | | | |
| P4.5 | ☐ | | | |
| P5.1 | ☐ | | | |
| P5.2 | ☐ | | | |
| P5.3 | ☐ | | | |
| P5.4 | ☐ | | | |
| P6.1 | ☐ | | | |
| P6.2 | ☐ | | | |
| P6.3 | ☐ | | | |
| P6.4 | ☐ | | | |
| P6.5 | ☐ | | | |
| P6.6 | ☐ | | | |
| P7.1 | ☐ | | | |
| P7.2 | ☐ | | | |
| P7.3 | ☐ (propose-only) | | | |
| P7.4 | ☐ (propose-only) | | | |
| P7.5 | ☐ | | | |
| P7.6 | ☐ | | | |
| P7.7 | ☐ | | | |
| P8.1–P8.4 | ☐ | | | |

---

## Appendix A — Quick task ordering if you only have ONE session

If time-boxed to a single session, do these in order (highest impact ÷ effort, all verifiable):
1. **P0.1** Search slice + **P1.4** remaining slices (kills the biggest recomposition fan-out)
2. **P1.1** `rememberCanScrollMore` (28 sites)
3. **P1.2** theme tween + **P1.6** MediaItems on Default
4. **P0.6** baseline profile wiring (generate + commit profiles)
5. **P0.3** Kotlin/BOM bump
6. **P3.5** snapshot item cache + **P3.1** adaptive buffers
7. **P4.3** WAL + **P4.4** LRU caps
8. **P5.1** trim ladder
9. Run §7.1 gates, fill §10, open the PR.

## Appendix B — Key grep commands for state verification

```bash
# Raw aggregate collectors (target: player-sheet only)
grep -rn "playerUiState.collectAsStateWithLifecycle" app/src/main/java | wc -l

# Raw canScroll reads (target: 0 outside derivedStateOf/remember helpers)
grep -rn "canScrollForward\|canScrollBackward" app/src/main/java --include="*.kt" | grep -v derivedStateOf

# Per-row AnimatedVisibility in detail screens (target: 0)
grep -n "AnimatedVisibility" app/src/main/java/com/theveloper/pixeltune/presentation/screens/ArtistDetailScreen.kt

# Unbounded caches (target: all LruCache or capped)
grep -rn "ConcurrentHashMap\|mutableMapOf" app/src/main/java/com/theveloper/pixeltune/data/ | grep -i cache

# JankStats instrumentation (target: >0 after P0.5)
grep -rn "JankStats" app/src/main/java | wc -l
```

## Appendix C — Source documents this planner was built from

- PixelPlayerOSS deep analysis (this session): full optimization-pattern inventory with file:line — §4.
- PixelTune HEAD verification (this session): 13-of-14 unmerged-item check + subsystem inventory + top-20 ranking — §3, §5, §6.
- `PERFORMANCE_FINDINGS.md` (in-repo, prior audit + 22-commit implementation report on `perf/optimization-pass`, of which the v3 lineage is merged into HEAD).
- `app/performance_analysis.md` (in PixelPlayerOSS repo, its own Spanish-language audit — used to cross-check which OSS claims are real vs. stale).
- Git history: `perf/optimization-pass`, `perf/optimization-pass-v2`, `perf/optimization-pass-v3` (merged), `perf/ui-smoothness-pass`.



