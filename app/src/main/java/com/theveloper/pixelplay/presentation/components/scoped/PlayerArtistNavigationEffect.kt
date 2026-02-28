package com.theveloper.pixelplay.presentation.components.scoped

import com.theveloper.pixelplay.presentation.navigation.navigateSafely

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun PlayerArtistNavigationEffect(
    navController: NavHostController,
    sheetCollapsedTargetY: Float,
    sheetMotionController: SheetMotionController,
    playerViewModel: PlayerViewModel
) {
    LaunchedEffect(navController, sheetCollapsedTargetY) {
        playerViewModel.artistNavigationRequests.collectLatest { artistId ->
            sheetMotionController.snapCollapsed(sheetCollapsedTargetY)
            playerViewModel.collapsePlayerSheet()

            navController.navigateSafely(Screen.ArtistDetail.createRoute(artistId)) {
                launchSingleTop = true
            }
        }
    }
}
