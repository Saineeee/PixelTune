package com.saine.pixeltune.presentation.components.subcomps

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Composable que dibuja una línea horizontal con ondulación senoidal.
 *
 * @param modifier Modificador para el Composable.
 * @param color Color de la línea.
 * @param alpha Opacidad (0f..1f).
 * @param strokeWidth Grosor de la línea (Dp).
 * @param amplitude Amplitud de la onda (Dp) — la altura máxima desde el centro.
 * @param waves Número de ondas completas a lo largo del ancho (ej: 1f = una onda).
 * @param phase Desplazamiento de fase estático (radianes). Se usa solo si animate = false.
 * @param animate Si es true, activa una animación de desplazamiento infinita.
 * @param animationDurationMillis Duración en milisegundos de un ciclo completo de animación.
 * @param samples Cantidad de puntos usados para dibujar la curva (más = más suave).
 * @param cap Tipo de extremo de la línea (Round, Butt, Square).
 */
@Composable
fun SineWaveLine(
    modifier: Modifier = Modifier,
    color: Color = Color.Black,
    alpha: Float = 1f,
    strokeWidth: Dp = 2.dp,
    amplitude: Dp = 8.dp,
    waves: Float = 2f,
    phase: Float = 0f,
    animate: Boolean? = false,
    animationDurationMillis: Int = 2000,
    samples: Int = 400,
    cap: StrokeCap = StrokeCap.Round
) {
    // PERF(smoothness): the animated phase used to be read in COMPOSITION
    // (`by` delegation) and captured as a plain Float by the draw lambda —
    // every frame of the infinite transition recomposed the composable, and
    // a fresh 400-segment Path was allocated per frame. The state is now
    // read inside the draw lambda (redraw-only, PlayingEqIcon's own pattern)
    // and the Path is reused (rewound + rebuilt).
    //
    // The static branch (animate != true) no longer creates an infinite
    // transition at all — it used to keep a 60 Hz frame loop running that
    // drew nothing (PlaylistContainer uses the static form).
    if (animate == true) {
        val infiniteTransition = rememberInfiniteTransition(label = "SineWaveAnimation")
        val animatedPhase = infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2f * PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = animationDurationMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "phaseAnimation"
        )
        val reusablePath = remember { Path() }
        Canvas(modifier = modifier) {
            drawSineWave(
                path = reusablePath,
                phase = animatedPhase.value,
                color = color,
                alpha = alpha,
                strokeWidth = strokeWidth,
                amplitude = amplitude,
                waves = waves,
                samples = samples,
                cap = cap
            )
        }
    } else {
        val reusablePath = remember { Path() }
        Canvas(modifier = modifier) {
            drawSineWave(
                path = reusablePath,
                phase = phase,
                color = color,
                alpha = alpha,
                strokeWidth = strokeWidth,
                amplitude = amplitude,
                waves = waves,
                samples = samples,
                cap = cap
            )
        }
    }
}

/** Shared geometry: builds (rewinding + refilling the reused path) and strokes the sine curve. */
private fun DrawScope.drawSineWave(
    path: Path,
    phase: Float,
    color: Color,
    alpha: Float,
    strokeWidth: Dp,
    amplitude: Dp,
    waves: Float,
    samples: Int,
    cap: StrokeCap
) {
    val w = size.width
    val h = size.height
    val centerY = h / 2f

    // Convertimos dp a px dentro del scope de dibujo para eficiencia
    // (DrawScope is itself a Density)
    val strokePx = strokeWidth.toPx()
    val ampPx = amplitude.toPx()

    if (w <= 0f || samples < 2) return

    // Construimos el path senoidal usando la fase actual (animada o estática)
    path.rewind()
    val step = w / (samples - 1)
    // Usamos la fase recibida para el punto inicial
    path.moveTo(0f, centerY + (ampPx * sin(phase)))
    for (i in 1 until samples) {
        val x = i * step
        // theta recorre 0..(2π * waves)
        val theta = (x / w) * (2f * PI.toFloat() * waves) + phase
        val y = centerY + ampPx * sin(theta)
        path.lineTo(x, y)
    }

    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = strokePx,
            cap = cap,
            join = StrokeJoin.Round
        ),
        alpha = alpha
    )
}

/**
 * Ejemplo de uso estático:
 *
 * SineWaveLine(
 *     modifier = Modifier
 *         .fillMaxWidth()
 *         .height(28.dp),
 *     color = Color(0xFF00AEEF),
 *     alpha = 0.95f,
 *     strokeWidth = 3.dp,
 *     amplitude = 10.dp,
 *     waves = 1.6f
 * )
 */
