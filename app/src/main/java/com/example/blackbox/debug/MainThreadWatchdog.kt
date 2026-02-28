package com.example.blackbox.debug

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val WATCHDOG_TIMEOUT_MS = 400L
private const val WATCHDOG_SAMPLE_INTERVAL_MS = 1_000L
private const val WATCHDOG_COOLDOWN_MS = 2_000L

object MainThreadWatchdog {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainThread = Looper.getMainLooper().thread

    @Volatile
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                val ping = pingMainThread(timeoutMs = WATCHDOG_TIMEOUT_MS)
                if (!ping.responded) {
                    val rawStack = mainThread.stackTrace
                    MainThreadBlockTracker.recordBlock(
                        durationMs = ping.durationMs.coerceAtLeast(WATCHDOG_TIMEOUT_MS),
                        stackTrace = rawStack,
                        source = "watchdog"
                    )
                    delay(WATCHDOG_COOLDOWN_MS)
                } else {
                    delay(WATCHDOG_SAMPLE_INTERVAL_MS)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private data class PingResult(
        val responded: Boolean,
        val durationMs: Long
    )

    private suspend fun pingMainThread(timeoutMs: Long): PingResult {
        val latch = CountDownLatch(1)
        val startNs = System.nanoTime()
        mainHandler.post { latch.countDown() }
        val respondedQuickly = withTimeoutOrNull(timeoutMs + 50L) {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } == true
        if (respondedQuickly) {
            val durationMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            return PingResult(responded = true, durationMs = durationMs)
        }
        latch.await(5L, TimeUnit.SECONDS)
        val durationMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(timeoutMs)
        return PingResult(responded = false, durationMs = durationMs)
    }
}
