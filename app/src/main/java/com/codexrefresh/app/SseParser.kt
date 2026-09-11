package com.codexrefresh.app

/** Small, bounded SSE framer. It deliberately returns JSON payloads for the client to decode. */
internal class SseParser(private val maxDataChars: Int = 32_768) {
    private val data = StringBuilder()
    private var dropped = false

    fun accept(line: String): String? {
        val normalized = line.removeSuffix("\r")
        if (normalized.isEmpty()) return flush()
        when {
            normalized.startsWith("data:") -> {
                val value = normalized.substring(5).trimStart()
                if (value == "[DONE]") { data.setLength(0); dropped = true; return null }
                if (data.length + value.length + 1 <= maxDataChars) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(value)
                } else dropped = true
            }
        }
        return null
    }

    fun finish(): String? = flush()

    private fun flush(): String? {
        val result = if (!dropped && data.isNotEmpty()) data.toString() else null
        data.setLength(0); dropped = false
        return result
    }
}
