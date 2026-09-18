package com.theveloper.pixeltune.presentation.components

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size // Import Coil's Size
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.theveloper.pixeltune.R

@Composable
fun SmartImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholderResId: Int = R.drawable.ic_music_placeholder,
    errorResId: Int = R.drawable.ic_music_placeholder,
    shape: Shape = RectangleShape,
    contentScale: ContentScale = ContentScale.Crop,
    crossfadeDurationMillis: Int = 300,
    useDiskCache: Boolean = true,
    useMemoryCache: Boolean = true,
    allowHardware: Boolean = true,
    targetSize: Size = Size(300, 300),
    colorFilter: ColorFilter? = null,
    alpha: Float = 1f,
    placeholderModel: Any? = null,
    onState: ((AsyncImagePainter.State) -> Unit)? = null
) {
    val context = LocalContext.current
    val clippedModifier = modifier.clip(shape)

    @Suppress("NAME_SHADOWING")
    val model = when (model) {
        is ImageRequest -> handleDirectModel(
            data = model.data,
            modifier = clippedModifier,
            contentDescription = contentDescription,
            contentScale = contentScale,
            colorFilter = colorFilter,
            alpha = alpha
        ) ?: model
        else -> handleDirectModel(
            data = model,
            modifier = clippedModifier,
            contentDescription = contentDescription,
            contentScale = contentScale,
            colorFilter = colorFilter,
            alpha = alpha
        ) ?: model
    }

    if (model is ImageVector || model is Painter || model is ImageBitmap || model is Bitmap) {
        // Already rendered inside handleDirectModel.
        return
    }

    val request = remember(
        context,
        model,
        crossfadeDurationMillis,
        useDiskCache,
        useMemoryCache,
        allowHardware,
        targetSize
    ) {
        when (model) {
            is ImageRequest -> model
            else -> ImageRequest.Builder(context)
                .data(model)
                .crossfade(crossfadeDurationMillis)
                .diskCachePolicy(if (useDiskCache) CachePolicy.ENABLED else CachePolicy.DISABLED)
                .memoryCachePolicy(if (useMemoryCache) CachePolicy.ENABLED else CachePolicy.DISABLED)
                .allowHardware(allowHardware)
                .apply {
                    size(targetSize)
                }
                .build()
        }
    }

    // PERF(scroll): the common path renders with a plain AsyncImage.
    //
    // This component is the artwork slot of essentially every list row in the
    // app (51 call sites). It used SubcomposeAsyncImage — Coil's most expensive
    // loading path: every image, including the steady-state SUCCESS state,
    // lived inside its own subcomposition (extra slot table + measure/layout
    // passes per row). Plain AsyncImage renders the same request with a single
    // Image node.
    //
    // The loading/error placeholder visuals are reproduced exactly (colored
    // box + centered 32 dp tinted icon) via [AlbumArtPlaceholderPainter].
    //
    // The placeholderModel branch (low-res thumbnail behind Telegram/cloud
    // art) still needs a composable loading slot, so it keeps the old
    // subcompose implementation — it has only two call sites, neither in a
    // fast-scrolling list.
    if (placeholderModel != null) {
        SmartImageWithPlaceholderModel(
            request = request,
            placeholderModel = placeholderModel,
            contentDescription = contentDescription,
            clippedModifier = clippedModifier,
            contentScale = contentScale,
            colorFilter = colorFilter,
            alpha = alpha,
            placeholderResId = placeholderResId,
            errorResId = errorResId,
            onState = onState
        )
        return
    }

    val containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
    val iconColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val iconSizePx = with(density) { 32.dp.toPx() }

    val placeholderIconPainter = painterResource(placeholderResId)
    val errorIconPainter = painterResource(errorResId)

    val placeholderPainter = remember(
        placeholderIconPainter, containerColor, iconColor, iconSizePx, alpha
    ) {
        AlbumArtPlaceholderPainter(
            delegate = placeholderIconPainter,
            containerColor = containerColor,
            iconTint = ColorFilter.tint(iconColor),
            iconSizePx = iconSizePx,
            alphaFactor = alpha
        )
    }
    val errorPainter = remember(
        errorIconPainter, containerColor, iconColor, iconSizePx, alpha
    ) {
        AlbumArtPlaceholderPainter(
            delegate = errorIconPainter,
            containerColor = containerColor,
            iconTint = ColorFilter.tint(iconColor),
            iconSizePx = iconSizePx,
            alphaFactor = alpha
        )
    }

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = clippedModifier,
        placeholder = placeholderPainter,
        error = errorPainter,
        // Empty (null) model previously rendered the *loading* placeholder.
        fallback = placeholderPainter,
        onLoading = { onState?.invoke(it) },
        onSuccess = { onState?.invoke(it) },
        onError = { onState?.invoke(it) },
        contentScale = contentScale,
        alpha = alpha,
        colorFilter = colorFilter
    )
}

/**
 * Rare variant used when a low-res [placeholderModel] (e.g. a Telegram
 * thumbnail) should be shown while the full artwork loads. Preserved verbatim
 * from the old subcompose implementation — see the note in [SmartImage].
 */
@Composable
private fun SmartImageWithPlaceholderModel(
    request: ImageRequest,
    placeholderModel: Any?,
    contentDescription: String?,
    clippedModifier: Modifier,
    contentScale: ContentScale,
    colorFilter: ColorFilter?,
    alpha: Float,
    @DrawableRes placeholderResId: Int,
    @DrawableRes errorResId: Int,
    onState: ((AsyncImagePainter.State) -> Unit)?
) {
    SubcomposeAsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = clippedModifier,
        contentScale = contentScale,
        colorFilter = colorFilter,
        alpha = alpha
    ) {
        val state = painter.state

        LaunchedEffect(state) {
            onState?.invoke(state)
        }

        var lastSuccessPainter by remember(request.data) { mutableStateOf<Painter?>(null) }

        when (state) {
            is AsyncImagePainter.State.Success -> {
                lastSuccessPainter = state.painter
                SubcomposeAsyncImageContent()
            }
            AsyncImagePainter.State.Empty,
            is AsyncImagePainter.State.Loading -> {
                val cachedPainter = lastSuccessPainter
                if (cachedPainter != null) {
                    Image(
                        painter = cachedPainter,
                        contentDescription = contentDescription,
                        modifier = Modifier
                            .fillMaxSize(),
                        contentScale = contentScale,
                        colorFilter = colorFilter,
                        alpha = alpha
                    )
                } else if (placeholderModel != null) {
                    // Render placeholder model (e.g. low-res thumbnail)
                     SubcomposeAsyncImage(
                        model = placeholderModel,
                        contentDescription = null, // Decorative placeholder
                        modifier = Modifier.fillMaxSize(),
                        contentScale = contentScale,
                        colorFilter = colorFilter,
                        alpha = alpha,
                        error = {
                            Placeholder(
                                modifier = Modifier.fillMaxSize(),
                                drawableResId = placeholderResId,
                                contentDescription = contentDescription,
                                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
                                iconColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                alpha = alpha
                            )
                        }
                    )
                } else {
                    Placeholder(
                        modifier = Modifier.fillMaxSize(),
                        drawableResId = placeholderResId,
                        contentDescription = contentDescription,
                        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
                        iconColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        alpha = alpha
                    )
                }
            }
            is AsyncImagePainter.State.Error -> {
                val cachedPainter = lastSuccessPainter
                if (cachedPainter != null) {
                    Image(
                        painter = cachedPainter,
                        contentDescription = contentDescription,
                        modifier = Modifier
                            .fillMaxSize(),
                        contentScale = contentScale,
                        colorFilter = colorFilter,
                        alpha = alpha
                    )
                } else {
                    Placeholder(
                        modifier = Modifier.fillMaxSize(),
                        drawableResId = errorResId,
                        contentDescription = contentDescription,
                        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
                        iconColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        alpha = alpha
                    )
                }
            }
        }
    }
}

/**
 * Reproduces the visual of the old `Placeholder` composable (a
 * surfaceContainerHigh box with a centered 32 dp tinted icon) as a single
 * [Painter], so the hot path can use plain [AsyncImage] instead of
 * subcomposition.
 */
private class AlbumArtPlaceholderPainter(
    private val delegate: Painter,
    private val containerColor: Color,
    private val iconTint: ColorFilter,
    private val iconSizePx: Float,
    private val alphaFactor: Float
) : Painter() {
    // NOTE: this is androidx.compose.ui.geometry.Size (Painter's contract),
    // NOT coil.size.Size used for request target sizes.
    override val intrinsicSize: androidx.compose.ui.geometry.Size
        get() = delegate.intrinsicSize

    override fun DrawScope.onDraw() {
        drawRect(color = containerColor, alpha = alphaFactor)
        val intrinsic = delegate.intrinsicSize
        if (intrinsic.width > 0f && intrinsic.height > 0f && iconSizePx > 0f) {
            val scale = iconSizePx / maxOf(intrinsic.width, intrinsic.height)
            val w = intrinsic.width * scale
            val h = intrinsic.height * scale
            translate(left = (size.width - w) / 2f, top = (size.height - h) / 2f) {
                // Painter.draw is a member-extension (fun DrawScope.draw declared
                // inside Painter) — the dispatch receiver must be implicit via
                // `with`, the DrawScope receiver comes from this scope.
                with(delegate) {
                    draw(
                        size = androidx.compose.ui.geometry.Size(w, h),
                        alpha = alphaFactor,
                        colorFilter = iconTint
                    )
                }
            }
        }
    }
}

@Composable
private fun handleDirectModel(
    data: Any?,
    modifier: Modifier,
    contentDescription: String?,
    contentScale: ContentScale,
    colorFilter: ColorFilter?,
    alpha: Float
): Any? {
    return when (data) {
        is ImageVector -> {
            Image(
                imageVector = data,
                contentDescription = contentDescription,
                modifier = modifier,
                contentScale = contentScale,
                colorFilter = colorFilter,
                alpha = alpha
            )
            data
        }
        is Painter -> {
            Image(
                painter = data,
                contentDescription = contentDescription,
                modifier = modifier,
                contentScale = contentScale,
                colorFilter = colorFilter,
                alpha = alpha
            )
            data
        }
        is ImageBitmap -> {
            Image(
                bitmap = data,
                contentDescription = contentDescription,
                modifier = modifier,
                contentScale = contentScale,
                colorFilter = colorFilter,
                alpha = alpha
            )
            data
        }
        is Bitmap -> {
            Image(
                bitmap = data.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = modifier,
                contentScale = contentScale,
                colorFilter = colorFilter,
                alpha = alpha
            )
            data
        }
        else -> null
    }
}

@Composable
private fun Placeholder(
    modifier: Modifier,
    @DrawableRes drawableResId: Int,
    contentDescription: String?,
    containerColor: Color,
    iconColor: Color,
    alpha: Float,
) {
    Box(
        modifier = modifier
            .alpha(alpha)
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(drawableResId),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(iconColor),
            modifier = Modifier.size(32.dp),
            contentScale = ContentScale.Fit
        )
    }
}
