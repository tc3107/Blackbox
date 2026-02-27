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
        lightShadow = lerp(surface, Color.White, 0.22f),
        darkShadow = lerp(surface, Color.Black, 0.52f),
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
    if (!enabled) {
        return@composed this
    }

    val palette = neomorphicPalette()
    val pressProgress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "neoPressProgress"
    )
    this.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = outline.toPath()
        val blurPx = blurRadius.toPx()
        val offsetPx = depth.toPx()
        val rimWidthPx = 1.dp.toPx()

        val outerLight = palette.lightShadow.copy(alpha = 0.82f)
        val outerDark = palette.darkShadow.copy(alpha = 0.62f)
        val pressedLight = palette.lightShadow.copy(alpha = 0.72f)
        val pressedDark = palette.darkShadow.copy(alpha = 0.70f)
        val topLeftColor = lerp(outerLight, pressedDark, pressProgress)
        val bottomRightColor = lerp(outerDark, pressedLight, pressProgress)

        val rimBrush = Brush.linearGradient(
            colors = listOf(topLeftColor, bottomRightColor),
            start = Offset.Zero,
            end = Offset(size.width, size.height)
        )

        onDrawWithContent {
            // Raised state: light top-left + dark bottom-right.
            // Pressed state: same positions, swapped colors.
            drawBlurredPath(
                path = path,
                color = topLeftColor,
                blurPx = blurPx,
                offset = Offset(-offsetPx, -offsetPx)
            )
            drawBlurredPath(
                path = path,
                color = bottomRightColor,
                blurPx = blurPx,
                offset = Offset(offsetPx, offsetPx)
            )

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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBlurredPath(
    path: Path,
    color: Color,
    blurPx: Float,
    offset: Offset
) {
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            this.color = color
            asFrameworkPaint().maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.save()
        canvas.translate(offset.x, offset.y)
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}
