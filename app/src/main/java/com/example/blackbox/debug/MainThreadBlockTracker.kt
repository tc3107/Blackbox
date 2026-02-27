package com.example.blackbox.debug

data class MainThreadBlockSummary(
    val signature: String,
    val count: Int,
    val avgMs: Double,
    val maxMs: Long
)

object MainThreadBlockTracker {
    private data class MutableBlockStat(
        var count: Int = 0,
        var totalMs: Long = 0L,
        var maxMs: Long = 0L
    )

    private val lock = Any()
    private val statsBySignature = linkedMapOf<String, MutableBlockStat>()

    fun reset() {
        synchronized(lock) {
            statsBySignature.clear()
        }
    }

    fun recordBlock(
        durationMs: Long,
        stackTrace: Array<StackTraceElement>,
        source: String? = null
    ) {
        val baseSignature = deriveSignature(stackTrace)
        val signature = source
            ?.takeIf { it.isNotBlank() }
            ?.let { "$it:$baseSignature" }
            ?: baseSignature
        recordNamedBlock(durationMs = durationMs, signature = signature)
    }

    fun recordNamedBlock(durationMs: Long, signature: String) {
        if (durationMs <= 0L || signature.isBlank()) return
        synchronized(lock) {
            val stat = statsBySignature.getOrPut(signature) { MutableBlockStat() }
            stat.count += 1
            stat.totalMs += durationMs
            if (durationMs > stat.maxMs) {
                stat.maxMs = durationMs
            }
        }
    }

    fun top(limit: Int): List<MainThreadBlockSummary> {
        synchronized(lock) {
            return statsBySignature.entries
                .map { (signature, stat) ->
                    MainThreadBlockSummary(
                        signature = signature,
                        count = stat.count,
                        avgMs = if (stat.count == 0) 0.0 else stat.totalMs.toDouble() / stat.count.toDouble(),
                        maxMs = stat.maxMs
                    )
                }
                .sortedWith(
                    compareByDescending<MainThreadBlockSummary> { it.maxMs }
                        .thenByDescending { it.avgMs }
                        .thenByDescending { it.count }
                )
                .take(limit.coerceAtLeast(0))
        }
    }

    private fun deriveSignature(stackTrace: Array<StackTraceElement>): String {
        val appFrame = stackTrace.firstOrNull { it.className.startsWith("com.example.blackbox") }
        if (appFrame != null) {
            return "${appFrame.className.substringAfterLast('.')}.${appFrame.methodName}"
        }

        val composeFrame = stackTrace.firstOrNull { it.className.startsWith("androidx.compose") }
        if (composeFrame != null) {
            return "${composeFrame.className.substringAfterLast('.')}.${composeFrame.methodName}"
        }

        val fallback = stackTrace.firstOrNull()
        return if (fallback == null) {
            "unknown"
        } else {
            "${fallback.className.substringAfterLast('.')}.${fallback.methodName}"
        }
    }
}
