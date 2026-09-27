package com.codexrefresh.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeModelsTest {
    @Test fun catalogOnlyOffersVisibleTextModelsForChatGptLogin() {
        val catalog = JSONObject("""{"models":[
            {"slug":"model-a","display_name":"Model A","visibility":"list","supported_in_api":true,"context_window":128000,"supported_reasoning_levels":[{"effort":"medium"},{"effort":"low"}],"support_verbosity":true},
            {"slug":"model-a","display_name":"Duplicate","visibility":"list","supported_in_api":true},
            {"slug":"hidden-model","visibility":"hide","supported_in_api":true},
            {"slug":"chatgpt-only","visibility":"list","supported_in_api":false},
            {"slug":"image-only","visibility":"list","supported_in_api":true,"input_modalities":["image"]},
            {"slug":"model-b","visibility":"list","supported_in_api":true}
        ]}""")

        assertEquals(
            listOf(
                ProbeModel("model-a", "Model A", 128000, "low", true),
                ProbeModel("chatgpt-only", "chatgpt-only", null),
                ProbeModel("model-b", "model-b", null),
            ),
            parseProbeModels(catalog),
        )
    }

    @Test fun missingOrEmptyCatalogIsRejected() {
        assertThrows(IllegalStateException::class.java) { parseProbeModels(JSONObject()) }
        assertThrows(IllegalStateException::class.java) { parseProbeModels(JSONObject("""{"models":[]}""")) }
    }

    @Test fun requestUsesSelectedModelAndPreservesVerificationChallenge() {
        val body = JSONObject(probeRequestBody("model-b", "abc123", ProbeModel("model-b", "Model B", null, "medium")))

        assertEquals("model-b", body.getString("model"))
        assertEquals(false, body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("medium", body.getJSONObject("reasoning").getString("effort"))
        assertEquals(false, body.has("text"))
        assertEquals(
            "Return exactly: PI_ANDROID_KICK_abc123",
            body.getJSONArray("input").getJSONObject(0)
                .getJSONArray("content").getJSONObject(0).getString("text"),
        )
    }
}
