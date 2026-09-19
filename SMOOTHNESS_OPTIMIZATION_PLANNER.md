# PixelTune Smoothness Optimization Planner

> **Execution-ready plan for a future session.** Generated 2026-09-20 by a comparative engineering analysis of
> **PixelPlayerOSS** (optimised reference — https://github.com/PixelPlayerHQ/PixelPlayerOSS @ `4386f38`, branch `main`)
> versus **PixelTune** (target — https://github.com/Saineeee/PixelTune @ `0aeec42`, default branch `fix/library-downloads-sort-playlists-filter-button-provider-ux`).
> This file is pushed alone on the brand-new branch `docs/smoothness-optimization-planner`.

---

## 0. How to use this planner

1. **Read §1 (scope discipline) and §2 (already-done list) first** — do not re-do landed work; do not violate constraints.
2. Clone the reference repo for side-by-side reading (public, no token needed):
   `git clone https://github.com/PixelPlayerHQ/PixelPlayerOSS.git`
   All `OSS:` paths below are relative to that repo root; `Tune:` paths are relative to the PixelTune repo root.
3. Execute **§5 Batch 1 → Batch 2 → Batch 3** in order. Batch 3 is *proposal-gated*: present the approach to the maintainer/user before implementing.
4. Verify every commit per **§6**; push per **§7**; produce the deliverables in **§8**.
5. **Line numbers drift.** Every finding was verified at the commits named above; before editing, re-locate each site with a text search (anchor snippets are quoted for exactly this purpose).
6. Companion document in-repo: `PERFORMANCE_FINDINGS.md` — the prior audit (its IDs are `S*/C*/M*/R*/B*/N*`). Most of it is **already fixed and landed**; this planner references those IDs in Appendix C so you can skip them.
7. The two repos share **no git history** (histories are unrelated), so this is a *pattern-level* comparison: for every gap there is a working, battle-tested reference implementation in the OSS tree. Cross-check against the OSS file before writing the Tune equivalent.

## 1. Mission & scope discipline

**Goal:** eliminate dropped frames / stutter in (a) scrolling, (b) animations & transitions, (c) playback-driven UI updates. Nothing else.

**Non-goals (record-only; do not implement unless directly frame-relevant):** startup time, APK size, battery, network behaviour, build-time speed. (Battery-adjacent items that were already cheap landed in earlier passes — see §2.)

**Hard constraints:**

- **No feature removal or silent disabling. No user-perceptible behaviour change other than "smoother".**
- **Preserve every integration** (locations in Appendix B): Wear OS sync, Android Auto, Cast, Glance widgets, Quick Settings tiles, backup/restore, tag editor, lyrics, equalizer, AI playlist providers, playlist import, downloads, and all cloud sources (YouTube/NewPipe, SoundCloud, Telegram/TDLib, Google Drive, Netease).
- **Do not modify `LICENSE` or `THIRD_PARTY_NOTICES.md`.** Both repos are GPL-3.0 with common lineage (PixelTune's `LICENSE` states "Portions of this software are based on PixelPlayer by Theo Vilardo"), so pattern transplantation is licence-compatible; when copying code verbatim rather than re-implementing, keep the receiving file's existing header/attribution conventions.
- **Do not delete the V1 `UnifiedPlayerSheet.kt`** even though it is legacy — it sits behind the user-visible `usePlayerSheetV2` toggle (`Tune: MainActivity.kt:965-985`, default **true** at `MainActivity.kt:941`). Removing V1 is a maintainer decision, out of scope here. (Optimising it is also out of scope — the default V2 path is what matters.)
- Leave `DualPlayerEngine` audio-renderer surgery (prior Tier-3, `Tune: DualPlayerEngine.kt:329-371`) and per-transition player-B rebuild (prior M2) alone — proposal-only tier, unchanged.
- Keep the deliberate M5 decision: the 250 ms progress poller keeps running while backgrounded to feed `listeningStatsTracker` (`Tune: PlaybackStateHolder.kt:317,367`). Change its *rate discipline*, not its lifetime policy (see F04).

## 2. Already done — do NOT redo

A previous optimization series landed ~22 perf commits on the current default branch; all were verified against current code:

| Prior item | Status | Where it landed |
|---|---|---|
| C1/C2 SmartImage was `SubcomposeAsyncImage` + software bitmaps | FIXED | `SmartImage.kt:115-192` plain `AsyncImage`, `allowHardware=true` default (line 56) |
| C3 `Song.displayArtist` recomputed per row | FIXED | `Song.kt:48-68` per-instance memo |
| C4 missing `contentType` on mixed lazy lists | FIXED | `LibraryScreen.kt:2298/2309/2321`, `SearchScreen.kt:1048/1081`, `CloudCatalogScreen.kt:427/461/488` |
| C6 `PlayingEqIcon` 60 Hz recomposition | FIXED | `PlayingEqIcon.kt:81-83` (animation value read only in Canvas draw lambda) |
| C8 search playlist O(n·m) membership | FIXED | `SearchScreen.kt:1157-1160` `toHashSet()` memoized |
| R1 favorite-toggle full-library re-emission storms | FIXED | `MusicRepositoryImpl.kt:176,298,343,842,942` `.conflate()` |
| R2 `isLibraryEmpty` full-table map | FIXED | `MusicDao.kt:849` `SELECT EXISTS` |
| R4 missing indices | FIXED | `PixelTuneDatabase.kt:463+` (`MIGRATION_24_25` etc.) |
| R6 playlists JSON re-parse per unrelated pref write | FIXED | `UserPreferencesRepository` `distinctUntilChanged` |
| R8a TileService `runBlocking` | FIXED | `LastPlaylistTileService.kt:58-89` async + IO |
| M1 player-B pre-buffer without crossfade | FIXED | `TransitionController` (commit `610ea4f`) |
| M3 duplicate 4 s queue-snapshot writer | FIXED | `PlayerViewModel.kt:1895-1918` cast-only collector |
| M4 widget/Wear pipeline over-firing | FIXED | `MusicService.kt:1722-1747,1861-1873` debounce + `getGlanceIds` gating |
| M6 duplicate listener-less MediaController | FIXED | removed from `MainActivity.onStart` |
| M7 two ExoPlayers at launch | FIXED | `DualPlayerEngine.kt:135-138` lazy `playerB` |
| S2 TDLib native init on main | FIXED | `PixelTuneApplication.kt:106-120` + `dagger.Lazy` edges |
| S5/M-leak MediaController release in `onCleared` | FIXED | `PlayerViewModel.kt:4455-4473` |
| S6 crash-log read on main | FIXED | `MainActivity.kt:277-290` |
| B1 baseline profile package casing | FIXED | 0 uppercase rules remain (7,845 lowercase) |
| B2 benchmark applicationId | FIXED | `StartupBenchmarks.kt:42` → `com.saine.pixeltune` |
| B9 gradle parallel/caching/config-cache | FIXED | `gradle.properties:16-18,31` |
| Mashup free-run 100 ms poller | FIXED | `MashupViewModel.kt:121-131` (deck-activity gated, 500 ms idle) |

**Deliberately kept (do not "fix" without asking):** M5 background poller (above); `-dontobfuscate` + blanket ktor/netty keeps in `proguard-rules.pro` (prior Tier-3 decision); Paging-3 library tabs; plain-`AsyncImage` SmartImage hot path; IPv4-first DNS + ytimg-fallback Coil OkHttp client (`Tune: AppModule.kt:212-219` — **Tune is better than OSS here, keep it**).

## 3. Evidence base

Findings come from three parallel deep analyses, cross-checked against each other and spot-verified by the lead analyst at the commits named in the header:

1. **PixelPlayerOSS optimization-pattern extraction** — every deliberate smoothness pattern in the reference, with file:line evidence (summarised in Appendix A).
2. **PixelTune remaining-gap audit** — the current state of every hot screen, verifying which prior findings are fixed vs. remaining.
3. **Side-by-side comparison** of the ten performance-critical component pairs (navigation, player sheet, list items, artwork, position pipeline, library, palette, lyrics, playback service, ViewModels).

Severity scale: **P0** = systemic, visible on every device; **P1** = clearly visible jank / ANR risk; **P2** = moderate; **P3** = minor / polish.

---

## 4. Findings inventory (remaining gaps)

### F01 — Lyrics screen recomposes 4×/second while open (P1 · prior C5 REMAINS)

**Evidence (Tune):**
- `LyricsSheet.kt:182` — position collected at **sheet scope**:
  `val playbackPosition by playbackPositionFlow.collectAsStateWithLifecycle(initialValue = 0L)`
- `LyricsSheet.kt:882-883` — collected **again** in `SyncedLyricsList`; `derivedStateOf` re-created per tick because it is keyed on `position`:
  `val currentLineIndex by remember(position, lines) { derivedStateOf { ... } }`
- `LyricsSheet.kt:1005-1006` and `:1076-1077` — per-row `remember(position, ...) { derivedStateOf { ... } }` churn.
- `LyricsSheet.kt:717` — `currentPosition = playbackPosition` feeds the whole 1,441-line sheet.
- `PlayerSeekBar.kt:43,49-64` — takes `currentPosition: Long` as a plain parameter; `LaunchedEffect(progressFraction)` relaunches per tick.
- `LyricsUtils.kt:306-307` — every instrumental `BubblesLine` row independently collects the 4 Hz flow and runs its own infinite transition.

**Why it janks:** four full recomposition waves per second across sheet + list + every visible row, plus per-tick allocation of `derivedStateOf` objects that defeats derivation.

**OSS reference:** `LyricsSheet.kt:1177-1184` samples the position **inside** `SyncedLyricsList` only; `rememberInterpolatedPlaybackPosition` (`LyricsSheet.kt:1851-1881`) anchors to the sampled position and advances per `withFrameNanos` by `elapsedMs * playbackSpeed` (karaoke precision at display rate with a 4 Hz sampler); active line via one un-keyed `derivedStateOf` (`:1189-1193`); word highlight via pure functions (`:1889-1897`); snap-based autoscroll keyed only on line-index changes (`:1203-1276`).

**Fix direction:** collect position once at the lowest scope that needs it; pass `positionProvider: () -> Long` into rows; drop `remember(position)` keys (keep `remember(lines)`); add frame interpolation for word-synced lyrics; keep Tune's preview-seek / `positionOverrideMs` and immersive-lyrics timeout behaviour intact. **Risk: low-medium (contained).**

### F02 — Collector storms: FullPlayerContent (18 flows) & player sheet (19 flows) at top scope (P1)

**Evidence (Tune):**
- `FullPlayerContent.kt:197-216` — eighteen top-level `collectAsStateWithLifecycle` calls including `trackVolume` (:212), `downloadedSongs`/`downloadStates` (:215-216 — **these tick during active downloads**), `bluetoothName` (:209), `immersiveLyricsTimeout` (:204), `selectedRouteName` (:207), `albumArtQuality` (:200)… any one of them invalidates the ~2,500-line full-player tree.
- `UnifiedPlayerSheetV2.kt:139-180` — nineteen collectors at sheet scope, incl. 7 preference flows collected individually (`:174-180`), `remotePositionState`/`isRemotePlaybackActive` (`:143-144`), `isCastConnecting` (`:203`), and the **full queue list** (`:158/:165`).
- Detail screens do the same with whole `playerUiState`: `SearchScreen.kt:165`, `AlbumDetailScreen.kt:113`, `ArtistDetailScreen.kt:111`, `PlaylistDetailScreen.kt:146`.

**Why it janks:** a download progress tick or a Bluetooth rename recomposes the entire full player; queue mutations recompose the whole sheet incl. mini player.

**OSS reference:** `PlayerViewModel.kt:1124-1215` builds `fullPlayerSlice` (combine of two part-slices, `distinctUntilChanged`, `stateIn(WhileSubscribed(5000))`) and `playerConfigSlice` (7 prefs → 1 flow); `FullPlayerContent.kt:242-244` collects only 3 things; the queue is collected **only inside the queue layer** (`UnifiedPlayerSheetLayers.kt:137-139`); `LibraryScreen` already has the correct pattern in Tune itself — `toLibraryScreenProjection()` slicing (`Tune: LibraryScreen.kt:253,356,671`) — extend it to the detail screens.

**Fix direction:** add slice flows (`fullPlayerSlice`, `playerConfigSlice`, keep `queueFlow` consumption inside the queue sheet only); scope download/cast/AI flows to the components that actually render them. Keep all flows reactive — this is pure re-scoping, not removal. **Risk: medium (mechanical but broad).**

### F03 — Player sheet animates in composition phase; full player torn down 450 ms after collapse (P0-structural, proposal-gated)

**Evidence (Tune):**
- `scoped/SheetVisualState.kt:18-26` — exposes plain `Dp`/`Float` derived states, read at **composition scope**: `UnifiedPlayerSheetV2.kt:333-334` (`playerContentAreaHeightDp`, `visualSheetTranslationY`), `:530-534` (`.padding(...)` / `.height(playerContentAreaHeightDp)`). Every frame of expand/collapse/drag invalidates the whole sheet subtree and **remeasures** it.
- `scoped/FullPlayerCompositionPolicy.kt:33,46-49` — `releaseDelayMs = 450`, then `keepFullPlayerComposed = false`: every re-expansion rebuilds the entire `FullPlayerContent` tree mid-animation (first-frame spike on each open).

**Why it janks:** whole-tree recomposition + remeasure per animation frame (60 Hz) during the single most-used gesture in the app; cold composition spike on every sheet reopen.

**OSS reference (complete working implementation):**
- `scoped/SheetVisualState.kt:20-32` — **provider-lambda API**: `playerContentAreaHeightPxProvider: () -> Float` ("read inside graphicsLayer"), `visualSheetTranslationYProvider: () -> Float` ("read inside .offset{}").
- `UnifiedPlayerSheetV2.kt:570-625` — consumed via `Modifier.offset { IntOffset(0, visualSheetTranslationYProvider().roundToInt()) }`, `graphicsLayer { }` blocks, and two custom `Modifier.layout { }` that re-measure the child at the current expansion size — **layout/draw-phase animation, zero recomposition per frame**.
- `scoped/SheetMotionController.kt:32-58` — `MutatorMutex` + early-return `animateTo` guards + gesture velocity handoff.
- `scoped/FullPlayerCompositionPolicy.kt:44-49` — after 650 ms collapsed, keep the full player composed **forever** (warm tree).
- `scoped/PrewarmFullPlayerState.kt:15-40` — 32 ms prewarm composition after song change; disabled on `ActivityManager.isLowRamDevice`.
- `UnifiedPlayerSheetV2.kt:269-271` — `setSliderUiMounted(shouldRenderFullPlayer)` via `DisposableEffect` (feeds F04).

**Fix direction:** port the provider-lambda `SheetVisualState` API and the `.offset{}/.layout{}/graphicsLayer{}` consumption; change the release policy to keep-warm; add the prewarm layer. Thread Tune's cast/remote branches through the provider pattern. **Risk: high — do it last, behind careful manual QA (sheet visuals, predictive back, cast sheet). This is the biggest single win in the plan.**

### F04 — Position poller has no rate discipline; slider recomposes per tick (P1)

**Evidence (Tune):**
- `PlaybackStateHolder.kt:294-384` — `while (true) { ...; delay(PROGRESS_TICK_MS) }` with `PROGRESS_TICK_MS = 250` (`:39`): fixed 4 Hz whenever playback is active — same rate whether the full player (needs 4 Hz), only the mini player (needs ≤1 Hz) or nobody is watching. (Writes are equality-gated — `:368-370` — that part is already good. Backgrounded operation is deliberate M5 — keep.)
- `WavySliderExpressive.kt:54` — `value: Float` **parameter** → every tick recomposes the whole slider call site; plus an invisible M3 `Slider` underneath (`:162-200`) recomposing its internals at the same rate.
- `UnifiedPlayerSheetV2.kt:145-149` already has `positionToDisplayProvider` — the pattern exists but stops at the slider boundary.

**Why it janks:** 4 Hz recomposition of the slider subtree while the player is open; wasted wakeups when collapsed.

**OSS reference:** `PlaybackStateHolder.kt:451-518` — poller gated on `_currentPosition.subscriptionCount` (no subscribers → no ticks), adaptive rate `currentProgressTickMs()` (`:515-518`: 250 ms slider mounted / 1000 ms mini-player only / 1000 ms screen-off), driven by `setSliderUiMounted()` (`:70-72`); `WavySliderExpressive.kt:70,105-215` — `value: () -> Float` provider + `derivedStateOf` + `snapshotFlow`/`withFrameNanos` smoothing, progress read only in `LinearWavyProgressIndicator(progress = { ... })` and `Canvas`; pointer handling via `awaitEachGesture` instead of a hidden M3 `Slider`.

**Fix direction:** add subscription gating + adaptive tick rate to the existing poller (keep the cast branch at `:301-338` on its own cadence and keep M5 lifetime policy); change `WavySliderExpressive`/`PlayerSeekBar` to provider-based value. **Risk: medium.**

### F05 — `EqualizerViewModel.onCleared()` blocks main thread with 10 sequential DataStore writes (P1 · prior R8b REMAINS)

**Evidence (Tune):** `EqualizerViewModel.kt:484-498` — `runBlocking { userPreferencesRepository.setEqualizerEnabled(...) /* ×10 */ }` on the main thread during VM teardown.

**Why it janks:** leaving the Equalizer screen freezes the main thread for 10 disk writes — ANR-window territory on slow storage.

**OSS reference:** the in-repo fixed pattern is Tune's own `PlayerViewModel.kt:4479-4488` (dedicated `CoroutineScope(SupervisorJob() + Dispatchers.IO)` for teardown persistence).

**Fix direction:** replace `runBlocking` with the PlayerViewModel pattern (or flush only in-memory debounced values). **Risk: trivial.** Verify settings still persist (kill process right after leaving screen).

### F06 — Shipped baseline profile misses the *default* player UI and 14+ hot screens (P1)

**Evidence (Tune):**
- `app/src/main/baseline-prof.txt` contains **zero** rules for `UnifiedPlayerSheetV2Kt`, `PlayerSeekBarKt`, `LyricsSheetKt`, `AlbumDetailScreenKt`, `ArtistDetailScreenKt`, `PlaylistDetailScreenKt`, `RecentlyPlayedScreenKt`, `ListeningHistoryScreenKt`, `MashupScreenKt`, `CloudCatalogScreenKt`, `GenreDetailScreenKt`, downloads/folder/equalizer screens — while legacy `UnifiedPlayerSheetKt` (V1, non-default) holds 566 rules. Verified by searching the file for `UnifiedPlayerSheetV2` → 0 matches.
- The default player is V2: `MainActivity.kt:941` `usePlayerSheetV2Flow.collectAsStateWithLifecycle(initialValue = true)`.
- `baselineprofile/src/main/java/com/theveloper/pixeltune/baselineprofile/BaselineProfileGenerator.kt:33-278` does not cover: cloud catalog/online results, album/artist/playlist detail, recently played, listening history, downloads, Mashup, equalizer, lyrics sheet, folders.
- `StartupBenchmarks.kt:38-50` — only `StartupTimingMetric`; **no frame-timing/scroll macrobenchmark exists**.
- No `dexLayoutOptimization`, no startup-profile split (vs OSS `app/build.gradle.kts:166-170` `baselineProfile { saveInSrc = true; dexLayoutOptimization = true }` + checked-in `app/src/release/generated/baselineProfiles/{baseline-prof.txt, startup-prof.txt}`).

**Why it janks:** first open of the full player / lyrics / every detail screen pays full JIT interpretation in release builds — the "first swipe feels worse" effect.

**Fix direction:** extend the generator with the missing flows (cloud catalog, detail screens, lyrics, downloads, recently played), regenerate on a device/emulator (casing bug already fixed), ship `baseline-prof.txt` + `startup-prof.txt` under `app/src/release/generated/baselineProfiles/`, wire `saveInSrc`/`dexLayoutOptimization`, and add a `FrameTimingMetric()` player-sheet gesture benchmark modelled on `OSS: baselineprofile/.../PlayerSheetAnimationBenchmarks.kt:26-149` (incl. its cancelled-drag synthesis `:172-188` and real-MediaStore assertion `:68-78`). **Risk: test-only; needs a connected device.** If no device is available in-session, land the generator/gradle wiring and flag regeneration as a manual step.

### F07 — `SineWaveLine`: infinite transition runs when idle + a 400-segment `Path` is rebuilt every frame (P1 on ListeningHistory, P2 elsewhere)

**Evidence (Tune):** `SineWaveLine.kt:50-62` — `rememberInfiniteTransition` created unconditionally (even when `animate != true`); `:79-90` — `val path = Path().apply { for (i in 1 until samples) lineTo(x, y) }` with `samples = 400`, rebuilt **every draw frame**. `ListeningHistoryScreen.kt:254-266` uses `animate = true, waves = 7.6f` in the always-composed header → 60 Hz allocation + invalidation churn for the whole session on that screen. Also present in SetupScreen ×2, ChangelogBottomSheet, PlaylistContainer, BetaInfoBottomSheet.

**Why it janks:** continuous GC pressure + draw invalidation concurrent with list scrolling.

**Fix direction:** `remember` the `Path` and `reset()/rewind()` + rebuild inside the draw lambda only when inputs change (or use `drawWithCache` — see `OSS: WavyMusicSlider.kt:208-226`); only create the infinite transition when `animate == true`; reduce default `samples` to ~120. **Risk: low.**

### F08 — Detail navigation transitions: 500 ms full-width slide + full-duration cross-fade; plus 600 ms of dead time after arrival (P2)

**Evidence (Tune):**
- `Transitions.kt:15-49` — `TRANSITION_DURATION = 500`; push enter slides the incoming screen across the **full** viewport width (`initialOffsetX = { it }`); exit fades over the **whole** duration (both screens alpha-blended for 500 ms); pop adds `scaleOut(targetScale = 0.75f)`.
- Hard-coded post-navigation sleeps: `AlbumDetailScreen.kt:120` (`delay(600)`), `:184`, `ArtistDetailScreen.kt:117`, `CloudCatalogScreen.kt:182`, `GenreDetailScreen.kt:104`, `SettingsScreen.kt:335`, `SettingsCategoryScreen.kt:1100` (`delay(600)`/`delay(800)`) — detail screens deliberately idle for 600 ms before doing any work ("Optimization: Defer list processing until transition is finished" — the comment at `AlbumDetailScreen.kt:117`).

**Why it janks:** 0.5 s window with two heavy screens composed + blended + scale transforms (overdraw), long perceived latency on every back navigation, and 600 ms of *added* dead time before content appears.

**OSS reference:** `Transitions.kt:13-58` — 450 ms with M3 *emphasized* easings (`CubicBezierEasing(0.2f, 0f, 0f, 1f)` etc.), enter slides only **half** width (`initialOffsetX = { (it * 0.5f).toInt() }`) with `scaleIn(0.92f)`, fade spec at **half** duration (`TRANSITION_DURATION / 2`); pop mirrored. OSS detail screens contain **zero** `delay(...)` post-navigation waits.

**Fix direction:** adopt the OSS constants/specs/offset fractions (keep Tune's function names and call sites); remove the `delay(600)` gates (load data immediately; the 450 ms transition is short enough that Room queries finish under it — verify on device; if a specific screen genuinely needs deferral, key it off `navBackStackEntry` transition completion rather than a blind sleep). **Risk: low-medium (pure visual timing; all screens share the global helpers).**

### F09 — Artwork decode sizing and row crossfades (P2)

**Evidence (Tune):**
- `ExpressiveSongListItem.kt:55-61` passes **no `targetSize`** → `SmartImage` falls back to `Size(300, 300)` (`SmartImage.kt:57`) → ~56 dp thumbnails decode ~5.5× the needed pixels.
- `EnhancedSongListItem.kt` hard-codes `Size(168, 168)` regardless of the actual `albumArtSize` parameter; OSS computes it from the real size.
- `SmartImage.kt:53,104` — `.crossfade(300)` default applies to all ~51 row call sites: every disk-cache miss animates alpha over ~18 frames while the row is transient during fling.

**OSS reference:** preset sizes `SmartImageCompactListTargetSize = Size(96,96)` / list `Size(128,128)` (`SmartImage.kt:41-43`); per-call-site `targetSize` (rows 96/128, mini player 150 at `UnifiedPlayerSheetShared.kt:93-103`); `EnhancedSongListItem` derives art target size from its `albumArtSize` param.

**Fix direction:** add preset constants and pass explicit `targetSize` at row call sites; set row crossfade to 0–150 ms (keep 300 ms for player/detail hero art). Keep the Telegram thumbnail `placeholderModel` branch untouched. **Risk: low.**

### F10 — `canScrollForward/Backward` read in composition for padding — 12 sites (P2 · prior C7 REMAINS)

**Evidence (Tune):** `LibraryScreen.kt:2278,2582,2745,2990,3083,3382,3436,3790`, `LibrarySongsTab.kt:265`, `QueueBottomSheet.kt:733`, `CloudCatalogScreen.kt:405,500` — e.g.
` .padding(end = if (listState.canScrollForward || listState.canScrollBackward) 22.dp else 12.dp)`

**Why it janks:** crossing the list top/bottom recomposes the whole tab/sheet body and changes list geometry exactly when scroll settles.

**Fix direction:** hoist `val scrollbarInset by remember { derivedStateOf { listState.canScrollForward || listState.canScrollBackward } }` and read only that Boolean (or move the inset into a `Modifier.layout { }` reading the state). Mechanical, 12 sites. **Risk: low.**

### F11 — Mashup: 10 Hz double emission + whole-screen scope; deck player churn (P2)

**Evidence (Tune):**
- `MashupViewModel.kt:126-128` — emits the whole `MashupUiState` **twice** per tick (deck1 + deck2 copies) at 10 Hz; `MashupScreen.kt:71` collects entire `uiState` → whole-screen recomposition 10×/s while a deck plays.
- `DeckController.kt:16-22` — **rebuilds an ExoPlayer per song load**; `MashupViewModel.kt:76-80` adds a new `Player.Listener` per load without removing the previous one (listener accumulation).

**Fix direction:** expose `deck1Progress`/`deck2Progress` as separate `StateFlow<Float>` consumed only by the deck components; remove the old listener before adding a new one in `loadSong`. Player reuse via `setMediaItem` is optional/riskier — treat as Tier-3 unless trivially safe. **Risk: low-medium.**

### F12 — Coil memory pressure: %-of-heap cache with no `onTrimMemory` cascade (P2)

**Evidence (Tune):** `AppModule.kt:222-225` — `memoryCache { maxSizePercent(0.20) }` (unbounded on large-heap devices → GC pressure); `PixelTuneApplication.kt:131-136` — only `MediaMetadataRetrieverPool.clear()` at `TRIM_MEMORY_RUNNING_CRITICAL`; no Coil trim, no theme-cache trim.

**OSS reference:** fixed 40 MB cap (`AppModule.kt:240-244`) and a tiered trim cascade at `PixelPlayerApplication.kt:150-180`: Coil `memoryCache.trimMemory` always → theme cache at MODERATE+ → artist-image cache + MRR pool at LOW+ → library trim → full Coil clear at CRITICAL/COMPLETE, with `restoreAfterTrimIfNeeded()` on foreground (`:83-91`).

**Fix direction:** cap the memory cache at a sane fixed size (or keep % but add a hard ceiling), port the tiered cascade. **Risk: low.**

### F13 — Palette pipeline: duplicate concurrent extractions, small cache, full-pixel sampling, no trim (P2)

**Evidence (Tune):** `ThemeStateHolder.kt:120-124` — album-scheme cache 30 entries (evicts hot schemes while carouselling); no request coalescing (queue row + carousel + sheet each trigger their own bitmap load for the same URI); `ColorSchemeProcessor` samples every pixel.

**OSS reference:** `ThemeStateHolder.kt:142-147` — `pendingAlbumColorSchemeTargets` coalescing map + 96-entry cache; `ColorSchemeProcessor.kt:73-78` + `ui/theme/ColorRoles.kt:41-48` — `ColorExtractionConfig(accuracyLevel)` pixel sub-sampling; `ThemeStateHolder.trimMemory()` wired from Application.

**Fix direction:** add coalescing, raise cache to ~96, adopt accuracy levels, wire trim (with F12). Cloud artwork URIs flow through the same `getOrGenerateColorScheme` path unchanged. **Risk: low.**

### F14 — Folder rows: full-subtree walk + 4–5 image collage per row (P2 · prior C10 partial)

**Evidence (Tune):** `LibraryScreen.kt:2372` — `remember(folder) { folder.collectAllSongs().take(9) }` materializes the **entire** subtree before `.take(9)` (`collectAllSongs()` at `:2472-2474`); `PlaylistArtCollage` (`:2385`, component at `PlaylistArtCollage.kt:65-211`) then issues 4–5 `SmartImage` requests at 128–256 px for a 48 dp slot.

**Fix direction:** make `collectAllSongs` accept a `limit` and early-terminate at 9; render 1–2 images at 64–96 px `targetSize` for the 48 dp slot. **Risk: low.**

### F15 — Scroll prefetch keyed on an always-inequal value → fires every frame (P2 · prior C12 REMAINS)

**Evidence (Tune):** `LibraryScreen.kt:3238-3239` and `:3267-3268` — `snapshotFlow { listState.layoutInfo }.distinctUntilChanged()` — `LazyListLayoutInfo` has no `equals`, so the collector fires **every scroll frame**; near the list end it enqueues up to 10 `ImageRequest`s per frame (`:3252-3260`).

**Fix direction:** key on `listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index` (an Int) so the collector fires only on real viewport changes. One-line fix per site. **Risk: trivial.**

### F16 — Playback service work: unconditional 4 s snapshot ticker; queue MediaItem rebuilds on main (P2 · prior M3-half/M9 REMAIN)

**Evidence (Tune):**
- `MusicService.kt:295-306` (`ENGINE_SNAPSHOT_TICK_MS = 4_000`, `:319`) — re-maps every upcoming `MediaItem` → snapshot (`buildSnapshotFromEngine()` `:222-236`) and writes DataStore (`:254-273`) for as long as playback is active; no item cache, no debounce. (The *ViewModel-side duplicate* was fixed — M3 — the service ticker itself remains.)
- `PlayerViewModel.kt:3315-3359` (prior M9) — whole-queue `MediaItem` + extras-Bundle build on the main thread before `setMediaItems` (GC spike on 1000+ item queues; Auto/Wear rely on the extras — keep building them, just off-main).

**OSS reference:** event-driven persist with 1.5 s debounce (`MusicService.kt:1308-1319`, constant at `:232`) + `PlaybackSnapshotItemCache` (`data/service/PlaybackSnapshotItemCache.kt:8-19`, invalidated only on `TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED` at `MusicService.kt:1103-1109`, rebuilt lazily at `:1345-1347`); MediaItems built on `Dispatchers.Default` and **bulk** queue ops beyond a threshold (`PlaybackStateHolder.kt:49-54,589-609,727-766` — `BULK_REPLACE_THRESHOLD = 80` replaces N `moveMediaItem` IPC round-trips with one `setMediaItems`/`replaceMediaItems` call — "freezes the UI on large queues").

**Fix direction:** keep Tune's teardown/last-chance persistence (`persistEngineSnapshotBeforeTeardown` `MusicService.kt:283-292`) and cloud-URI canonicalisation; switch the steady-state ticker to event-driven + debounced writes with an item cache; move queue MediaItem construction to `Dispatchers.Default`. **Risk: medium — crash-restore position must keep working (test: kill process mid-playback, restore).**

### F17 — Detail/search screens collect whole `playerUiState` at screen scope (P2)

**Evidence (Tune):** `SearchScreen.kt:165`, `AlbumDetailScreen.kt:113`, `ArtistDetailScreen.kt:111`, `PlaylistDetailScreen.kt:146`, plus the Library pager body (`LibraryScreen.kt:1131-1132` reads `playerUiState.currentSongSortOption`). Any unrelated uiState field (favorite toggle, queue op) recomposes the whole screen.

**Fix direction:** apply Tune's own `toLibraryScreenProjection()` pattern (`LibraryScreen.kt:253`) — map to small immutable projections + `distinctUntilChanged` per screen. **Risk: low (mechanical).**

### F18 — Song list items: 6 parallel `animate*AsState` per row; `List` where `ImmutableList` (P2)

**Evidence (Tune):** `EnhancedSongListItem.kt:80-130` — six independent `animate*AsState` (cornerRadius, albumCornerRadius, selectionScale, selectionBorderWidth, containerColor, contentColor, borderColor); `MultiSelectionStateHolder.kt:37` — `selectedSongs: List<Song>` (unstable → disables skipping in consumers); `LibraryScreen.kt:297,385-386` — `playlistSheetSongs`/`selectedAlbums` plain `List` + `selectedAlbums.map { it.id }.toSet()` recomputed per recomposition.

**OSS reference:** `subcomps/EnhancedSongListItem.kt:111-131` — one `updateTransition` + manual `lerpDp`/`lerpColor` (same target visuals); `MultiSelectionStateHolder.kt:27-33` — `ImmutableList<Song>`.

**Fix direction:** consolidate into a single `updateTransition` reproducing the same animation values; switch selection state to `ImmutableList` (kotlinx-collections-immutable is already an OSS dep — check Tune's `libs.versions.toml`, add if missing). **Risk: low.**

### F19 — `OptimizedAlbumArt`: unbounded `Size.ORIGINAL`, no dimension-suffixed cache keys, disk cache for local files (P2)

**Evidence (Tune):** `OptimizedAlbumArt.kt:37` — `targetSize: Size = Size.ORIGINAL` (embedded art can be 4000×4000); default `diskCachePolicy(ENABLED)` writes disk cache for local artwork that is already on disk; no memory-cache key suffixing (mini-player 150 px and hero 2048 px thrash one entry).

**OSS reference:** `OptimizedAlbumArt.kt:39-40,77-114,224-253` — 2048 px clamp, `albumArtMemoryCacheKey()` with dimension suffixes, `diskCachePolicy(if (isStableLocalArtwork) DISABLED else ENABLED)`, `lastSuccessPainter` retention (no placeholder flash on queue changes).

**Fix direction:** port the clamp/keys/disk-policy parts; **do not** port OSS's `allowHardware=false` default (Tune's hardware-bitmap path is already better). **Risk: low-medium.**

### F20 — Wear module gaps (P3)

**Evidence (Tune):** no `key` on the three list screens (`wear/.../presentation/screens/LibraryListScreen.kt:196`, `QueueScreen.kt:206`, `SongListScreen.kt:233` — `items(state.items.size)`); `WearPlayerViewModel` → `PlayerScreen.kt:124` uses non-lifecycle `collectAsState()`; phone-side `WearStatePublisher.kt:50-51` sends 2048 px quality-99 JPEG per publish (battery/data more than frames).

**Fix direction:** add `key = { state.items[it].id }`, switch to `collectAsStateWithLifecycle`, cap published art at ~512 px WEBP q85 with per-songId byte cache. **Risk: trivial.**

### Minor list (P3 — batch into one "polish" commit each batch)

- `ArtistDetailScreen.kt:329-333` — per-row `AnimatedVisibility` for section expand → replace with `Modifier.animateItem()` (OSS `ArtistDetailScreen.kt:336-359`), cap items during entry transition (`:311`).
- `QueueBottomSheet.kt:766-770` — always-on `animateItem` fade specs → apply only during reorder (OSS `:786-793`).
- `CloudCatalogScreen.kt:460` — index-baked keys `"cloud_song_${song.id}_$index"` → key on `song.id` only.
- `RoundedParallaxCarousell.kt:291-315` — anonymous `Shape.createOutline` allocates `Rect+Size+Path+Outline` per visible item per frame during swipe → hoist/cache shapes.
- `ExpressiveScrollBar.kt:250-262` — small per-frame `RoundRect/Rect/Offset/CornerRadius` allocations → reuse instances (keep the otherwise-excellent draw-phase design already present in Tune).
- `GitHubAnnouncementPropertiesService` — no TTL; fetched every app open (`MainActivity.kt:705-712`) → cache with TTL.
- `DailyMixManager.kt:41-44,71-73` — legacy migration `runBlocking` in singleton constructor path → move behind an IO-scoped init flag.
- `LyricsUtils.kt:306-307` — `BubblesLine` per-row position subscription (also covered by F01).
- Non-lifecycle `collectAsState()` at `LibraryScreen.kt:436-437,801`, `SongInfoBottomSheet.kt:113-114`, wear `PlayerScreen.kt:124` → `collectAsStateWithLifecycle()`.
- Optional (build-only, outside strict smoothness scope — do only if free): consolidate triple material3 / dual cast / dual mediarouter / dual splashscreen declarations (`app/build.gradle.kts:158-171,242-243,266,273,284`).

---

## 5. Implementation plan

Ordered by (perceived-smoothness impact ÷ regression risk). Every batch ends with the full §6 verification before moving on.

### Batch 1 — Quick wins (low risk, grouped commits)

Small, contained, mechanical changes. Land as a handful of themed commits (`perf(compose): …`, `perf(nav): …`, …). Nothing here changes architecture.

| # | Item | Files (Tune) | Commit theme |
|---|---|---|---|
| 1.1 | F05 EqualizerViewModel teardown off main | `EqualizerViewModel.kt:484-498` | `perf(main-thread): move equalizer teardown persistence to IO scope` |
| 1.2 | F15 prefetch key fix | `LibraryScreen.kt:3238-3239,3267-3268` | `perf(scroll): key image prefetch on last visible index` |
| 1.3 | F08 transition constants + remove `delay(600/800)` gates | `Transitions.kt`; `AlbumDetailScreen.kt:117-122,184`, `ArtistDetailScreen.kt:117`, `CloudCatalogScreen.kt:182`, `GenreDetailScreen.kt:104`, `SettingsScreen.kt:335`, `SettingsCategoryScreen.kt:1100` | `perf(nav): shorter emphasized transitions; drop post-navigation sleeps` |
| 1.4 | F10 `canScroll*` derived Boolean (12 sites) | listed in F10 | `perf(compose): derive scroll-edge padding inset` |
| 1.5 | F07 SineWaveLine allocation + idle animation | `SineWaveLine.kt:50-90` | `perf(draw): cache SineWaveLine path; gate idle animation` |
| 1.6 | F09 row artwork `targetSize` + short crossfade | `SmartImage.kt`, `ExpressiveSongListItem.kt:55-61`, `EnhancedSongListItem.kt` | `perf(images): right-size list artwork; disable row crossfades` |
| 1.7 | F17 detail/search state slicing | `SearchScreen.kt:165`, `AlbumDetailScreen.kt:113`, `ArtistDetailScreen.kt:111`, `PlaylistDetailScreen.kt:146` | `perf(compose): slice player state in detail/search screens` |
| 1.8 | F20a wear list keys + lifecycle collection | wear `LibraryListScreen.kt:196`, `QueueScreen.kt:206`, `SongListScreen.kt:233`, `PlayerScreen.kt:124` | `perf(wear): list keys; lifecycle-aware collection` |
| 1.9 | Minor polish batch | `collectAsState`→`WithLifecycle` (4 sites); announcement TTL; `DailyMixManager` migration off constructor | `perf(misc): lifecycle collection; announcement TTL; lazy daily-mix migration` |

**Gotchas:** 1.3 changes feel — verify on device that detail screens don't visibly "pop" content in mid-transition (if one does, gate *that* screen on transition-completion instead of a sleep). 1.6 must keep the Telegram `placeholderModel` branch and player/hero art at 300 ms crossfade.

### Batch 2 — Medium changes (one commit per item)

Each item is an isolated, reviewable commit with its own verification.

| # | Item | What to build (OSS reference in §4) |
|---|---|---|
| 2.1 | **F01 Lyrics position refactor** | Provider-based position through `SyncedLyricsList`/rows; single un-keyed `derivedStateOf` for line index; `rememberInterpolatedPlaybackPosition` port for word-synced lyrics; `PlayerSeekBar` provider param. Keep preview-seek + immersive timeouts. |
| 2.2 | **F04 Position poller rate discipline** | `subscriptionCount` gating + adaptive tick (250 ms slider-mounted / 1000 ms mini / 1000 ms screen-off) + `setSliderUiMounted()` from the sheet; `WavySliderExpressive` `value: () -> Float`; remove hidden M3 `Slider`, use `awaitEachGesture`. Keep cast branch cadence + M5 lifetime. |
| 2.3 | **F02 Slice flows** | `fullPlayerSlice` + `playerConfigSlice` in `PlayerViewModel`; `FullPlayerContent` collects 3 things; queue consumed only in the queue layer; re-scope download/cast/AI/volume flows to their real consumers. |
| 2.4 | **F11 Mashup slicing + listener hygiene** | Separate `deck1Progress`/`deck2Progress` StateFlows; remove stale `Player.Listener`s; (optional) deck player reuse. |
| 2.5 | **F13 Palette coalescing + cache** | Pending-targets map, 96-entry cache, accuracy levels. |
| 2.6 | **F12 + trim cascade** | Coil cache cap + tiered `onTrimMemory` (Coil → theme → artist/MRR → full clear) + foreground restore. |
| 2.7 | **F19 OptimizedAlbumArt hardening** | 2048 px clamp, dimension-suffixed memory keys, disk-cache skip for stable local art, `lastSuccessPainter` retention. Keep `allowHardware=true`. |
| 2.8 | **F18 Item animation consolidation** | One `updateTransition` in `EnhancedSongListItem`; `ImmutableList` selection state; remember derived id-set. |
| 2.9 | **F14 Folder-row cost** | `collectAllSongs(limit)` early-exit; 1–2 collage images at 64–96 px. |
| 2.10 | **F16 Snapshot persistence** | Event-driven + 1.5 s debounce + item cache in `MusicService`; queue MediaItem construction on `Dispatchers.Default` (keep extras for Auto/Wear); keep teardown persistence + cloud URI canonicalisation. |
| 2.11 | **F06 Baseline profiles + frame benchmark** | Extend generator (cloud catalog, detail screens, lyrics, downloads, recently played, folders); wire `saveInSrc`/`dexLayoutOptimization`; regenerate profiles on device if available; add `FrameTimingMetric` player-sheet benchmark (OSS template). Flag regeneration as manual if no device. |
| 2.12 | **Minor polish batch 2** | `Modifier.animateItem` in ArtistDetail; reorder-only queue animateItem; cloud keys; carousel shape caching; scrollbar instance reuse; F20b wear art downsize. |

### Batch 3 — Structural (proposal-gated: present before implementing)

**F03 — Player sheet draw-phase animation + keep-warm policy.** The single biggest smoothness win, and the only high-risk item. Do it last, after Batch 1+2 have landed and the sheet's state plumbing (F02/F04) is clean.

Proposed sequence (each step independently verifiable, in this order):
1. Introduce the provider-lambda `SheetVisualState` API (OSS `scoped/SheetVisualState.kt:20-32`) alongside the existing one; migrate consumers one call-site at a time (`.offset{}`, `graphicsLayer{}`, custom `.layout{}` — OSS `UnifiedPlayerSheetV2.kt:570-625`).
2. Switch `SheetMotionController` to the `MutatorMutex` + early-return-guard implementation (OSS `:32-58`).
3. Change `FullPlayerCompositionPolicy` to keep-warm after 650 ms (OSS `:44-49`); add `PrewarmFullPlayerState` 32 ms prewarm with `isLowRamDevice` gate (OSS `scoped/PrewarmFullPlayerState.kt:15-40`).
4. Wire `setSliderUiMounted()` (if not already done in 2.2).
5. Full manual QA pass (§6 list), including predictive-back, cast sheet, immersive lyrics, queue drag, floating-window insets.

**Explicitly NOT in scope (maintainer decisions / prior Tier-3 — do not do without asking):** deleting V1 `UnifiedPlayerSheet.kt`; per-transition player-B rebuild surgery (M2); custom `MediaCodecAudioRenderer` buffer surgery (M8); FTS search (R7); cloud stream byte-cache (M10); dependency consolidation beyond the optional note.

---

## 6. Verification protocol

Run after **every** commit (fast checks) and the full suite after each batch:

1. **Unit tests stay green:** `./gradlew :app:testDebugUnitTest :wear:testDebugUnitTest`
2. **All modules build:** `./gradlew :app:assembleDebug :wear:assembleDebug` (and once per batch: `:app:assembleRelease` to exercise R8 + profile wiring).
3. **Compose compiler reports** (already wired to run on builds, `app/build.gradle.kts:94-101`): after F02/F17/F18 confirm the touched composables now *skip* (stability report) — capture before/after counts for the deliverable.
4. **Frame evidence (before/after per batch, where a device/emulator is available):**
   - `adb shell dumpsys gfxinfo com.saine.pixeltune framestats` after a scripted fling on Library/Songs, Library/Folders, Search, CloudCatalog.
   - Macrobenchmark `FrameTimingMetric` (added in 2.11) for the player sheet gesture suite.
   - If no device is available, state that explicitly in the deliverable and rely on compiler metrics + tests.
5. **Manual QA checklist (device, once per batch — this is the acceptance bar):**
   - Fling-scroll Library tabs incl. Folders and Downloads; scroll Search + Cloud Catalog (online results); no stutter, no placeholder flicker storms.
   - Open/close the player sheet 10× in a row (incl. fast partial drags and a cancelled drag); expand while a track changes; predictive-back swipe.
   - Open Lyrics with a synced file (LRC); watch karaoke word highlight and autoscroll for a full minute.
   - Queue sheet: reorder a 200+ item queue; scroll during playback.
   - Leave the Equalizer screen immediately after toggling (F05); kill the process mid-playback and relaunch (F16 restore).
   - Start a cloud download, then browse Library during it (F02).
   - Connect Cast, expand the sheet, scrub (F02/F03/F04 cast branches).
   - Wear: browse library + queue on the watch (F20).
6. **Regression guardrails:** every preserved integration from Appendix B must behave identically — the plan never removes a code path, it re-scopes state and defers work.

## 7. Git & push protocol

- Create a **new** implementation branch off the repo's default branch: suggested name `perf/smoothness-oss-parity-pass`. **Do not reuse** any existing branch — `perf/optimization-pass`, `perf/optimization-pass-v2`, `perf/optimization-pass-v3`, `perf/ui-smoothness-pass`, `fix/perf-pass-build` already exist, as does this planner's branch `docs/smoothness-optimization-planner`.
- **Never push to `main`/`master` or the current default branch. Never force-push.**
- The GitHub PAT is supplied by the user **in the session** — use it only in the push remote URL. **Never write the token into any file, commit, or log** (this planner deliberately contains no credentials).
- Commit style: `perf(scope): summary` — one logical change per commit in Batches 2–3; themed group commits acceptable in Batch 1.
- After pushing, verify: `git ls-remote origin <branch>` shows the expected SHA.

## 8. Deliverables (what the implementing session must hand back)

1. A written summary: what was found (reference this planner's F-IDs), what was changed and why, and **before/after evidence per item** (frame stats, compiler skip counts, or an explicit statement that on-device measurement was unavailable).
2. Confirmation that all §6 checks passed, plus the list of items that still need on-device human verification.
3. The pushed branch name + commit list.
4. Any proposal-gated items (Batch 3) left unimplemented, with the written proposal instead.

---

## Appendix A — OSS transplant cheat-sheet (top patterns, with evidence)

All paths `OSS:` (repo root). Rank = suggested transplant priority.

| Rank | Pattern | Evidence | Benefit |
|---|---|---|---|
| 1 | Deferred-read provider lambdas for hot values (`() -> T` read inside `graphicsLayer{}`/`offset{}`/`layout{}`/`Canvas`) | `UnifiedPlayerSheetV2.kt:158-161,570-625`; `UnifiedPlayerSheetLayers.kt:150-186`; `WavySliderExpressive.kt:70,108,245-246` | Zero recomposition per animation/position frame |
| 2 | Subscription-gated, adaptive-rate position ticker | `PlaybackStateHolder.kt:451-518` (250/1000/1000 ms + equality-skipped writes) | No background invalidation; no wasted wakeups |
| 3 | Flow slicing per screen (`map{Slice}.distinctUntilChanged().collectAsStateWithLifecycle`) | `SearchScreen.kt:133-165`; `PlayerViewModel.kt:1111-1215` | Kills cross-screen recomposition fan-out |
| 4 | Batch ColorScheme animation: one `Animatable` + manual 34-field lerp in `derivedStateOf` | `SheetThemeState.kt:153-226` | 34 concurrent springs → 1 during theme fades |
| 5 | Stability conf + `@Immutable` UiState + `ImmutableList` | `compose_stability.conf`; `PlayerUiState.kt:16-18`; `StablePlayerState.kt:8-9` | List items become skippable |
| 6 | `items(key=…, contentType=…)` + remembered per-item lambdas everywhere | `LibraryMediaTabs.kt:340-357,414-417`; `QueueBottomSheet.kt:774-775` | Scroll/reorder composition reuse |
| 7 | `Modifier.animateItem()` instead of per-item `AnimatedVisibility`; cap items during entry transition | `ArtistDetailScreen.kt:311,331-373` | Layout-managed placement animation |
| 8 | Single `updateTransition` per list item | `subcomps/EnhancedSongListItem.kt:111-131` | 6–7 Animatables → 3 per row |
| 9 | Bounded Coil request sizes + dimension-suffixed memory keys + disk-off for local art | `OptimizedAlbumArt.kt:39-114,224-253`; `SmartImage.kt:41-43` | Less decode, better cache hit, no flash |
| 10 | Baseline profile pipeline: 7-step generator, `dexLayoutOptimization`, checked-in output, benchmark buildType | `app/build.gradle.kts:118-170`; `BaselineProfileGenerator.kt:24-78` | Pre-JIT'ed hot paths on low-end devices |
| 11 | Composition gating + keep-warm + 32 ms prewarm (low-RAM gated) | `scoped/FullPlayerCompositionPolicy.kt:21-73`; `scoped/PrewarmFullPlayerState.kt:15-40` | Sheet opens without composition spike |
| 12 | Choreographer stall monitor + opt-in diagnostics ring buffer | `MainThreadStallMonitor.kt:11-50`; `AdvancedPerformanceDiagnosticsController.kt:62-78` | Field-grade jank attribution |
| 13 | Bulk queue ops beyond threshold + off-main MediaItem builds | `PlaybackStateHolder.kt:49-54,589-609,727-766` | No UI freeze on large-queue shuffle/reorder |
| 14 | PlaybackSnapshotItemCache (invalidate only on playlist-changed) | `PlaybackSnapshotItemCache.kt:8-19`; `MusicService.kt:1103-1109` | Cheaper next/prev + persistence |
| 15 | No `TextMeasurer` in composition (deterministic heuristics) | `GenreTypography.kt:107-151` (commit `14c4b22`) | Unblocks first frame of text-heavy screens |
| 16 | snapshotFlow-driven prefetch (debounced, keyed on real changes) | `LibraryMediaTabs.kt:147-207` | Art ready when items appear |
| 17 | Palette: coalescing + LRU(96) + Room + 128 px software bitmaps + versioned keys | `ColorSchemeProcessor.kt:51-61,129-183`; `ThemeStateHolder.kt:142-222` | No palette pop-in or duplicate work |
| 18 | onTrimMemory tiered cascade + foreground restore | `PixelPlayerApplication.kt:150-180` | No GC death spiral on 3–4 GB devices |
| 19 | Frame-interpolated lyrics position (`withFrameNanos` anchor) | `LyricsSheet.kt:1851-1881` | Karaoke precision at 4 Hz sampling |
| 20 | Macrobenchmark guards: FrameTimingMetric sheet-gesture suite incl. cancelled drags | `PlayerSheetAnimationBenchmarks.kt:26-149,172-188` | Regression protection |

Also useful: `SheetMotionController.kt:32-58` (MutatorMutex + no-op guards); queue stable-key remapping (`QueueBottomSheet.kt:303-395`); `MarqueeText` on-demand overflow gating (`:43-53`); inset clamping + measured-height sheet anchoring (`PlayerInternalNavigationBar.kt:43-62`).

## Appendix B — Integrations preservation map (never remove; keep behaviour identical)

| Integration | Location (Tune) |
|---|---|
| Wear OS sync | phone `data/service/wear/WearStatePublisher.kt` + `MusicService.kt:1858`; watch `wear/.../data/WearDataListenerService.kt`, `wear/.../presentation/` |
| Android Auto | `data/service/MusicService.kt` (MediaLibrary session) + `app/src/main/res/xml/automotive_app_desc.xml` |
| Cast | `data/service/cast/CastOptionsProvider.kt`, `presentation/viewmodel/CastStateHolder.kt`, `CastBottomSheet.kt` |
| Widgets (Glance ×4) | `ui/glancewidget/PixelTuneGlanceWidget.kt`, `BarWidget4x1*`, `ControlWidget4x2*`, `GridWidget2x2*` |
| Quick Settings tiles | `data/service/tile/LastPlaylistTileService.kt`, `ShuffleAllTileService.kt` |
| Backup/restore | `data/backup/` (BackupReader/Writer, format/, validation/, history/) |
| Tag editor | `data/media/SongMetadataEditor.kt` via `MetadataEditStateHolder.kt` |
| Lyrics | `data/repository/LyricsRepositoryImpl.kt` + `presentation/components/LyricsSheet.kt` |
| Equalizer | `data/equalizer/EqualizerManager.kt` + `EqualizerScreen.kt` / `EqualizerViewModel.kt` |
| AI playlist providers | `data/ai/` (AiPlaylistGenerator; DeepSeek/Gemini clients) + `AiPlaylistSheet.kt` |
| Playlist import | `presentation/components/ImportPlaylistSheet.kt` + `PlaylistViewModel.importM3u` |
| Downloads | `data/downloads/DownloadedSongsRepository.kt` + `DownloadNotificationManager.kt` |
| Cloud sources | `data/youtube/` (NewPipe), `data/soundcloud/`, `data/netease/`, `data/telegram/` (TDLib), `data/gdrive/` |

## Appendix C — Mapping to `PERFORMANCE_FINDINGS.md` IDs

| Prior ID | Status | This planner |
|---|---|---|
| S1–S10 | fixed (except S8→minor list announcement TTL; S3 proxies = deliberate) | — |
| C1–C4, C6, C8 | fixed | — |
| C5 | REMAINS | **F01** |
| C7 | REMAINS | **F10** |
| C9 | partial | Minor list (cloud keys) |
| C10 | partial | **F14** + F09 |
| C11 | REMAINS (low) | Minor list (2.12) |
| C12 | REMAINS | **F15** |
| M1, M3, M4, M6, M7 | fixed | — |
| M2, M8 | deliberate Tier-3 | out of scope |
| M5 | deliberate | respected in F04 |
| M9 | REMAINS | **F16** |
| M10, M11 | flag-only | out of scope |
| R1, R2, R4, R6, R8a | fixed | — |
| R3 | fixed (targeted lookups) | — |
| R5 | latent, consumers dead | out of scope |
| R7 | structural (FTS) | out of scope |
| R8b | REMAINS | **F05** |
| B1, B2, B9 | fixed | — |
| B7 | REMAINS (build-only) | optional minor |
| B13 | partial | **F06** |
| N5 | REMAINS | **F20** |

## Appendix D — Session notes & environment

- **Line-number disclaimer:** every path:line above was verified at Tune `0aeec42` / OSS `4386f38`. Expect drift after any commit; re-locate sites via the quoted anchor code before editing.
- **Repos:** Tune https://github.com/Saineeee/PixelTune (default branch is `fix/library-downloads-sort-playlists-filter-button-provider-ux`); reference https://github.com/PixelPlayerHQ/PixelPlayerOSS (`main`). No shared git history — pattern comparison, not diffs.
- **Version context:** Tune Kotlin 2.1.0 / Compose BOM 2025.05.00; OSS Kotlin 2.4.10 / Compose BOM 2026.06.01. Strong skipping is on in both. Do NOT attempt a wholesale toolchain upgrade as part of this plan — out of scope.
- **Where Tune is already better than OSS (preserve, don't "fix"):** Paging-3 library tabs; plain-`AsyncImage` SmartImage hot path with single-node placeholder painter; IPv4-first DNS + ytimg-fallback Coil OkHttp client; hardware bitmaps by default; telegram thumbnail placeholder pipeline; download-state architecture (needs only re-scoping, F02).
- **Tooling for evidence:** Compose compiler reports already run on builds (`app/build.gradle.kts:94-101`); `adb shell dumpsys gfxinfo com.saine.pixeltune framestats`; Macrobenchmark module `:baselineprofile` (applicationId `com.saine.pixeltune`).




