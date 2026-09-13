package com.codexrefresh.app

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class QuotaResetSnapshot(
    val fiveHourReset: Long?,
    val sevenDayReset: Long?,
)

class QuotaResetStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("quota_reset_snapshot", Context.MODE_PRIVATE)

    fun read(): QuotaResetSnapshot = QuotaResetSnapshot(
        fiveHourReset = preferences.getLong("five_hour_reset", -1L).takeIf { it > 0L },
        sevenDayReset = preferences.getLong("seven_day_reset", -1L).takeIf { it > 0L },
    )

    fun save(usage: Usage) {
        preferences.edit()
            .putLong("five_hour_reset", usage.fiveHour.reset ?: -1L)
            .putLong("seven_day_reset", usage.weekly.reset ?: -1L)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }
}

object QuotaPresentation {
    /** The service reports consumed percent; the UI intentionally presents remaining capacity. */
    fun remainingPercent(usedPercent: Double?): Double? =
        usedPercent
            ?.takeIf { it.isFinite() }
            ?.let { (100.0 - it).coerceIn(0.0, 100.0) }

    fun resetLabel(epochSecond: Long?, now: Long, zone: ZoneId): String {
        if (epochSecond == null || epochSecond <= 0L) return "重置时间未知"
        val reset = Instant.ofEpochSecond(epochSecond).atZone(zone)
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val day = if (reset.toLocalDate() == today) {
            "今天"
        } else {
            reset.format(DateTimeFormatter.ofPattern("M月d日", Locale.SIMPLIFIED_CHINESE))
        }
        return "$day ${reset.format(DateTimeFormatter.ofPattern("HH:mm"))} 重置"
    }

    fun fiveHourResetLabel(epochSecond: Long?, now: Long, zone: ZoneId): String = when {
        epochSecond == null || epochSecond <= 0L -> "窗口状态未知"
        epochSecond <= now -> "窗口待激活"
        else -> resetLabel(epochSecond, now, zone)
    }

    fun notificationText(
        snapshot: QuotaResetSnapshot,
        now: Long,
        zone: ZoneId,
        evidence: FiveHourEvidence? = null,
    ): String = "5小时：${notificationFiveHourReset(snapshot.fiveHourReset, now, zone, evidence)}" +
        "｜7天：${notificationReset(snapshot.sevenDayReset, now, zone, compactToday = false)}"

    private fun notificationFiveHourReset(
        epochSecond: Long?,
        now: Long,
        zone: ZoneId,
        evidence: FiveHourEvidence?,
    ): String = when {
        evidence?.kind == FiveHourEvidenceKind.ACTIVE -> notificationReset(
            evidence.resetAt,
            now,
            zone,
            compactToday = true,
        )
        evidence?.kind == FiveHourEvidenceKind.STANDBY ||
            evidence?.kind == FiveHourEvidenceKind.AMBIGUOUS -> "等待激活"
        evidence?.kind == FiveHourEvidenceKind.UNKNOWN -> "状态未知"
        epochSecond == null || epochSecond <= 0L -> "状态未知"
        epochSecond <= now -> "等待激活"
        else -> notificationReset(epochSecond, now, zone, compactToday = true)
    }

    private fun notificationReset(
        epochSecond: Long?,
        now: Long,
        zone: ZoneId,
        compactToday: Boolean,
    ): String {
        if (epochSecond == null || epochSecond <= 0L) return "重置时间未知"
        val reset = Instant.ofEpochSecond(epochSecond).atZone(zone)
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val date = if (compactToday && reset.toLocalDate() == today) {
            ""
        } else {
            reset.format(DateTimeFormatter.ofPattern("M月d日·", Locale.SIMPLIFIED_CHINESE))
        }
        return "$date${reset.format(DateTimeFormatter.ofPattern("HH:mm"))}重置"
    }
}
