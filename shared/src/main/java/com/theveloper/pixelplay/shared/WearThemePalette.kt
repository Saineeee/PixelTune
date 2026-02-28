package com.theveloper.pixelplay.shared

import kotlinx.serialization.Serializable

/**
 * Compact palette snapshot sent from phone to watch so Wear UI can render
 * with the same color intent used on phone.
 *
 * Values are ARGB ints (some may include alpha).
 */
@Serializable
data class WearThemePalette(
    val gradientTopArgb: Int,
    val gradientMiddleArgb: Int,
    val gradientBottomArgb: Int,
    val textPrimaryArgb: Int,
    val textSecondaryArgb: Int,
    val textErrorArgb: Int,
    val controlContainerArgb: Int,
    val controlContentArgb: Int,
    val controlDisabledContainerArgb: Int,
    val controlDisabledContentArgb: Int,
    val chipContainerArgb: Int,
    val chipContentArgb: Int,
    val favoriteActiveArgb: Int,
    val shuffleActiveArgb: Int,
    val repeatActiveArgb: Int,
)
