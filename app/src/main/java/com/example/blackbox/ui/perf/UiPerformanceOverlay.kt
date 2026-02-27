package com.example.blackbox.ui.perf

import com.example.blackbox.debug.MainThreadBlockTracker
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val FRAME_JANK_THRESHOLD_NS = 16_666_667L
private const val MAX_FRAMES_BUFFER = 300

data class UiPerfSectionSnapshot(
    val name: String,
    val score: Double,
    val recompositions: Int,
    val composeAvgMs: Double,
    val composeMaxMs: Double,
    val drawAvgMs: Double,
    val drawMaxMs: Double
)

data class UiPerformanceSnapshot(
    val elapsedMs: Long,
    val estimatedFps: Double,
    val jankPercent: Double,
    val sections: List<UiPerfSectionSnapshot>
)

data class UiPerformanceTrackingContext(
    val monitor: UiPerformanceMonitor,
    val enabled: Boolean
)

val LocalUiPerformanceTracking = compositionLocalOf<UiPerformanceTrackingContext?> { null }

class UiPerformanceMonitor {
    private data class SectionMutableStats(
        var recompositions: Int = 0,
        var composeSamples: Int = 0,
        var composeTotalNs: Long = 0L,
        var composeMaxNs: Long = 0L,
        var drawSamples: Int = 0,
        var drawTotalNs: Long = 0L,
        var drawMaxNs: Long = 0L
    )

    private val lock = Any()
    private val sectionStats = linkedMapOf<String, SectionMutableStats>()
    private val frameDurationsNs = ArrayDeque<Long>()
    private var sessionStartNs = System.nanoTime()

    fun reset() {
        synchronized(lock) {
            sectionStats.clear()
            frameDurationsNs.clear()
            sessionStartNs = System.nanoTime()
        }
    }

    fun recordFrameDuration(durationNs: Long) {
        if (durationNs <= 0L) return
        synchronized(lock) {
            if (frameDurationsNs.size >= MAX_FRAMES_BUFFER) {
                frameDurationsNs.removeFirst()
            }
            frameDurationsNs.addLast(durationNs)
        }
    }

    fun recordSectionComposition(name: String, durationNs: Long) {
        if (durationNs <= 0L) return
        synchronized(lock) {
            val stats = sectionStats.getOrPut(name) { SectionMutableStats() }
            stats.recompositions += 1
            stats.composeSamples += 1
            stats.composeTotalNs += durationNs
            if (durationNs > stats.composeMaxNs) stats.composeMaxNs = durationNs
        }
    }

    fun recordSectionDraw(name: String, durationNs: Long) {
        if (durationNs <= 0L) return
        synchronized(lock) {
            val stats = sectionStats.getOrPut(name) { SectionMutableStats() }
            stats.drawSamples += 1
            stats.drawTotalNs += durationNs
            if (durationNs > stats.drawMaxNs) stats.drawMaxNs = durationNs
        }
    }

    fun snapshot(): UiPerformanceSnapshot {
        synchronized(lock) {
            val nowNs = System.nanoTime()
            val elapsedMs = ((nowNs - sessionStartNs) / 1_000_000L).coerceAtLeast(1L)
            val elapsedSec = elapsedMs / 1_000.0

            val frames = frameDurationsNs.toList()
            val avgFrameNs = if (frames.isEmpty()) 0.0 else frames.average()
            val jankFrames = frames.count { it > FRAME_JANK_THRESHOLD_NS }
            val jankPercent = if (frames.isEmpty()) 0.0 else (jankFrames * 100.0) / frames.size
            val avgFrameMs = avgFrameNs / 1_000_000.0
            val fpsEstimate = if (avgFrameMs > 0.0) 1_000.0 / avgFrameMs else 0.0

            val sections = sectionStats.entries.map { (name, mutable) ->
                val composeAvgMs = if (mutable.composeSamples == 0) {
                    0.0
                } else {
                    (mutable.composeTotalNs.toDouble() / mutable.composeSamples) / 1_000_000.0
                }
                val composeMaxMs = mutable.composeMaxNs / 1_000_000.0
                val drawAvgMs = if (mutable.drawSamples == 0) {
                    0.0
                } else {
                    (mutable.drawTotalNs.toDouble() / mutable.drawSamples) / 1_000_000.0
                }
                val drawMaxMs = mutable.drawMaxNs / 1_000_000.0
                val composeRate = mutable.recompositions / elapsedSec
                val drawRate = mutable.drawSamples / elapsedSec
                val score = (composeAvgMs * composeRate) + (drawAvgMs * drawRate * 1.35)

                UiPerfSectionSnapshot(
                    name = name,
                    score = score,
                    recompositions = mutable.recompositions,
                    composeAvgMs = composeAvgMs,
                    composeMaxMs = composeMaxMs,
                    drawAvgMs = drawAvgMs,
                    drawMaxMs = drawMaxMs
                )
            }.sortedByDescending { it.score }

            return UiPerformanceSnapshot(
                elapsedMs = elapsedMs,
                estimatedFps = fpsEstimate,
                jankPercent = jankPercent,
                sections = sections
            )
        }
    }
}

@Composable
fun ProvideUiPerformanceTracking(
    monitor: UiPerformanceMonitor,
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalUiPerformanceTracking provides UiPerformanceTrackingContext(
            monitor = monitor,
            enabled = enabled
        ),
        content = content
    )
}

@Composable
fun ObserveUiPerformanceFrames(
    monitor: UiPerformanceMonitor,
    enabled: Boolean
) {
    LaunchedEffect(monitor, enabled) {
        if (!enabled) return@LaunchedEffect
        var previousFrameNs = 0L
        while (true) {
            withFrameNanos { frameNs ->
                if (previousFrameNs > 0L) {
                    monitor.recordFrameDuration(frameNs - previousFrameNs)
                }
                previousFrameNs = frameNs
            }
        }
    }
}

@Composable
fun UiPerfSection(
    name: String,
    content: @Composable () -> Unit
) {
    val tracking = LocalUiPerformanceTracking.current
    if (tracking?.enabled == true) {
        val startNs = System.nanoTime()
        content()
        SideEffect {
            tracking.monitor.recordSectionComposition(name = name, durationNs = System.nanoTime() - startNs)
        }
    } else {
        content()
    }
}

fun Modifier.uiPerfDraw(name: String): Modifier = composed {
    val tracking = LocalUiPerformanceTracking.current
    if (tracking?.enabled != true) {
        this
    } else {
        val drawSampleCounter = remember { intArrayOf(0) }
        this.drawWithContent {
            // Draw sampling limits profiler overhead while still surfacing hotspots.
            drawSampleCounter[0] += 1
            if (drawSampleCounter[0] % 2 != 0) {
                drawContent()
                return@drawWithContent
            }
            val startNs = System.nanoTime()
            drawContent()
            tracking.monitor.recordSectionDraw(name = name, durationNs = System.nanoTime() - startNs)
        }
    }
}

@Composable
fun UiPerformanceOverlay(
    monitor: UiPerformanceMonitor,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    val snapshot by produceState(
        initialValue = monitor.snapshot() to MainThreadBlockTracker.top(limit = 3),
        key1 = monitor,
        key2 = visible
    ) {
        while (true) {
            value = monitor.snapshot() to MainThreadBlockTracker.top(limit = 3)
            delay(350L)
        }
    }
    val uiSnapshot = snapshot.first
    val blockSnapshot = snapshot.second

    Box(
        modifier = modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .background(Color.Black.copy(alpha = 0.42f))
    ) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "UI PERFORMANCE OVERLAY",
                color = Color(0xFF9BE7A2),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace
            )
            val healthColor = when {
                uiSnapshot.jankPercent >= 12.0 -> Color(0xFFFF8A80)
                uiSnapshot.jankPercent >= 5.0 -> Color(0xFFFFE082)
                else -> Color(0xFF80DEEA)
            }
            Text(
                text = "Session ${uiSnapshot.elapsedMs / 1000}s | FPS ${uiSnapshot.estimatedFps.format1()} | " +
                    "Jank ${uiSnapshot.jankPercent.format1()}%",
                color = healthColor,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Top main-thread blockers:",
                color = Color(0xFFB39DDB),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            if (blockSnapshot.isEmpty()) {
                Text(
                    text = "- none captured yet",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                blockSnapshot.forEachIndexed { index, block ->
                    val rowColor = when (index) {
                        0 -> Color(0xFFFF8A80)
                        1 -> Color(0xFFFFE082)
                        2 -> Color(0xFFFFF59D)
                        else -> Color.White.copy(alpha = 0.78f)
                    }
                    Text(
                        text = "${index + 1}. ${block.signature} | count ${block.count} | max ${block.maxMs}ms",
                        color = rowColor,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Text(
                text = "Top 3 UI actions:",
                color = Color(0xFFB39DDB),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            val sections = uiSnapshot.sections.take(3)
            if (sections.isEmpty()) {
                Text(
                    text = "- collecting samples...",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                sections.forEachIndexed { index, section ->
                    val rowColor = when (index) {
                        0 -> Color(0xFFFF8A80)
                        1 -> Color(0xFFFFE082)
                        else -> Color(0xFFFFF59D)
                    }
                    Text(
                        text = "${index + 1}. ${section.name} | score ${section.score.format2()}",
                        color = rowColor,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Text(
                text = "Tap top menu bar to toggle",
                color = Color.White.copy(alpha = 0.66f),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun Double.format1(): String = String.format(java.util.Locale.US, "%.1f", this)
private fun Double.format2(): String = String.format(java.util.Locale.US, "%.2f", this)
