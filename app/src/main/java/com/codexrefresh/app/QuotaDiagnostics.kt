package com.codexrefresh.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val WINDOW_CONFIRMATION_DELAY_SECONDS = 12L
const val WINDOW_CONFIRMATION_REASON = "正在确认零用量窗口"

enum class QuotaObservationSource(val label: String) {
    APP_OPEN("进入应用"),
    MANUAL_REFRESH("手动刷新"),
    LOGIN("登录后读取"),
    MANUAL_ACTIVATION("手动激活后"),
    AUTO_PREFLIGHT("自动预检"),
    AUTO_ACTIVATION("自动激活后"),
    WINDOW_CONFIRMATION("窗口确认"),
}

data class QuotaObservation(
    val fetchedAt: Long,
    val source: QuotaObservationSource,
    val appRequestSent: Boolean,
    val usedPercentRaw: String?,
    val resetAt: Long?,
    val resetAfterSeconds: Long?,
    val windowSeconds: Long?,
)

class QuotaDiagnosticsStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("quota_diagnostics", Context.MODE_PRIVATE)

    fun record(
        source: QuotaObservationSource,
        usage: Usage,
        appRequestSent: Boolean = false,
        fetchedAt: Long = System.currentTimeMillis() / 1000,
    ): List<QuotaObservation> = synchronized(LOCK) {
        val five = usage.fiveHour
        val updated = (
            readUnlocked() + QuotaObservation(
                fetchedAt = fetchedAt,
                source = source,
                appRequestSent = appRequestSent,
                usedPercentRaw = five.percentRaw,
                resetAt = five.reset,
                resetAfterSeconds = five.resetAfterSeconds,
                windowSeconds = five.windowSeconds,
            )
            ).takeLast(MAX_ENTRIES)
        preferences.edit().putString(KEY_ENTRIES, encode(updated)).apply()
        updated
    }

    fun read(): List<QuotaObservation> = synchronized(LOCK) { readUnlocked() }

    fun clear() = synchronized(LOCK) {
        preferences.edit().clear().apply()
    }

    private fun readUnlocked(): List<QuotaObservation> {
        val raw = preferences.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val source = runCatching {
                        QuotaObservationSource.valueOf(item.optString("source"))
                    }.getOrNull() ?: continue
                    add(
                        QuotaObservation(
                            fetchedAt = item.optLong("fetched_at"),
                            source = source,
                            appRequestSent = item.optBoolean("app_request"),
                            usedPercentRaw = item.nullableString("used_percent"),
                            resetAt = item.nullableLong("reset_at"),
                            resetAfterSeconds = item.nullableLong("reset_after_seconds"),
                            windowSeconds = item.nullableLong("window_seconds"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encode(entries: List<QuotaObservation>): String = JSONArray().apply {
        entries.forEach { entry ->
            put(JSONObject().apply {
                put("fetched_at", entry.fetchedAt)
                put("source", entry.source.name)
                put("app_request", entry.appRequestSent)
                putNullable("used_percent", entry.usedPercentRaw)
                putNullable("reset_at", entry.resetAt)
                putNullable("reset_after_seconds", entry.resetAfterSeconds)
                putNullable("window_seconds", entry.windowSeconds)
            })
        }
    }.toString()

    private fun JSONObject.nullableString(key: String): String? =
        takeIf { has(key) && !isNull(key) }?.optString(key)

    private fun JSONObject.nullableLong(key: String): Long? =
        takeIf { has(key) && !isNull(key) }?.optLong(key)

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private companion object {
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 40
        val LOCK = Any()
    }
}

object QuotaDiagnosticsPresentation {
    private val localTime = DateTimeFormatter.ofPattern("M-d HH:mm:ss", Locale.SIMPLIFIED_CHINESE)

    fun summary(entries: List<QuotaObservation>, zone: ZoneId): String {
        val latest = entries.lastOrNull() ?: return "尚无观测记录"
        val time = formatEpoch(latest.fetchedAt, zone)
        return "最近：${latest.source.label} · $time · 共 ${entries.size} 条"
    }

    fun details(entries: List<QuotaObservation>, zone: ZoneId): String =
        entries.asReversed().joinToString("\n\n") { entry ->
            buildString {
                append(formatEpoch(entry.fetchedAt, zone))
                append(" · ")
                append(entry.source.label)
                append(" · app_request=")
                append(if (entry.appRequestSent) "yes" else "no")
                append("\nused_percent=")
                append(entry.usedPercentRaw ?: "null")
                append("\nreset_at=")
                append(entry.resetAt ?: "null")
                entry.resetAt?.let {
                    append(" (")
                    append(formatEpoch(it, zone))
                    append(") · delta=")
                    append(it - entry.fetchedAt)
                    append("s")
                }
                append("\nreset_after_seconds=")
                append(entry.resetAfterSeconds ?: "null")
                append(" · limit_window_seconds=")
                append(entry.windowSeconds ?: "null")
            }
        }

    private fun formatEpoch(epochSecond: Long, zone: ZoneId): String =
        Instant.ofEpochSecond(epochSecond).atZone(zone).format(localTime)
}

enum class FiveHourEvidenceKind { UNKNOWN, ACTIVE, STANDBY, AMBIGUOUS }

data class FiveHourEvidence(
    val kind: FiveHourEvidenceKind,
    val resetAt: Long? = null,
)

/** Interprets the observed WHAM behavior without treating a lone future reset as activity. */
object FiveHourEvidencePolicy {
    private const val DEFAULT_WINDOW_SECONDS = 18_000L
    private const val FULL_WINDOW_TOLERANCE_SECONDS = 3L
    private const val MIN_PAIR_SPACING_SECONDS = 5L
    private const val ANCHORED_AGE_SECONDS = 5L

    fun evaluate(entries: List<QuotaObservation>, now: Long): FiveHourEvidence {
        val latest = entries.lastOrNull() ?: return FiveHourEvidence(FiveHourEvidenceKind.UNKNOWN)
        val resetAt = latest.resetAt ?: return FiveHourEvidence(FiveHourEvidenceKind.UNKNOWN)
        if (resetAt <= now) return FiveHourEvidence(FiveHourEvidenceKind.STANDBY)

        val usedPercent = latest.usedPercentRaw?.toDoubleOrNull()
        if (usedPercent != null && usedPercent > 0.0) {
            return FiveHourEvidence(FiveHourEvidenceKind.ACTIVE, resetAt)
        }
        if (latest.appRequestSent) {
            return FiveHourEvidence(FiveHourEvidenceKind.ACTIVE, resetAt)
        }
        if (usedPercent == null) return FiveHourEvidence(FiveHourEvidenceKind.UNKNOWN)

        val windowSeconds = latest.windowSeconds ?: DEFAULT_WINDOW_SECONDS
        val remaining = latest.resetAfterSeconds ?: (resetAt - latest.fetchedAt)
        val latestIsFull = isFullWindow(remaining, windowSeconds)
        val previous = entries.dropLast(1).lastOrNull {
            latest.fetchedAt - it.fetchedAt >= MIN_PAIR_SPACING_SECONDS &&
                it.resetAt != null &&
                it.usedPercentRaw?.toDoubleOrNull() == 0.0
        }

        if (previous != null) {
            if (previous.resetAt == resetAt) {
                return FiveHourEvidence(FiveHourEvidenceKind.ACTIVE, resetAt)
            }
            val previousWindow = previous.windowSeconds ?: DEFAULT_WINDOW_SECONDS
            val previousRemaining = previous.resetAfterSeconds
                ?: (previous.resetAt!! - previous.fetchedAt)
            val fetchShift = latest.fetchedAt - previous.fetchedAt
            val resetShift = resetAt - previous.resetAt!!
            if (
                latestIsFull &&
                isFullWindow(previousRemaining, previousWindow) &&
                kotlin.math.abs(resetShift - fetchShift) <= FULL_WINDOW_TOLERANCE_SECONDS
            ) {
                return FiveHourEvidence(
                    if (latest.source == QuotaObservationSource.WINDOW_CONFIRMATION) {
                        FiveHourEvidenceKind.STANDBY
                    } else {
                        FiveHourEvidenceKind.AMBIGUOUS
                    },
                )
            }
        }

        // A reset already several seconds into its countdown was anchored by
        // activity before this read. A response at the full 5h boundary is
        // ambiguous until one metadata-only confirmation read arrives.
        if (remaining <= windowSeconds - ANCHORED_AGE_SECONDS) {
            return FiveHourEvidence(FiveHourEvidenceKind.ACTIVE, resetAt)
        }
        return FiveHourEvidence(
            if (latestIsFull) FiveHourEvidenceKind.AMBIGUOUS else FiveHourEvidenceKind.UNKNOWN,
        )
    }

    private fun isFullWindow(remaining: Long, windowSeconds: Long): Boolean =
        kotlin.math.abs(remaining - windowSeconds) <= FULL_WINDOW_TOLERANCE_SECONDS
}

fun FiveHourEvidence.schedulingQuota(quota: Quota?): Quota? {
    if (quota == null || kind != FiveHourEvidenceKind.ACTIVE) return quota
    val positivePercent = quota.percent?.takeIf { it > 0.0 } ?: Double.MIN_VALUE
    return quota.copy(percent = positivePercent, reset = resetAt ?: quota.reset)
}
