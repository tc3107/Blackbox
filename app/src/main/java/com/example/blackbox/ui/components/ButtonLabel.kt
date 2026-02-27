package com.example.blackbox.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
fun ButtonLabel(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelLarge
) {
    Crossfade(
        targetState = text,
        animationSpec = tween(durationMillis = 140),
        label = "buttonLabelCrossfade"
    ) { label ->
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            val textMeasurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val maxFontSize = if (style.fontSize.isSpecified) style.fontSize else 16.sp
            val minFontSize = 8.sp
            val maxWidthPx = with(density) { maxWidth.roundToPx() }
            val (resolvedFontSize, forceTwoLines) = remember(label, style, maxWidthPx, density.fontScale) {
                if (maxWidthPx <= 0) {
                    maxFontSize to false
                } else {
                    val minSingleLine = textMeasurer.measure(
                        text = AnnotatedString(label),
                        style = style.copy(fontSize = minFontSize),
                        maxLines = 1,
                        softWrap = false
                    )
                    if (minSingleLine.size.width > maxWidthPx) {
                        minFontSize to true
                    } else {
                    var low = minFontSize.value
                    var high = maxFontSize.value
                    var best = minFontSize.value
                    repeat(10) {
                        val mid = (low + high) / 2f
                        val measured = textMeasurer.measure(
                            text = AnnotatedString(label),
                            style = style.copy(fontSize = mid.sp),
                            maxLines = 1,
                            softWrap = false
                        )
                        if (measured.size.width <= maxWidthPx) {
                            best = mid
                            low = mid
                        } else {
                            high = mid
                        }
                        if (abs(high - low) < 0.1f) return@repeat
                    }
                        best.sp to false
                    }
                }
            }
            Text(
                text = label,
                modifier = Modifier.fillMaxWidth(),
                maxLines = if (forceTwoLines) 2 else 1,
                softWrap = forceTwoLines,
                overflow = if (forceTwoLines) TextOverflow.Clip else TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = style.copy(fontSize = resolvedFontSize)
            )
        }
    }
}
