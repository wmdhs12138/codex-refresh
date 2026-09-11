package com.codexrefresh.app

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
private const val AUTH = "https://auth.openai.com"
private const val USAGE = "https://chatgpt.com/backend-api/wham/usage"
private const val FIVE_HOURS = 18_000L
private const val WEEK = 604_800L
private const val CODEX_URL = "https://chatgpt.com/backend-api/codex/responses"
private const val CODEX_MODEL = "gpt-5.6-luna"
// ChatGPT Codex does not publish this product limit; this is the installed Pi transport metadata.
const val CODEX_CONTEXT_REFERENCE = 272_000

data class DeviceCode(val id: String, val code: String, val interval: Long)
data class Tokens(val access: String, val refresh: String, val expiresAt: Long)
data class Quota(val percent: Double?, val reset: Long?)
data class Usage(val fiveHour: Quota, val weekly: Quota)
data class HttpResult(val status: Int, val body: String)

fun classifyUsageWindows(rateLimit: JSONObject): Pair<JSONObject, JSONObject> {
    val primary = rateLimit.optJSONObject("primary_window")
    val secondary = rateLimit.optJSONObject("secondary_window")
    val windows = listOfNotNull(primary, secondary)
    if (windows.isEmpty()) return JSONObject() to JSONObject()
    fun duration(j: JSONObject) = j.optLong("limit_window_seconds", -1L)
    val five = windows.minByOrNull { abs(duration(it) - FIVE_HOURS) } ?: JSONObject()
    val weekly = windows.minByOrNull { abs(duration(it) - WEEK) } ?: JSONObject()
    return if (five === weekly && windows.size > 1) {
        (primary ?: JSONObject()) to (secondary ?: JSONObject())
    } else five to weekly
}

class CodexClient {
    @Volatile private var activeProbe: HttpURLConnection? = null

    fun cancelProbe() { activeProbe?.disconnect() }
    private fun request(url: String, method: String, body: String? = null, headers: Map<String, String> = emptyMap()): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            HttpResult(status, text)
        } finally { connection.disconnect() }
    }

    private fun json(result: HttpResult, operation: String): JSONObject {
        if (result.status !in 200..299) {
            val detail = result.body.replace(Regex("(?i)(access|refresh)_token[^,}]*"), "token=redacted").take(200)
            error("$operation 失败（HTTP ${result.status}）${if (detail.isNotBlank()) ": $detail" else ""}")
        }
        return try { JSONObject(result.body) } catch (_: Exception) { error("$operation 返回无效数据") }
    }

    fun beginDeviceAuth(): DeviceCode {
        val j = json(request("$AUTH/api/accounts/deviceauth/usercode", "POST", "{\"client_id\":\"$CLIENT_ID\"}"), "设备登录")
        val id = j.optString("device_auth_id")
        val code = j.optString("user_code")
        require(id.isNotBlank() && code.isNotBlank()) { "设备登录响应缺少验证码" }
        val interval = j.optString("interval").toLongOrNull() ?: j.optLong("interval", 5)
        return DeviceCode(id, code, interval.coerceAtLeast(1))
    }

    fun finishDeviceAuth(device: DeviceCode, cancelled: () -> Boolean): Tokens {
        val deadline = System.currentTimeMillis() + 15 * 60 * 1000
        var delay = device.interval
        while (System.currentTimeMillis() < deadline) {
            if (cancelled() || Thread.currentThread().isInterrupted) error("已取消登录")
            try { Thread.sleep(TimeUnit.SECONDS.toMillis(delay)) } catch (_: InterruptedException) { error("已取消登录") }
            val result = request("$AUTH/api/accounts/deviceauth/token", "POST", "{\"device_auth_id\":\"${device.id}\",\"user_code\":\"${device.code}\"}")
            if (result.status == 403 || result.status == 404) continue
            val pending = try { JSONObject(result.body).optString("error") == "deviceauth_authorization_pending" } catch (_: Exception) { false }
            if (pending) continue
            if (result.status == 429 || result.body.contains("slow_down", true)) { delay = (delay + 2).coerceAtMost(30); continue }
            val j = json(result, "设备授权")
            val code = j.optString("authorization_code")
            val verifier = j.optString("code_verifier")
            require(code.isNotBlank() && verifier.isNotBlank()) { "设备授权响应缺少字段" }
            return exchange(code, verifier)
        }
        error("设备登录超时")
    }

    private fun exchange(code: String, verifier: String): Tokens {
        val form = "grant_type=authorization_code&client_id=$CLIENT_ID&code=${enc(code)}&code_verifier=${enc(verifier)}&redirect_uri=${enc("$AUTH/deviceauth/callback")}"
        return tokenResult(requestForm("$AUTH/oauth/token", form), null, "登录换取令牌")
    }

    fun verificationUrl() = "$AUTH/codex/device"

    fun refresh(old: Tokens): Tokens {
        val form = "grant_type=refresh_token&refresh_token=${enc(old.refresh)}&client_id=$CLIENT_ID"
        return tokenResult(requestForm("$AUTH/oauth/token", form), old.refresh, "刷新令牌")
    }

    private fun requestForm(url: String, form: String): HttpResult {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 15_000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            c.outputStream.use { it.write(form.toByteArray(StandardCharsets.UTF_8)) }
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            HttpResult(status, stream?.bufferedReader(StandardCharsets.UTF_8)?.use(BufferedReader::readText).orEmpty())
        } finally { c.disconnect() }
    }

    private fun tokenResult(result: HttpResult, oldRefresh: String?, operation: String): Tokens {
        val j = json(result, operation)
        val access = j.optString("access_token")
        val refresh = j.optString("refresh_token").ifBlank { oldRefresh.orEmpty() }
        val expires = j.optLong("expires_in", 0)
        require(access.isNotBlank() && refresh.isNotBlank() && expires > 0) { "$operation 返回缺少令牌字段" }
        return Tokens(access, refresh, System.currentTimeMillis() + expires * 1000)
    }

    fun usage(tokens: Tokens): Usage {
        val account = accountId(tokens.access)
        val headers = mutableMapOf("Authorization" to "Bearer ${tokens.access}", "Accept" to "application/json", "Cache-Control" to "no-cache", "Pragma" to "no-cache")
        if (account.isNotBlank()) headers["ChatGPT-Account-Id"] = account
        val root = json(request(USAGE, "GET", headers = headers), "读取额度")
        val (five, weekly) = classifyUsageWindows(root.optJSONObject("rate_limit") ?: JSONObject())
        fun quota(window: JSONObject) = Quota(window.optDouble("used_percent", Double.NaN).takeUnless { it.isNaN() }, window.optLong("reset_at", 0).takeUnless { it == 0L })
        return Usage(quota(five), quota(weekly))
    }

    fun accountId(access: String): String = try {
        val payload = access.split('.')[1]
        val raw = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).toString(StandardCharsets.UTF_8)
        JSONObject(raw).optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id", "").orEmpty()
    } catch (_: Exception) { "" }

    fun probe(tokens: Tokens, cancelled: () -> Boolean): ProbeResult {
        if (cancelled() || Thread.currentThread().isInterrupted) error("已取消测试请求")
        val challenge = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        val account = accountId(tokens.access)
        val requestId = java.util.UUID.randomUUID().toString()
        val headers = mutableMapOf("Authorization" to "Bearer ${tokens.access}", "Accept" to "text/event-stream", "Content-Type" to "application/json", "OpenAI-Beta" to "responses=experimental", "originator" to "codex-refresh-android", "User-Agent" to "CodexRefreshAndroid/0.1.0", "Cache-Control" to "no-cache", "session-id" to requestId, "x-client-request-id" to requestId)
        if (account.isNotBlank()) headers["chatgpt-account-id"] = account
        val body = JSONObject().apply {
            put("model", CODEX_MODEL); put("store", false); put("stream", true)
            put("instructions", "Reply with exactly the requested verification string and nothing else.")
            put("input", org.json.JSONArray().put(JSONObject().apply { put("role", "user"); put("content", org.json.JSONArray().put(JSONObject().apply { put("type", "input_text"); put("text", "Return exactly: PI_ANDROID_KICK_$challenge") })) }))
            put("text", JSONObject().put("verbosity", "low")); put("reasoning", JSONObject().put("effort", "low")); put("tool_choice", "none"); put("parallel_tool_calls", false)
        }.toString()
        if (cancelled() || Thread.currentThread().isInterrupted) error("已取消测试请求")
        val connection = URL(CODEX_URL).openConnection() as HttpURLConnection
        activeProbe = connection
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = 15_000; connection.readTimeout = 120_000; connection.doOutput = true
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (cancelled() || Thread.currentThread().isInterrupted) error("已取消测试请求")
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { readBounded(it, 240) }.orEmpty()
                    .let(::redactTokenText)
                error("测试请求失败（HTTP $status）${if (detail.isNotBlank()) ": $detail" else ""}")
            }
            val rateLimitHeaders = connection.headerFields.entries
                .asSequence()
                .filter { (key, _) -> key?.startsWith("x-ratelimit-", ignoreCase = true) == true }
                .take(8)
                .associate { (key, values) -> key.lowercase() to values.joinToString(",").take(80) }
            val parser = SseParser()
            val state = ProbeSseState()
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                while (true) {
                    if (cancelled() || Thread.currentThread().isInterrupted) error("已取消测试请求")
                    val line = reader.readLine() ?: break
                    val payload = parser.accept(line)
                    if (payload != null && state.consume(payload)) break
                }
            }
            parser.finish()?.let { state.consume(it) }
            val stream = state.result()
            val expected = "PI_ANDROID_KICK_$challenge"
            return ProbeResult(
                CODEX_MODEL,
                challenge,
                stream.text.trim() == expected,
                stream.inputTokens,
                stream.outputTokens,
                stream.cachedTokens,
                rateLimitHeaders,
            )
        } finally { if (activeProbe === connection) activeProbe = null; connection.disconnect() }
    }
    private fun readBounded(reader: BufferedReader, maxChars: Int): String {
        val buffer = CharArray(maxChars)
        val count = reader.read(buffer, 0, maxChars)
        return if (count > 0) String(buffer, 0, count) else ""
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}

data class ProbeResult(
    val model: String,
    val challenge: String,
    val verified: Boolean,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val rateLimitHeaders: Map<String, String>,
)

private val TOKEN_STORE_LOCK = Any()

class TokenStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("codex_auth", Context.MODE_PRIVATE)
    private val alias = "CodexRefreshTokens"
    private fun key(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            val generator = javax.crypto.KeyGenerator.getInstance("AES", "AndroidKeyStore")
            generator.init(android.security.keystore.KeyGenParameterSpec.Builder(alias, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generator.generateKey()
        }
        return (ks.getEntry(alias, null) as java.security.KeyStore.SecretKeyEntry).secretKey
    }
    fun load(): Tokens? = synchronized(TOKEN_STORE_LOCK) { loadUnlocked() }

    internal fun loadUnlocked(): Tokens? = try {
        val blob = Base64.decode(prefs.getString("blob", null) ?: return null, Base64.NO_WRAP)
        val plain = decrypt(blob.copyOfRange(12, blob.size), blob.copyOfRange(0, 12)).toString(StandardCharsets.UTF_8).split('\n')
        Tokens(plain[0], plain[1], plain[2].toLong())
    } catch (_: Exception) { null }

    fun save(tokens: Tokens) {
        // Invalidate and publish as one short critical section. This closes the
        // gap in which a refresh could claim the old disk value after login.
        TokenCoordinator.invalidateAndPersist {
            synchronized(TOKEN_STORE_LOCK) { saveUnlocked(tokens) }
        }
    }

    internal fun saveUnlocked(tokens: Tokens) {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal("${tokens.access}\n${tokens.refresh}\n${tokens.expiresAt}".toByteArray(StandardCharsets.UTF_8))
        check(prefs.edit().putString("blob", Base64.encodeToString(cipher.iv + data, Base64.NO_WRAP)).commit()) {
            "无法持久化认证令牌"
        }
    }

    private fun decrypt(data: ByteArray, iv: ByteArray): ByteArray = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, iv)) }.doFinal(data)

    fun clear() {
        // Logout must win over a refresh that is currently on the network.
        TokenCoordinator.invalidateAndPersist {
            synchronized(TOKEN_STORE_LOCK) {
                check(prefs.edit().clear().commit()) { "无法清除认证令牌" }
            }
        }
    }
}

/** Serializes refresh-token rotation without holding the persistence lock over I/O. */
object TokenCoordinator {
    private const val REFRESH_MARGIN_MS = 60_000L
    private val refreshLock = java.util.concurrent.locks.ReentrantLock()
    private val refreshDone = refreshLock.newCondition()
    private var refreshInFlight = false
    private var revision = 0L

    internal fun invalidateAndPersist(persist: () -> Unit) {
        refreshLock.lock()
        try {
            revision++
            persist()
        } finally {
            refreshLock.unlock()
        }
    }

    fun latest(store: TokenStore, client: CodexClient, now: Long = System.currentTimeMillis()): Tokens? {
        var checkAt = now
        while (true) {
            val claim = claimRefresh(store, checkAt)
            if (claim.retry) {
                checkAt = System.currentTimeMillis()
                continue
            }
            val current = claim.tokens ?: return null
            if (!claim.shouldRefresh) return current
            val refreshed = runCatching { client.refresh(current) }
            val outcome = completeRefresh(store, claim, refreshed)
            if (outcome.error != null) throw outcome.error
            return outcome.tokens
        }
    }

    fun current(store: TokenStore): Tokens? = store.load()

    private fun claimRefresh(store: TokenStore, now: Long): RefreshClaim {
        refreshLock.lock()
        try {
            while (refreshInFlight) {
                try {
                    // Callers are the Activity/Worker executors, never the UI thread.
                    refreshDone.await()
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
                return RefreshClaim(null, 0L, shouldRefresh = false, retry = true)
            }
            // This read is short and happens only after taking refreshLock. A
            // login/logout cannot change revision without also publishing its
            // new disk value while holding the same lock.
            val current = store.load()
                ?: return RefreshClaim(null, revision, shouldRefresh = false, retry = false)
            val begin = RefreshCoordinationPolicy.begin(
                refreshInFlight = false,
                tokens = current,
                now = now,
                marginMs = REFRESH_MARGIN_MS,
            )
            if (begin.action == RefreshCoordinationPolicy.BeginAction.USE_CURRENT) {
                return RefreshClaim(current, revision, shouldRefresh = false, retry = false)
            }
            refreshInFlight = true
            return RefreshClaim(current, revision, shouldRefresh = true, retry = false)
        } finally {
            refreshLock.unlock()
        }
    }

    private fun completeRefresh(
        store: TokenStore,
        claim: RefreshClaim,
        refreshed: Result<Tokens>,
    ): RefreshOutcome {
        refreshLock.lock()
        try {
            // Keep the complete local transaction in lock order:
            // refreshLock -> TOKEN_STORE_LOCK. The network refresh has already
            // finished and is deliberately outside both locks.
            return synchronized(TOKEN_STORE_LOCK) {
                val current = store.loadUnlocked()
                val decision = RefreshCoordinationPolicy.finish(
                    claim.revision,
                    revision,
                    refreshed.getOrNull(),
                    current,
                )
                if (decision.persist && refreshed.isSuccess) {
                    store.saveUnlocked(refreshed.getOrThrow())
                }
                RefreshOutcome(
                    decision.tokens,
                    if (decision.propagateError) refreshed.exceptionOrNull() else null,
                )
            }
        } finally {
            refreshInFlight = false
            refreshDone.signalAll()
            refreshLock.unlock()
        }
    }

    private data class RefreshClaim(
        val tokens: Tokens?,
        val revision: Long,
        val shouldRefresh: Boolean,
        val retry: Boolean,
    )
    private data class RefreshOutcome(val tokens: Tokens?, val error: Throwable?)
}

/** Pure state transition for testing stale refresh results and failure fallback. */
object RefreshCoordinationPolicy {
    enum class BeginAction { USE_CURRENT, WAIT, START_REFRESH }
    data class Begin(val action: BeginAction)
    data class Finish(val persist: Boolean, val tokens: Tokens?, val propagateError: Boolean)

    fun begin(
        refreshInFlight: Boolean,
        tokens: Tokens,
        now: Long,
        marginMs: Long,
    ): Begin = when {
        refreshInFlight -> Begin(BeginAction.WAIT)
        tokens.expiresAt >= now + marginMs -> Begin(BeginAction.USE_CURRENT)
        else -> Begin(BeginAction.START_REFRESH)
    }

    fun finish(
        startedRevision: Long,
        currentRevision: Long,
        refreshed: Tokens?,
        diskTokens: Tokens?,
    ): Finish {
        if (startedRevision != currentRevision) {
            return Finish(persist = false, tokens = diskTokens, propagateError = false)
        }
        if (refreshed != null) return Finish(persist = true, tokens = refreshed, propagateError = false)
        return Finish(persist = false, tokens = diskTokens, propagateError = true)
    }
}
