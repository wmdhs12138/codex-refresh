package com.codexrefresh.app

import org.json.JSONArray
import org.json.JSONObject

internal data class ProbeStreamResult(
    val text: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
)

/** Stateful decoder for the small subset of Codex Responses events used by the probe. */
internal class ProbeSseState(private val maxOutputChars: Int = 4_000) {
    private val text = StringBuilder()
    private var completed = false
    private var inputTokens = 0L
    private var outputTokens = 0L
    private var cachedTokens = 0L

    /** Returns true once a successful terminal event has been consumed. */
    fun consume(payload: String): Boolean {
        val event = try {
            JSONObject(payload)
        } catch (_: Exception) {
            error("测试请求返回了无效 SSE 数据")
        }
        when (event.optString("type")) {
            "response.output_text.delta" -> append(event.optString("delta"))
            "response.completed", "response.done" -> {
                val response = event.optJSONObject("response") ?: error("测试请求完成事件缺少响应")
                val status = response.optString("status")
                if (status != "completed") error("测试请求终态异常：${status.ifBlank { "未知" }.take(40)}")
                val usage = response.optJSONObject("usage")
                inputTokens = usage?.optLong("input_tokens", 0) ?: 0
                outputTokens = usage?.optLong("output_tokens", 0) ?: 0
                cachedTokens = usage?.optJSONObject("input_tokens_details")?.optLong("cached_tokens", 0) ?: 0
                if (text.isEmpty()) append(extractOutputText(response))
                completed = true
            }
            "response.failed", "response.incomplete", "error" -> {
                val failure = event.optJSONObject("error")
                    ?: event.optJSONObject("response")?.optJSONObject("error")
                val message = redactTokenText(failure?.optString("message").orEmpty()).take(160)
                error("测试请求未完成${if (message.isNotBlank()) ": $message" else ""}")
            }
        }
        return completed
    }

    fun result(): ProbeStreamResult {
        check(completed) { "测试请求未收到完成事件" }
        return ProbeStreamResult(text.toString(), inputTokens, outputTokens, cachedTokens)
    }

    private fun append(value: String) {
        if (value.isEmpty() || text.length >= maxOutputChars) return
        text.append(value.take(maxOutputChars - text.length))
    }

    private fun extractOutputText(response: JSONObject): String {
        val direct = response.optString("output_text")
        if (direct.isNotBlank()) return direct
        val output = response.optJSONArray("output") ?: return ""
        val result = StringBuilder()
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            appendTextItems(content, result)
            if (result.length >= maxOutputChars) break
        }
        return result.toString()
    }

    private fun appendTextItems(content: JSONArray, result: StringBuilder) {
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            if (item.optString("type") != "output_text") continue
            val value = item.optString("text")
            if (value.isNotEmpty() && result.length < maxOutputChars) {
                result.append(value.take(maxOutputChars - result.length))
            }
        }
    }
}

internal fun redactTokenText(value: String): String = value.replace(
    Regex("(?i)(access|refresh)_token\\s*[:=]\\s*\\\"?[^,}\\\" ]+"),
    "token=redacted",
)
