package com.codexrefresh.app

import java.time.Instant
import java.time.ZoneId

object TimelinePresentation {
    fun windowPhase(reset: Long?, now: Long): FiveHourWindowPhase = when {
        reset == null || reset <= 0L -> FiveHourWindowPhase.UNKNOWN
        reset > now -> FiveHourWindowPhase.ACTIVE
        else -> FiveHourWindowPhase.READY
    }

    fun countdown(reset: Long?, now: Long): String = when (windowPhase(reset, now)) {
        FiveHourWindowPhase.UNKNOWN -> "—"
        FiveHourWindowPhase.READY -> "等待激活"
        FiveHourWindowPhase.ACTIVE -> duration(reset!! - now)
    }

    fun dayTimeline(
        anchor: Long?,
        now: Long,
        zone: ZoneId,
        schedule: WorkSchedule,
        autoEnabled: Boolean,
        windowPhase: FiveHourWindowPhase = windowPhase(anchor, now),
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
        // A reset timestamp proves only the current window boundary. Never
        // manufacture later windows by adding five-hour intervals: the next
        // window does not exist until another Codex request activates it.
        val confirmedWindowEnd = anchor?.takeIf { it > now && it in dayStart until dayEnd }
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
            listOfNotNull(
                confirmedWindowEnd?.let {
                    TimelineMarker(minuteOfDay(it, zone), TimelineMarkerKind.CONFIRMED)
                },
            )
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
            append(if (autoEnabled) "今天二十四小时激活计划。" else "今天二十四小时窗口状态。")
            if (workPlanEnabled) {
                append("工作时间 ${WorkSchedulePolicy.formatMinute(schedule.startMinute)} 到 ${WorkSchedulePolicy.formatMinute(schedule.endMinute)}。")
            }
            if (workPlanEnabled) {
                append("计划激活 ${baseMarkers.size} 个，")
            } else if (confirmedWindowEnd != null) {
                append("当前窗口正在倒计时，")
            } else if (windowPhase == FiveHourWindowPhase.READY) {
                append("当前窗口等待激活，")
            }
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
