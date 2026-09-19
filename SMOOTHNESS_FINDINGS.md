# PixelTune UI Smoothness Findings Report

**Scope:** dropped frames / jank / stutter ONLY — during scrolling, animations & transitions,
and playback-driven UI updates. Startup, APK size, battery and network efficiency are out of
scope unless they are the direct root cause of a frame drop (none were).
**Baseline:** tip of `fix/library-downloads-sort-playlists-filter-button-provider-ux` (0aeec42),
which already contains the three prior `perf/optimization-pass` batches
(SmartImage→AsyncImage, contentType, `PlayingEqIcon` draw-phase, conflate on library flows,
memoized `displayArtist`, Room indices, etc. — see `PERFORMANCE_FINDINGS.md`).
**Method:** full static audit of the current tree by three parallel deep-reads
(new `0aeec42` code / player plumbing / lists+images) with every finding below re-verified
by hand against the sources (file:line + quoted code). Runtime numbers marked ⚑ need the
new `:baselineprofile` scroll benchmarks (added by this pass) on a real device.

---

## 1. Findings (evidence)

### A. Playback-driven UI updates (fires constantly while music plays)

| # | Finding | Where (current tree) | Impact |
|---|---------|----------------------|--------|
| **S1** | **Playback history re-emitted 4×/s while playing; HomeScreen then re-merges the whole library on every tick.** `onProgress` runs from the 250 ms poller (`PlaybackStateHolder.kt:317,367`). After 1 s of listening every tick calls `upsertPlaybackHistory` (`ListeningStatsTracker.kt:139-170 → 279-298`) which allocates a new entry + new 30-item list whose `timestamp` always differs ⇒ StateFlow re-emits every tick. `HomeScreen.kt:121,134-158` collects it and re-runs `remember(playbackHistory, allSongs, cloudSongsById)`: full library merge (HashSet over all songs) + `mapRecentlyPlayedSongs` → `songs.associateBy { it.id }` (HashMap over the entire library) + sort — **4×/s on the main thread, while Home stays composed under the player sheet**. `RecentlyPlayedScreen`/`ListeningHistoryScreen` re-render their lists 4×/s too. | `ListeningStatsTracker.kt:139-170,279-298`, `HomeScreen.kt:121,134-158`, `RecentlyPlayedSongUi.kt:23` | **High** — the single biggest constant recomposition+GC load in the app; jank whenever the user is on Home/history while music plays. |
| **S2** | **Album-art theme cross-fade recomposes the entire player sheet every frame for ~1 s on each track change.** `rememberSheetThemeState` animates **2 ColorSchemes × 36 colors** by reading `animateColorAsState(...).value` in composition (`SheetThemeState.kt:112-115` + `animateColorScheme` at `:154-197`) with `spring(Spring.StiffnessLow)` (settles ≈1 s). Each frame produces new `ColorScheme` instances provided through `LocalMaterialTheme` — a **`staticCompositionLocalOf`** (`UnifiedPlayerSheet.kt:125`) at `UnifiedPlayerSheetLayers.kt:73,108,249`: a static-local value change invalidates *every consumer* in the mini player, full player and queue host. Default theme is `ALBUM_ART`, so **every track change pays a per-frame full-subtree recomposition exactly when queue rebuild + artwork decode + sheet motion also run** — the classic "track-change stutter". | `SheetThemeState.kt:112-115,154-197`, `UnifiedPlayerSheet.kt:125`, `UnifiedPlayerSheetLayers.kt:73,108,249` | **High** |
| **S3** | **Whole queue mapped to MediaItems on the main thread at playback start.** `playSongs` launches on `viewModelScope` (Main); `internalPlaySongs`'s `playSongsAction` runs `songsToPlay.map { MediaItemBuilder.build(song) … }` (`PlayerViewModel.kt:3353-3401`) — per song: Bundle with ~14 puts + MediaMetadata + MediaItem + Uri parse. "Play all" on a 5 000-song library = a 100–300 ms main-thread stall at the tap, immediately before the sheet/theme work of S2. | `PlayerViewModel.kt:3353-3401` (launch at `:3081`) | **High** (large libraries) |
| **S4** | **`EqualizerViewModel.onCleared` performs 10 sequential DataStore writes inside `runBlocking` on the main thread** — runs exactly when the nav transition off the equalizer screen starts. | `EqualizerViewModel.kt:484-505` | **Med** — one visible hitch per equalizer exit |
| **S5** | **Batch "add selected to queue" = N binder IPCs + N full queue rebuilds**: `songs.forEach { addSongToQueue(it) }` (`PlayerViewModel.kt:3627-3646`), each firing `onTimelineChanged` → full O(n) queue re-resolution + re-emit. 50-song selection = 50 IPCs + 50 rebuilds while the queue sheet is open. | `PlayerViewModel.kt:3627-3646,2577-2619` | **Med** |

### B. Scroll-time recomposition (hot lists)

| # | Finding | Where | Impact |
|---|---------|-------|--------|
| **S6** | **`canScrollForward/Backward` read directly in composition — 13 sites.** These getters are computed from `layoutInfo` (a State that writes a new instance every scroll frame), so reading them in composition subscribes the **entire tab/screen scope to every scroll frame** — the whole tab body recomposes while fling-scrolling (this is the canonical Compose anti-pattern; `derivedStateOf` is the documented fix). Sites: main Songs tab `LibrarySongsTab.kt:265`; Folders/Liked/Downloads/skeleton/Paginated/Albums-list/Albums-grid/Artists `LibraryScreen.kt:2278,2582,2745,2990,3083,3382,3436,3790`; Playlists `PlaylistContainer.kt:235`; CloudCatalog `:405,500`; AlbumDetail `:282`; PlaylistDetail `:562,568` (read twice inside a double `PaddingValues` build). | as listed | **High** — whole-scope recomposition during every scroll of every main list |
| **S7** | **Collapsing-header screens read `topBarHeight.value` (Animatable, `snapTo`-driven per scroll frame) in composition** — whole-screen Box scope recomposes every frame of the collapse/expand gesture: `CloudCatalogScreen.kt:397`, `AlbumDetailScreen.kt:274`, `GenreDetailScreen.kt:249` (uses `with(density){.toDp()}`). `GenreDetailScreen` already demonstrates the correct deferred pattern for the list offset — but still reads the value in composition for the top padding. | as listed | **High** during header collapse |
| **S8** | **`LaunchedEffect(listState.isScrollInProgress)` uses a scroll-state read as effect key** (`CloudCatalogScreen.kt:332`, `AlbumDetailScreen.kt:242`) — the key expression is evaluated in composition ⇒ the large enclosing scope recomposes (and the effect restarts) at scroll start AND end. `GenreDetailScreen` avoids it via `onPostFling`. | as listed | **Med** |
| **S9** | **`snapshotFlow { layoutInfo }.distinctUntilChanged()` is a no-op (2 sites)** — `LazyListLayoutInfo`/`LazyGridLayoutInfo` don't implement `equals`, so a new instance is emitted **every scroll frame** and the collect body runs per frame while scrolling Albums (list `LibraryScreen.kt:3238`, grid `:3267`). Inner gates limit actual prefetch enqueues, but the per-frame lambda + bookkeeping still runs. | `LibraryScreen.kt:3238,3267` | **Med** |
| **S10** | **Every visible playlist row collects the whole library and filters `it.id in playlist.songIds` (List ⇒ linear scan) — O(library × playlistSize) per row** on every library emission; rows also can never skip because `PlaylistItems` wraps its remembered lambdas in fresh per-recomposition lambdas (`PlaylistContainer.kt:272-279` wrapping `:250-268`). The HashSet form of this exact bug was already fixed in `SearchScreen.kt:1153-1160` but never ported. | `PlaylistContainer.kt:249,272-279,312-315` | **High** on the Playlists tab with a real library |
| **S11** | **Folder rows walk the folder tree synchronously in item composition** — `remember(folder) { folder.collectAllSongs().take(9) }` (`LibraryScreen.kt:2372`, recursive `collectAllSongs` at `:2472-2474`). Because `MusicFolder` is a nested data class, the `remember` key comparison itself is a **deep structural equals over the whole subtree** on every row recomposition (compounds S6). Each card then composes a 2×2 `PlaylistArtCollage` of SmartImages. | `LibraryScreen.kt:2372,2472-2474` | **Med-High** on Folders tab |
| **S12** | **Index-baked LazyList keys** — `CloudCatalogScreen.kt:458-461` `key = { i, song -> "cloud_song_${song.id}_$i" }`: removing a cloud song shifts every subsequent key ⇒ whole visible list recomposes & loses item state. Same class in `SearchScreen.kt:1063-1072` (playlist/cloud rows). | as listed | **Med** |
| **S13** | **Unstable lambda/instance params defeating skipping** — `ThemeStateHolder.getAlbumColorSchemeFlow` returns `cached.asStateFlow()` which **allocates a new read-only wrapper per call** (`ThemeStateHolder.kt:132-152`): every recomposition of the Albums tab hands new Flow instances to `AlbumListItem`/`AlbumGridItemRedesigned` (`LibraryScreen.kt:3396-3397,3453-3454`) ⇒ rows never skip and `collectAsStateWithLifecycle` restarts. `SearchScreen.kt:1162` `onPlayClick` is the only un-remembered row lambda (siblings at `:1087-1241` all remembered). `SearchScreen.kt:992-1012` re-runs `results.groupBy{}` + section-order build on every results-list recomposition (play/pause flips, library emissions). `LibraryScreen.kt:386` re-maps `selectedAlbumIds` per screen recomposition. | as listed | **Med** |
| **S14** | **`ShimmerBox` reads its translate animation in composition** (`ShimmerBox.kt:27-44`): rebuilds a `Brush.linearGradient` per frame ⇒ every skeleton box (12 rows × 3-4 boxes on loading screens, plus album-art loading overlays at `LibraryScreen.kt:3636,4062`) recomposes at ~60 Hz while visible. | `ShimmerBox.kt:27-44` | **Med** on every skeleton/loading state |
| **S15** | **SmartImage pins every default request to `Size(300,300)` px** (`SmartImage.kt:57,109` `.apply { size(targetSize) }` unconditional) — disables Coil's constraint-based sizing: a 56 dp row at @3x (~168 px) decodes ~1.8× oversized ⇒ extra memory + decode time + GC while fling-scrolling. Worse outliers: collapsing headers request `Size(1600,1600)` (`AlbumDetailScreen.kt:469`, `CloudCatalogScreen.kt:763`) ≈ **10.2 MB bitmaps for a ≤ screen-size view**; `PlaylistArtCollage.kt` uses 128/256 px for 48 dp quadrants (~1.8-3× oversized ×4 per folder card); several rows hardcode `Size(168,168)` for 56 dp boxes — exact @3x but blurry @3.5-4x, oversized @2x (density-blind). | `SmartImage.kt:57,109` + call sites | **Med-High** (decode cost + GC during scroll) |

### C. Player sheet / lyrics / queue

| # | Finding | Where | Impact |
|---|---------|-------|--------|
| **S16** | **LyricsSheet recomposes the whole sheet + every visible lyric row 4×/s.** Sheet scope collects position (`LyricsSheet.kt:182`) and passes `currentPosition = playbackPosition` (Long) into `PlayerSeekBar` (`:713-724`) which recomputes `progressFraction` + restarts a `LaunchedEffect` per tick; list scope re-creates `remember(position, lines){ derivedStateOf { … } }` every tick (`:882-883` — keyed on its own input, defeating derivation) and passes raw `position: Long` into every `LyricLineRow` (`:941`). The main player was already migrated to the provider-lambda + `rememberSmoothProgress` pattern — the lyrics sheet is the last big consumer of raw per-tick position. | `LyricsSheet.kt:182,713-724,882-893,941` | **High** while lyrics are open |
| **S17** | **QueueBottomSheet collects the whole `PlayerUiState`** (`QueueBottomSheet.kt:1014`) only to read `showQueueItemUndoBar`/`lastRemovedQueueSong` — any unrelated field change (search results, sync flags, folders…) recomposes the whole open queue sheet. | `QueueBottomSheet.kt:1014` | **Med** |
| **S18** | **Queue list fallback order/key lists re-allocated on every recomposition + O(n) `indexOf` per drag move** (`QueueBottomSheet.kt:741-747,408-434`): `reorderPreviewOrder ?: List(displaySongCount){…}` and the keys fallback allocate two N-element lists per recomposition of the content scope; each drag-move does `keys.indexOf` + a full copy. With big queues this is GC pressure + janky reordering. | `QueueBottomSheet.kt:741-747,408-434` | **Med** |
| **S19** | **Queue row swipe-dismiss reads `dismissOffsetAnimatable.value` in composition** (`QueueBottomSheet.kt:1893-1895` → `.graphicsLayer { translationX = currentOffsetPx }` at `:1966` captures the composition value): the whole row (Surface, texts, buttons) recomposes and the reveal Box re-measures every frame of the swipe. | `QueueBottomSheet.kt:1893-1966` | **Med** during swipe |
| **S20** | **`ExternalPlayerOverlay` collects position at overlay scope** (`ExternalPlayerOverlay.kt:73`) — whole external-player overlay recomposes 4×/s with `formatDuration` string allocations per tick. **`WavyMusicSlider.kt:112`** reads `phaseShiftAnim.value` in composition — recomposes per frame while the wave animates. Both are the legacy pattern the in-app player was fixed away from. | `ExternalPlayerOverlay.kt:73`, `WavyMusicSlider.kt:112` | **Med** (Android Auto / external surfaces) |
| **S21** | Whole-state provider reads in the full player: `FullPlayerContent.kt:1448-1449` collects entire `stablePlayerState` for `isBuffering`; `AnimatedPlaybackControls.kt:75`, `FullPlayerContent.kt:1197-1198`, `BottomToggleRow.kt:37` read whole-state providers — any `StablePlayerState` field change (lyrics-load flips, duration resolve) recomposes those sections. | as listed | **Low-Med** |

### D. New code from `0aeec42` (Downloads sort / Playlists filter / provider indicator)

| # | Finding | Where | Impact |
|---|---------|-------|--------|
| **S22** | **`currentOnlineProvider` collected in the HomeScreen body** (`HomeScreen.kt:418-429`) — the provider tap that also starts `sheetState.hide()`, two badge spring animations and (for cloud playback) a network hand-off triggers a **whole-~450-line HomeScreen body recomposition on the same frames as the sheet-dismiss animation**. | `HomeScreen.kt:418-429` | **Med** (fires on provider switch) |
| **S23** | **Provider "Active" badge uses `expandHorizontally(spring(DampingRatioMediumBouncy))` inside a `Modifier.weight(1f)` column** (`StreamingProviderSheet.kt:286,311-326`): animating layout width re-measures the weighted title/subtitle Texts every frame, and the underdamped spring oscillates for several hundred ms — three concurrent animation loads (badge expand + sibling shrink + sheet hide) on the switch tap. | `StreamingProviderSheet.kt:286,311-326` | **Med** |
| **S24** | **Netease card navigates *before* the sheet-dismiss animation** (`StreamingProviderSheet.kt:216-223`): `navigateSafely` runs while the sheet is still animating down (and while S22 recomposes Home) — overlapping the nav transition with the sheet transition. The app's own convention (`HomeScreen.kt:397-404`) navigates inside `invokeOnCompletion` after `hide()`. | `StreamingProviderSheet.kt:216-223` | **Med** |
| **S25** | Downloads sort does `lowercase()` per comparison in composition (`LibraryScreen.kt:2688-2702`, `sortedBy { it.title.lowercase() }` ⇒ O(n log n × 2) String allocations); playlist source filter re-sorts the full playlist set per tap on the Main dispatcher (`PlaylistViewModel.kt:342-350`); `LibraryActionRow`'s new `onPlaylistSourceFilterClick` lambda is un-remembered like its 7 siblings (`LibraryScreen.kt:964-966`). | as listed | **Low** (typical sizes) — cheap hygiene |
| **S26** | `topBarHeight.snapTo()` launched as a coroutine per scroll frame in `onPreScroll` (`CloudCatalogScreen.kt:313-317`, `AlbumDetailScreen.kt:223-227`, `GenreDetailScreen.kt:142-144`) — per-frame allocation + dispatch. | as listed | **Low** |

### E. Tooling / verification gaps

| # | Finding | Where | Impact |
|---|---------|-------|--------|
| **S27** | **`:baselineprofile` has NO scroll/frame-timing benchmarks at all** — only `StartupBenchmarks` (StartupTimingMetric). None of the main scrollable screens (Home, Library tabs, Search, queue) have jank metrics, so smoothness regressions/fixes cannot be quantified. | `baselineprofile/…/StartupBenchmarks.kt` | blocks evidence |
| **S28** | **Baseline profile generator coverage gaps**: covers Home/Settings/Library-pager/Genre/Search/Player-sheet/Queue, but not Folders tab, Album/Artist detail, Liked/Downloads tabs. (Cloud catalog excluded intentionally — needs network.) | `BaselineProfileGenerator.kt` | Med — hot paths not pre-compiled |

### Verified clean (no action needed — avoids false positives)

- Position plumbing of the main player: sliced `currentPlaybackPosition` flow, provider-lambdas (`positionToDisplayProvider`), `rememberSmoothProgress` 180–320 ms sampling, draw-phase slider (`WavySliderExpressive`), 1 s-quantized time labels — all still correct.
- Palette extraction off-main with LRU + Room caches (`ColorSchemeProcessor`); sheet theme scoped via `LocalMaterialTheme` so **no whole-activity recomposition** on track change (the problem is S2's per-frame churn *inside* the sheet).
- Sheet drag/expand visuals read the expansion `Animatable` in `derivedStateOf`/`graphicsLayer` (draw phase); V2 composition gating (prewarm + keep-alive) intact.
- `SmartImage` rewrite (plain `AsyncImage`, hardware bitmaps, remembered request/painters, crossfade) is good — S15 is about *request sizing*, not the loading path.
- Keys/contentType on Songs/Liked/Downloads/Folders/Search/Genre/history lists; Paging keys via `peek`; `Song` `@Immutable` + memoized `displayArtist`; `ExpressiveScrollBar` draw-phase driven; queue resolve off-main; bulk-replace threshold for queue ops.
- New `0aeec42` state is otherwise leaf-safe: `currentDownloadSortOption` rides the `distinctUntilChanged` projection; downloads sort result properly `remember`ed; DataStore flow read once with `.first()`; no eager/infinite badge animations; playlist lists keyed with remembered click lambdas at the item level.

---

## 2. Prioritization (impact on perceived smoothness ÷ risk of regression)

### Tier 1 — Quick, low-risk wins (safe to batch)
1. **S6** `derivedStateOf` wrappers for the 13 `canScroll*` reads (mechanical).
2. **S1** Throttle live-history timestamp refresh (5 s) — biggest constant win, zero behavior change (ordering preserved by `onSongChanged`'s immediate upsert).
3. **S9** Derive `visibleItemsInfo.lastOrNull()?.index` in the 2 prefetch snapshotFlows.
4. **S10** HashSet/map lookup for playlist row filter + stop double-wrapping remembered lambdas.
5. **S11** Precompute folder preview songs at tab level (no per-row tree walk / deep equals).
6. **S12** Drop `_$index` from cloud-song + search-playlist keys.
7. **S13** Cache `asStateFlow()` wrapper in `ThemeStateHolder`; remember Search `groupBy` + `onPlayClick`; remember `selectedAlbumIds`.
8. **S8** `snapshotFlow { isScrollInProgress }` inside `LaunchedEffect(Unit)` (2 screens).
9. **S17** Slice `playerUiState` collection in QueueBottomSheet.
10. **S5** Single `addMediaItems` batch for multi-add-to-queue.
11. **S24** Move Netease navigation after sheet-hide completes.
12. **S25** `String.CASE_INSENSITIVE_ORDER` comparators for downloads/playlist sorts (no per-compare allocations).
13. **S4** Equalizer flush off the main thread (IO scope).
14. **S14** ShimmerBox brush build in draw phase.
15. **S20 (slider)** WavyMusicSlider phase read in draw phase.
16. **S22** Narrow `currentOnlineProvider` read into a sheet-host scope.
17. **S23** NoBouncy enter spring for the Active badge (matches its own exit spec).
18. **S21 (easy parts)** `isBuffering` slice in `FullPlayerContent`.

### Tier 2 — Medium changes (one commit each, verified individually)
1. **S3** Build queue MediaItems on `Dispatchers.Default`, apply on Main.
2. **S2** Shorten theme cross-fade to a 300 ms tween (same visual crossfade, ~3× shorter window).
3. **S16** LyricsSheet migration to provider-lambda position + `derivedStateOf` current-line (leaf reads only).
4. **S7** Deferred `topBarHeight` reads on the 3 collapsing screens (port GenreDetail's own offset-lambda + fixed-padding pattern; remove the last composition reads there too).
5. **S15** SmartImage constraint-based default sizing (nullable `targetSize`), fix density-blind call sites, cap header decodes.
6. **S18** Remember queue fallback order/key lists + O(1) key→index map.
7. **S19** Swipe-dismiss offset in draw phase.
8. **S20 (overlay)** ExternalPlayerOverlay position provider.
9. **S27** Add `ScrollBenchmarks.kt` (FrameTimingMetric) covering Home/Library/Search/queue.
10. **S28** Extend BaselineProfileGenerator (folders, album/artist detail, liked/downloads).

### Tier 3 — Flagged, deliberately NOT implemented (proposal for maintainer)
- **S26** per-frame `snapTo` coroutine in `onPreScroll` (works; restructuring nested-scroll for marginal gain).
- Queue `Modifier.animateItem` idle fade specs (prior report C11): dropping them changes visible removal/slide-in animation — declined to keep behavior identical.
- Legacy V1 `UnifiedPlayerSheet` keeps full player composed when collapsed (V2 is default; only affects users who opt out of V2).
- Per-frame shape churn in `AnimatedPlaybackControls` corner morph (inherent to the expressive morph; alternatives change the visual).
- `resolveSongFromMediaItem` O(n²) fallback (background thread only).
- `miniAppearProgress.value` composition read (one-shot 260 ms appear).
- Wear module: separate UI, no reported jank; left untouched to avoid regression risk.

**Feature preservation:** no feature is removed or disabled; all integrations (Wear sync, Auto, Cast, widgets, QS tiles, backup format, tag editor, lyrics, equalizer, AI playlists, import, downloads) untouched. `THIRD_PARTY_NOTICES.md` and licenses unchanged.

---

# IMPLEMENTATION REPORT

Branch: `perf/ui-smoothness-pass` (12 commits, one concern each), based on the tip of
`fix/library-downloads-sort-playlists-filter-button-provider-ux` (0aeec42). Every change is
smoothness-scoped; no feature was removed, disabled or degraded, and no public behavior was
changed except things feeling smoother (two documented micro-exceptions: the provider Active
badge enter spring no longer overshoots, and the album-art theme cross-fade settles in 300 ms
instead of ~1 s — both animation nuance, same motion language).

## Verification

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | **127 tests, 17 failures — byte-identical failure set to the pre-change baseline** (same 17, same classes; verified against the documented baseline in PERFORMANCE_FINDINGS.md: 14× JVM `VerifyError` "Call to wrong \<init\> method" on PlayerViewModelTest nested classes — GMS/Cast classes under the JVM runner — + 3× NewPipeDownloader User-Agent/ISO-8859-1 env assertions). No test added, removed or weakened. |
| `:app:assembleDebug` | ✅ BUILD SUCCESSFUL — 5 APKs (4 ABI + universal) |
| `:wear:assembleDebug` | ✅ BUILD SUCCESSFUL — wear-debug.apk |
| `:shared:compileDebugKotlin` | ✅ BUILD SUCCESSFUL |
| `:baselineprofile:compileBenchmarkReleaseSources` | ✅ BUILD SUCCESSFUL |
| `:wear:testDebugUnitTest` | N/A — the wear module has no test source set (only `src/main`) |

⚠ Sandbox note: 4 GB RAM / 2 cores — builds ran with `-Dorg.gradle.jvmargs=-Xmx2048m` (project default `-Xmx6g` OOMs the container). No emulator/device available, so runtime numbers marked ⚑ below need the new `:baselineprofile` scroll benchmarks on a real device; the impact column states the measured-before behavior (from code) and the mechanism that removes it.

## What was changed (12 commits)

| # | Commit | Finding(s) | Expected impact ⚑ | Risk |
|---|--------|-------|-----------------|------|
| 1 | `docs: UI smoothness findings report` | — | evidence-first report (this file) | none |
| 2 | `perf(scroll): derivedStateOf for all 16 canScroll* composition reads` | S6 | **High.** `canScrollForward/Backward` are computed from `layoutInfo` — a State written on **every scroll frame** — so all 16 reads subscribed whole tab/screen scopes to per-frame invalidation: Songs/Folders/Liked/Downloads/Albums(list+grid)/Artists/Playlists tabs, CloudCatalog, Album/Playlist detail, Queue sheet, FileExplorer, SongPicker recomposed their entire body during **every fling**. Now only the boundary flip applies (required anyway — the inset padding changes). | Very low (padding values identical) |
| 3 | `perf: batch of low-risk smoothness wins (Tier 1 batch B)` + compile fixes | S1, S4, S5, S8, S9, S10, S11, S12, S13, S17, S22, S23, S24, S25 | **High (S1):** history re-emission 4 Hz → ≤0.2 Hz while playing (HomeScreen's full-library merge + `associateBy` + sort ran 4×/s on the main thread). Med: prefetch collect per scroll frame → per visible-index change; cloud-song key shifts removed; album-row Flow params stable (skipping restored + collectors not restarted); playlist-row filter O(library×playlistSize) → O(library) with O(1) membership; folder-row deep equals + tree walk gone; queue sheet no longer recomposes on unrelated PlayerUiState fields; 50-song add-to-queue = 1 IPC + 1 rebuild (was 50+50); equalizer exit no longer blocks the nav transition (10 sequential DataStore writes moved off-main); provider sheet switch no longer recomposes HomeScreen; Netease nav no longer overlaps sheet-dismiss; bouncy width oscillation removed. | Low (all semantics-preserving; ordering/visuals identical) |
| 4 | `perf(playback-start): build queue MediaItems on Dispatchers.Default` | S3 | **High** for large libraries: "play all" (5 000 songs) removed a 100–300 ms main-thread stall at the tap (5 000 × Bundle+MediaMetadata+Uri). Engine calls still on Main. | Low (pure computation moved; identical items/order) |
| 5 | `perf(theme-crossfade): 300ms tween` | S2 | **High.** The 72-color scheme animation fed a static CompositionLocal — every frame for ~1 s on each track change invalidated the entire mini player + full player + queue host. Window ~3× shorter (still a smooth M3-standard crossfade). Structural alternatives (animating only consumed colors / crossfading two static schemes) left as proposals. | Low-med (animation duration nuance only) |
| 6 | `perf(lyrics): provider-lambda position reads` | S16 | **High** while lyrics are open: whole-sheet + every visible row recomposed 4×/s with recreated derived states; now the list scope recomposes only when the active line changes and a row only when its own highlight flips. | Low (same tick source, same visuals) |
| 7 | `perf(scroll): deferred topBarHeight reads on the 3 collapsing-header screens` | S7 | **High** during header collapse: whole screen Box no longer recomposes per gesture frame; only the top bar (which must animate) + scrollbar leaf re-render. | Med (visual parity verified structurally: same snap targets, same boundary gates, same offset lambdas) |
| 8 | `perf(scroll): constraint-based SmartImage request sizing` | S15 | **Med-High.** Every default-size artwork request decoded `Size(300,300)` regardless of view (56 dp @3x ≈ 1.8× oversized); headers decoded 1600×1600 ≈ 10.2 MB bitmaps. Right-sized decodes → less bitmap memory, faster decode, less GC during fling. DailyMix (loose constraints) + AlbumCarousel (quality setting) keep explicit sizes. | Med (accepted: one-time disk-cache key change → images re-decode once; density-blind 168px rows now exact on all densities) |
| 9 | `perf(queue): reorder-bookkeeping + draw-phase swipe-dismiss + sliced external player` | S18, S19, S20 | Med: queue-sheet fallback lists no longer re-allocated per recomposition; swipe-dismiss rows no longer recompose/re-measure per swipe frame (draw/layout-phase reads + boundary Booleans; icon tweens that chased per-frame targets computed in draw phase); ExternalPlayerOverlay no longer recomposes at 4 Hz with per-tick String formatting. | Low-med (icon fade now tracks the finger 1:1 instead of via retargeted 120 ms tweens — visually equivalent) |
| 10 | `perf(benchmarks): scroll frame-timing benchmarks + baseline profile coverage` | S27, S28 | **Unblocks evidence**: `ScrollBenchmarks` (FrameTimingMetric) for Home/Songs/pager/Search; generator now exercises album/artist detail + folders so those hot paths land in the baseline profile. | None (test-only) |
| 11 | `fix: key derived states on their backing state objects` | hardening | Prevents stale captures if a backing Animatable/derived state is recreated. | none |

## Before/after evidence (static, per finding)

No device/emulator was available in this sandbox — the evidence below is the code-level
before/after that Macrobenchmark's new `ScrollBenchmarks` will quantify on a real device.

| Finding | Before (measured in code) | After |
|---|---|---|
| S6 canScroll reads | 16 composition reads of getters computed from per-frame-written `layoutInfo` → whole-tab recomposition on **every scroll frame** | `derivedStateOf` Boolean per site → scope invalidates only at list-top/bottom crossing |
| S1 history emission | `upsertPlaybackHistory` on every 250 ms tick (new 30-item list + entry; timestamp always differs) → 4 Hz re-merge of the entire library in HomeScreen | Refresh interval 5 s → ≤0.2 Hz; song changes still immediate |
| S3 queue build | 5 000-song "play all": 5 000 × (Bundle + MediaMetadata + Uri) on Main ≈ 100–300 ms stall at tap | Same map on `Dispatchers.Default`; Main only applies `setMediaItems/prepare/play` |
| S2 theme cross-fade | ~60 frames/s × ~1 s of full mini-player+full-player+queue-host invalidation per track change (spring StiffnessLow settle) | ~18 frames × 300 ms (tween), then idle |
| S16 lyrics | Sheet scope + N visible rows × 4 recompositions/s + N derived-state object allocations per tick | List scope recomposes on active-line change only; rows on own-highlight flip; zero per-tick allocations |
| S15 image sizing | Every row artwork: 300 px fixed decode (~1.8× @3x oversize); headers 1600² ≈ 10.2 MB | Constraint-sized decode (exact displayed px); headers ≤ screen size |
| S19 swipe dismiss | Whole queue row recomposed + reveal Box re-measured per swipe frame | 0 recompositions during swipe; translation/width/alpha in draw/layout phase; 2 Boolean flips per swipe |
| S5 batch queue add | 50 songs = 50 binder IPCs + 50 full O(n) queue rebuilds | 1 IPC + 1 rebuild |
| S4 equalizer exit | `runBlocking` 10 sequential DataStore writes on Main during the nav transition | Off-main flush (values still persisted; only at risk if the process dies within ~100 ms of leaving the screen) |

## Needs manual verification on a real device (cannot be confirmed statically)

- Fling-scroll feel on Library tabs (Songs/Folders/Liked/Downloads/Albums/Artists/Playlists), Search results, CloudCatalog — expect visibly fewer dropped frames; confirm with `ScrollBenchmarks` (frame 95th/99th percentile + jank %) before/after.
- Collapsing-header gestures on Album/Artist/Genre/CloudCatalog detail — collapse/expand + snap should feel the same but smoother; verify the scrollbar appears at the same collapse point and tracks the header.
- Track-change visual: album-art theme cross-fade still reads as a smooth blend (now 300 ms); player sheet does not stutter on song transition.
- Lyrics sheet: line highlighting + karaoke word timing identical; auto-scroll behavior unchanged.
- Queue sheet: swipe-to-dismiss reveal/trash icon feel; drag-reorder placement animations; undo bar appearance.
- External player overlay (Android Auto companion / external screens): slider + time label still track playback correctly.
- SmartImage visuals: artwork sharpness across densities (esp. @2x and @3.5+ devices where sizing actually changed), crossfade/placeholder states, album/artist detail header art quality.
- Equalizer: values persist after leaving the screen (and after a normal app close).
- Wear OS / Cast / widgets / QS tile / backup-restore: untouched code paths — smoke-check only.

## Explicitly NOT done (proposals for maintainer sign-off — Tier 3)

1. **S2 structural variant**: animate only the ~10 colors the player subtree actually consumes, or cross-fade two static schemes via `graphicsLayer { alpha }` — removes the remaining 300 ms per-frame scheme invalidation entirely.
2. Queue `Modifier.animateItem` idle fade specs (S-mapped from prior C11): dropping them changes visible removal/slide-in animation — declined to keep behavior identical.
3. Legacy V1 `UnifiedPlayerSheet` keeps the full player composed when collapsed (V2 is the default; affects opt-out users only).
4. Per-frame `snapTo` coroutine in `onPreScroll` (S26) — works correctly; restructuring nested-scroll for marginal gain.
5. Per-frame shape churn in `AnimatedPlaybackControls` corner morph — inherent to the expressive morph.
6. `resolveSongFromMediaItem` O(n²) fallback — background thread only.
7. `miniAppearProgress.value` composition read (one-shot 260 ms appear).
8. Wear module — no reported jank; left untouched to avoid regression risk.
9. CloudCatalog baseline-profile coverage — needs network on the profiling device; intentionally excluded from the generator.
