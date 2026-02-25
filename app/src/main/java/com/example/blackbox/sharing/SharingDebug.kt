package com.example.blackbox.sharing

const val SHARING_DEBUG_TAG = "BBX_SHARING"

internal fun shortSharingId(id: String?): String {
    if (id.isNullOrBlank()) return "null"
    return if (id.length <= 12) id else "${id.take(6)}...${id.takeLast(4)}"
}

internal fun summarizeRelayBody(body: String, maxChars: Int = 240): String {
    val compact = body.replace('\n', ' ').trim()
    return if (compact.length <= maxChars) compact else compact.take(maxChars) + "…"
}
