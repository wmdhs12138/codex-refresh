package com.codexrefresh.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Presentation-only projections; no scheduler or network decisions are made here. */
data class ActivationPoint(val epochSecond: Long, val confirmed: Boolean, val text: String)

data class TimelineResult(val points: List<ActivationPoint>, val fallback: String? = null)

object TimelinePresentation {
    private const val DAY_SECONDS = 24 * 60 * 60L
    private val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)

    fun countdown(reset: Long?, now: Long): String = when {
        reset == null || reset <= 0L -> "—"
        reset <= now -> "等待重置"
        else -> duration(reset - now)
    }

    fun formatLocal(epochSecond: Long, zone: ZoneId): String {
        val dateTime = Instant.ofEpochSecond(epochSecond).atZone(zone)
        val day = dateTime.format(DateTimeFormatter.ofPattern("M月d日 E", Locale.SIMPLIFIED_CHINESE))
        return "$day ${dateTime.hour.toString().padStart(2, '0')}:${dateTime.minute.toString().padStart(2, '0')}"
    }

    /** The anchor is the only confirmed point. Projections are bounded to the next 24h. */
    fun futureTimeline(anchor: Long?, now: Long, zone: ZoneId): TimelineResult {
        if (anchor == null || anchor <= 0L) return TimelineResult(emptyList(), "缺少可信的服务器五小时锚点")
        if (anchor < now) return TimelineResult(emptyList(), "服务器锚点已过期，请刷新额度")
        val end = now + DAY_SECONDS
        val result = mutableListOf<ActivationPoint>()
        var point = anchor
        var first = true
        while (point <= end) {
            result += ActivationPoint(point, first, formatLocal(point, zone))
            first = false
            point += AUTO_LEASE
        }
        return TimelineResult(result)
    }

    fun dayTimeline(
        anchor: Long?,
        now: Long,
        zone: ZoneId,
        schedule: WorkSchedule,
        autoEnabled: Boolean,
        nextActivationEpoch: Long? = null,
        completedActivations: List<Long>,
    ): DayTimelineState {
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val dayStart = today.atStartOfDay(zone).toEpochSecond()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toEpochSecond()
        val workPlanEnabled = schedule.enabled && autoEnabled
        val workRanges = if (workPlanEnabled) workRanges(schedule) else emptyList()

        val plannedEpochs = if (workPlanEnabled) {
            listOf(today.minusDays(1), today, today.plusDays(1))
                .flatMap { WorkSchedulePolicy.activationEpochsForShift(it, schedule, zone) }
        } else {
            emptyList()
        }
        val quotaEpochs = if (anchor != null && anchor >= now) {
            projectedEpochs(anchor, dayStart, dayEnd)
        } else {
            emptyList()
        }
        val completedToday = completedActivations.filter { it in dayStart until dayEnd }

        val completedMarkers = completedToday.map {
            TimelineMarker(minuteOfDay(it, zone), TimelineMarkerKind.COMPLETED)
        }
        val baseMarkers = if (workPlanEnabled) {
            plannedEpochs
                .filter { it in dayStart until dayEnd }
                .map { TimelineMarker(minuteOfDay(it, zone), TimelineMarkerKind.PLANNED) }
                // A delayed completion replaces its nearby plan point instead of creating a visual doublet.
                .filterNot { planned ->
                    completedMarkers.any { completed ->
                        kotlin.math.abs(completed.minuteOfDay - planned.minuteOfDay) <= 60f
                    }
                }
        } else {
            quotaEpochs
                .filter { it in dayStart until dayEnd }
                .map { epoch ->
                    TimelineMarker(
                        minuteOfDay(epoch, zone),
                        if (epoch == anchor) TimelineMarkerKind.CONFIRMED else TimelineMarkerKind.ESTIMATED,
                    )
                }
        }
        val nextMarker = nextActivationEpoch
            ?.takeIf { autoEnabled && it in dayStart until dayEnd }
            ?.let { TimelineMarker(minuteOfDay(it, zone), TimelineMarkerKind.NEXT) }
        val visibleBaseMarkers = if (nextMarker == null) {
            baseMarkers
        } else {
            baseMarkers.filterNot {
                kotlin.math.abs(it.minuteOfDay - nextMarker.minuteOfDay) <= 60f
            }
        }
        val markers = (visibleBaseMarkers + listOfNotNull(nextMarker) + completedMarkers)
            .sortedBy { it.minuteOfDay }
        val localNow = Instant.ofEpochSecond(now).atZone(zone)

        val description = buildString {
            append(if (autoEnabled) "今天二十四小时激活计划。" else "今天二十四小时额度窗口。")
            if (workPlanEnabled) {
                append("工作时间 ${WorkSchedulePolicy.formatMinute(schedule.startMinute)} 到 ${WorkSchedulePolicy.formatMinute(schedule.endMinute)}。")
            }
            append("待激活 ${baseMarkers.size} 个，")
            append("已自动激活 ${completedToday.size} 个。")
        }
        return DayTimelineState(
            markers = markers,
            workRanges = workRanges,
            workLabel = if (workPlanEnabled) {
                "工作时段 ${WorkSchedulePolicy.formatMinute(schedule.startMinute)}—${WorkSchedulePolicy.formatMinute(schedule.endMinute)}"
            } else {
                null
            },
            nowMinute = localNow.hour * 60f + localNow.minute + localNow.second / 60f,
            accessibilityText = description,
        )
    }

    fun workPlanText(schedule: WorkSchedule): String {
        if (!schedule.enabled) return "工作时间优化未启用"
        val points = WorkSchedulePolicy.activationMinutes(schedule)
            .joinToString(" · ") { WorkSchedulePolicy.formatMinute(it) }
        return "推荐激活 $points"
    }

    private fun projectedEpochs(anchor: Long?, dayStart: Long, dayEnd: Long): List<Long> {
        if (anchor == null || anchor <= 0L) return emptyList()
        var first = anchor
        while (first - AUTO_LEASE >= dayStart - AUTO_FIVE_HOURS) first -= AUTO_LEASE
        val result = mutableListOf<Long>()
        var point = first
        while (point < dayEnd) {
            if (point >= dayStart - AUTO_FIVE_HOURS) result += point
            point += AUTO_LEASE
        }
        return result
    }

    private fun minuteOfDay(epoch: Long, zone: ZoneId): Float {
        val value = Instant.ofEpochSecond(epoch).atZone(zone)
        return value.hour * 60f + value.minute + value.second / 60f
    }

    private fun workRanges(schedule: WorkSchedule): List<TimelineRange> =
        if (schedule.startMinute < schedule.endMinute) {
            listOf(TimelineRange(schedule.startMinute.toFloat(), schedule.endMinute.toFloat()))
        } else {
            listOf(
                TimelineRange(0f, schedule.endMinute.toFloat()),
                TimelineRange(schedule.startMinute.toFloat(), MINUTES_PER_DAY.toFloat()),
            )
        }

    fun duration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        return if (hours > 0) "${hours}小时${minutes}分" else "${minutes}分${safe % 60}秒"
    }
}
