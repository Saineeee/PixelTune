package com.theveloper.pixeltune.presentation.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember

/**
 * PERF(scroll): `LazyListState.canScrollForward/Backward` are computed from
 * `layoutInfo`, a snapshot State that is *written on every scroll frame*.
 * Reading them directly in composition therefore subscribes the whole
 * enclosing scope to every scroll frame — the entire tab body recomposes
 * while fling-scrolling (the canonical Compose performance anti-pattern).
 *
 * Wrapping the read in [derivedStateOf] collapses those writes to a Boolean
 * that only changes when the list actually crosses its top/bottom edge, so
 * the containing scope recomposes at most twice per boundary crossing
 * (which is required anyway, because the inset padding value flips).
 *
 * Usage:
 * ```
 * end = if (rememberCanScrollMore(listState)) 22.dp else 12.dp
 * ```
 */
@Composable
fun rememberCanScrollMore(state: LazyListState): Boolean {
    val derived: State<Boolean> = remember(state) {
        derivedStateOf { state.canScrollForward || state.canScrollBackward }
    }
    return derived.value
}

/** [rememberCanScrollMore] variant for lazy grids. */
@Composable
fun rememberCanScrollMore(state: LazyGridState): Boolean {
    val derived: State<Boolean> = remember(state) {
        derivedStateOf { state.canScrollForward || state.canScrollBackward }
    }
    return derived.value
}
