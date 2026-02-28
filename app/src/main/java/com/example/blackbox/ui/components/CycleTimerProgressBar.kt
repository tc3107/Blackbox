package com.example.blackbox.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay

private val TimerTrailMinLength: Dp = 14.dp
private const val TIMER_ACTIVITY_PULSE_DELAY_MS = 250L
private const val TIMER_ACTIVITY_PULSE_DURATION_MS = 700

@Composable
fun CycleTimerProgressBar(
    totalMs: Long,
    remainingMs: Long,
    sampleNowMs: Long,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    pulse: Boolean = false,
    onTap: (() -> Unit)? = null
) {
    val progress = rememberFrameSyncedProgress(
        totalMs = totalMs,
        remainingMs = remainingMs,
        sampleNowMs = sampleNowMs,
        isActive = isActive
    )

    val drawAsActive = isActive
    val outlineColor = if (drawAsActive) {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.60f)
    val fillColor = if (drawAsActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f)
    }
    val shape = RoundedCornerShape(7.dp)
    val tapModifier = if (onTap != null) {
        Modifier.clickable(onClick = onTap)
    } else {
        Modifier
    }
    val pulseAlpha = rememberTimerActivityPulseAlpha(isPulsing = pulse)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(12.dp)
            .alpha(pulseAlpha)
            .clip(shape)
            .border(width = 1.5.dp, color = outlineColor, shape = shape)
            .background(trackColor)
            .then(tapModifier)
            .padding(1.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val clampedProgress = progress.coerceIn(0f, 1f)
            val segmentRight = (size.width * clampedProgress).coerceIn(0f, size.width)
            if (segmentRight <= 0f) return@Canvas

            val segmentLengthPx = maxOf(size.width * 0.22f, TimerTrailMinLength.toPx())
            val segmentLeft = (segmentRight - segmentLengthPx).coerceAtLeast(0f)
            val segmentWidth = (segmentRight - segmentLeft).coerceAtLeast(0f)
            if (segmentWidth <= 0f) return@Canvas

            val segmentEndAlpha = if (drawAsActive) 0.58f else 0.30f
            drawRect(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to fillColor.copy(alpha = 0f),
                        1f to fillColor.copy(alpha = segmentEndAlpha)
                    ),
                    startX = segmentLeft,
                    endX = segmentRight
                ),
                topLeft = androidx.compose.ui.geometry.Offset(segmentLeft, 0f),
                size = Size(segmentWidth, size.height)
            )
        }
    }
}

@Composable
fun rememberTimerActivityPulseActive(
    isInProgress: Boolean,
    delayMs: Long = TIMER_ACTIVITY_PULSE_DELAY_MS
): Boolean {
    var pulseActive by remember { mutableStateOf(false) }
    LaunchedEffect(isInProgress, delayMs) {
        if (!isInProgress) {
            pulseActive = false
            return@LaunchedEffect
        }
        pulseActive = false
        delay(delayMs)
        pulseActive = true
    }
    return pulseActive
}

@Composable
fun rememberTimerActivityPulseAlpha(isPulsing: Boolean): Float {
    if (!isPulsing) return 1f
    val transition = rememberInfiniteTransition(label = "timerActivityPulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.40f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = TIMER_ACTIVITY_PULSE_DURATION_MS),
            repeatMode = RepeatMode.Reverse
        ),
        label = "timerActivityPulseAlpha"
    )
    return alpha
}

@Composable
private fun rememberFrameSyncedProgress(
    totalMs: Long,
    remainingMs: Long,
    sampleNowMs: Long,
    isActive: Boolean
): Float {
    if (totalMs <= 0L) return 1f
    val clampedRemaining = remainingMs.coerceIn(0L, totalMs)
    val baseElapsedMs = (totalMs - clampedRemaining).coerceIn(0L, totalMs)
    var frameElapsedMs by remember(totalMs, baseElapsedMs, sampleNowMs, isActive) {
        mutableLongStateOf(0L)
    }

    LaunchedEffect(totalMs, baseElapsedMs, sampleNowMs, isActive) {
        frameElapsedMs = 0L
        if (!isActive || clampedRemaining <= 0L || clampedRemaining >= totalMs) return@LaunchedEffect
        val startNanos = withFrameNanos { it }
        val maxExtraMs = totalMs - baseElapsedMs
        while (true) {
            val nowNanos = withFrameNanos { it }
            val elapsedMs = ((nowNanos - startNanos) / 1_000_000L).coerceAtLeast(0L)
            frameElapsedMs = elapsedMs.coerceAtMost(maxExtraMs)
            if (frameElapsedMs >= maxExtraMs) break
        }
    }

    val totalElapsedMs = if (isActive) {
        (baseElapsedMs + frameElapsedMs).coerceIn(0L, totalMs)
    } else {
        baseElapsedMs
    }
    return totalElapsedMs.toFloat() / totalMs.toFloat()
}
