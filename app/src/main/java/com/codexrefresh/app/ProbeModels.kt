package com.codexrefresh.app

import org.json.JSONObject

data class ProbeModel(
    val id: String,
    val name: String,
    val contextReference: Int?,
    val reasoningEffort: String? = null,
    val supportsVerbosity: Boolean = false,
)

/** Decode the account's live Codex catalog for ChatGPT OAuth. */
internal fun parseProbeModels(catalog: JSONObject): List<ProbeModel> {
    val entries = catalog.optJSONArray("models") ?: error("模型目录缺少 models")
    val result = mutableListOf<ProbeModel>()
    val seen = mutableSetOf<String>()
    for (index in 0 until entries.length()) {
        val item = entries.optJSONObject(index) ?: continue
        val id = item.optString("slug").trim()
        if (item.optString("visibility") != "list") continue
        if (!id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) || !seen.add(id)) continue
        val modalities = item.optJSONArray("input_modalities")
        if (modalities != null && (0 until modalities.length()).none { modalities.optString(it) == "text" }) continue
        val context = item.optInt("context_window", 0).takeIf { it > 0 }
        val levels = item.optJSONArray("supported_reasoning_levels")
        val efforts = (0 until (levels?.length() ?: 0)).mapNotNull { level ->
            levels?.optJSONObject(level)?.optString("effort")?.takeIf { it.isNotBlank() }
        }
        val defaultEffort = item.optString("default_reasoning_level")
        val effort = when {
            "low" in efforts -> "low"
            defaultEffort in efforts -> defaultEffort
            else -> efforts.firstOrNull()
        }
        result += ProbeModel(
            id,
            item.optString("display_name").ifBlank { id },
            context,
            effort,
            item.optBoolean("support_verbosity"),
        )
    }
    if (result.isEmpty()) error("模型目录没有可用于消息的模型")
    return result
}
