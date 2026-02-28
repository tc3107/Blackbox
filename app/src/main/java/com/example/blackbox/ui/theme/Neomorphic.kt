package com.example.blackbox.ui.theme

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val ENABLE_NEOMORPHIC_EFFECTS = true
private const val ENABLE_NEOMORPHIC_BLUR_SHADOWS = true
private const val NEO_PRESS_ANIM_MS = 210

@Immutable
data class NeomorphicPalette(
    val surface: Color,
    val surfaceVariant: Color,
    val lightShadow: Color,
    val darkShadow: Color,
    val stroke: Color
)

@Composable
fun neomorphicPalette(): NeomorphicPalette {
    val scheme = MaterialTheme.colorScheme
    val surface = scheme.surface
    return NeomorphicPalette(
        surface = surface,
        surfaceVariant = scheme.surfaceVariant,
        lightShadow = lerp(surface, Color.White, 0.30f),
        darkShadow = lerp(surface, Color.Black, 0.62f),
        stroke = lerp(surface, Color.White, 0.14f)
    )
}

fun Modifier.neomorphicShadow(
    shape: Shape,
    enabled: Boolean = true,
    pressed: Boolean = false,
    addBorder: Boolean = true,
    depth: Dp = 2.dp,
    blurRadius: Dp = 4.dp
): Modifier = composed {
    if (!enabled || !ENABLE_NEOMORPHIC_EFFECTS) {
        return@composed this
    }

    val palette = neomorphicPalette()
    val pressProgress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(durationMillis = NEO_PRESS_ANIM_MS, easing = FastOutSlowInEasing),
        label = "neoPressProgress"
    )
    this.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = outline.toPath()
        val rimWidthPx = 1.dp.toPx()

        val topLeftColor = palette.lightShadow.copy(alpha = 0.92f)
        val bottomRightColor = palette.darkShadow.copy(alpha = 0.74f)

        val rimBrush = Brush.linearGradient(
            colors = listOf(topLeftColor, bottomRightColor),
            start = Offset.Zero,
            end = Offset(size.width, size.height)
        )
        if (!ENABLE_NEOMORPHIC_BLUR_SHADOWS) {
            return@drawWithCache onDrawWithContent {
                drawContent()
                if (addBorder) {
                    drawPath(path = path, brush = rimBrush, style = Stroke(width = rimWidthPx))
                }
            }
        }

        val blurPx = blurRadius.toPx()
        val offsetPx = depth.toPx()
        val topLeftOffset = Offset(
            x = -offsetPx + (2f * offsetPx * pressProgress),
            y = -offsetPx + (2f * offsetPx * pressProgress)
        )
        val bottomRightOffset = Offset(
            x = offsetPx - (2f * offsetPx * pressProgress),
            y = offsetPx - (2f * offsetPx * pressProgress)
        )
        val topLeftShadowPaint = Paint().apply {
            asFrameworkPaint().maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
        }
        val bottomRightShadowPaint = Paint().apply {
            asFrameworkPaint().maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
        }

        onDrawWithContent {
            topLeftShadowPaint.color = topLeftColor
            bottomRightShadowPaint.color = bottomRightColor

            // Raised -> pressed transitions now animate both color and shadow position.
            drawIntoCanvas { canvas ->
                canvas.save()
                canvas.translate(topLeftOffset.x, topLeftOffset.y)
                canvas.drawPath(path, topLeftShadowPaint)
                canvas.restore()

                canvas.save()
                canvas.translate(bottomRightOffset.x, bottomRightOffset.y)
                canvas.drawPath(path, bottomRightShadowPaint)
                canvas.restore()
            }

            drawContent()

            if (addBorder) {
                drawPath(path = path, brush = rimBrush, style = Stroke(width = rimWidthPx))
            }
        }
    }
}

private fun Outline.toPath(): Path {
    return when (this) {
        is Outline.Generic -> path
        is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
        is Outline.Rectangle -> Path().apply { addRect(rect) }
    }
}
