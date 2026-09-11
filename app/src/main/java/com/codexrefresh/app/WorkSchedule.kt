package com.codexrefresh.app

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

const val MINUTES_PER_DAY = 24 * 60

data class WorkSchedule(
    val enabled: Boolean = false,
    val startMinute: Int = 8 * 60 + 30,
    val endMinute: Int = 17 * 60 + 30,
) {
    init {
        require(startMinute in 0 until MINUTES_PER_DAY)
        require(endMinute in 0 until MINUTES_PER_DAY)
        require(startMinute != endMinute)
    }

    val durationMinutes: Int
        get() = (endMinute - startMinute + MINUTES_PER_DAY) % MINUTES_PER_DAY
}

class WorkScheduleStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("work_schedule", Context.MODE_PRIVATE)

    fun read(): WorkSchedule {
        val start = preferences.getInt("start_minute", 8 * 60 + 30)
            .takeIf { it in 0 until MINUTES_PER_DAY } ?: 8 * 60 + 30
        val savedEnd = preferences.getInt("end_minute", 17 * 60 + 30)
            .takeIf { it in 0 until MINUTES_PER_DAY } ?: 17 * 60 + 30
        val end = savedEnd.takeIf { it != start } ?: (start + 9 * 60) % MINUTES_PER_DAY
        return WorkSchedule(
            enabled = preferences.getBoolean("enabled", false),
            startMinute = start,
            endMinute = end,
        )
    }

    fun write(schedule: WorkSchedule) {
        check(
            preferences.edit()
                .putBoolean("enabled", schedule.enabled)
                .putInt("start_minute", schedule.startMinute)
                .putInt("end_minute", schedule.endMinute)
                .commit(),
        ) { "无法保存上班时间" }
    }
}

/** Calendar-based work-window projection. Network safety constraints stay in AutoPolicy. */
object WorkSchedulePolicy {
    private const val PLAN_GRACE_SECONDS = 10 * 60L
    /**
     * Places reset boundaries inside the shift so the first and last partial
     * windows are equally useful. For 08:30-17:30 this yields activations at
     * 05:30, 10:30 and 15:30: three quota buckets overlap the work day.
     */
    fun activationMinutes(schedule: WorkSchedule): List<Int> {
        val durationSeconds = schedule.durationMinutes * 60L
        val resetCount = (durationSeconds + AUTO_FIVE_HOURS - 1) / AUTO_FIVE_HOURS
        val edgeSeconds = (durationSeconds - (resetCount - 1) * AUTO_FIVE_HOURS) / 2
        val firstOffset = edgeSeconds - AUTO_FIVE_HOURS
        return (0L..resetCount).map { index ->
            val absoluteSeconds = schedule.startMinute * 60L + firstOffset + index * AUTO_FIVE_HOURS
            Math.floorMod(Math.floorDiv(absoluteSeconds, 60L), MINUTES_PER_DAY.toLong()).toInt()
        }
    }

    /** Returns the first recommended activation no earlier than [earliest]. */
    fun nextActivation(
        earliest: Long,
        schedule: WorkSchedule,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        if (!schedule.enabled) return earliest
        val localDate = Instant.ofEpochSecond(earliest).atZone(zone).toLocalDate()
        for (dayOffset in -1L..8L) {
            val shiftDate = localDate.plusDays(dayOffset)
            for (point in activationEpochsForShift(shiftDate, schedule, zone)) {
                // Reset safety, request duration and modest OS delay should not
                // discard an otherwise useful planned quota bucket.
                if (point >= earliest || earliest - point <= PLAN_GRACE_SECONDS) {
                    return maxOf(point, earliest)
                }
            }
        }
        return earliest
    }

    fun activationEpochsForShift(
        shiftDate: LocalDate,
        schedule: WorkSchedule,
        zone: ZoneId,
    ): List<Long> {
        val shiftStart = shiftDate.atStartOfDay(zone)
            .plusMinutes(schedule.startMinute.toLong())
        val durationSeconds = schedule.durationMinutes * 60L
        val resetCount = (durationSeconds + AUTO_FIVE_HOURS - 1) / AUTO_FIVE_HOURS
        val edgeSeconds = (durationSeconds - (resetCount - 1) * AUTO_FIVE_HOURS) / 2
        val firstOffset = edgeSeconds - AUTO_FIVE_HOURS
        return (0L..resetCount).map { index ->
            shiftStart.plusSeconds(firstOffset + index * AUTO_FIVE_HOURS).toEpochSecond()
        }
    }

    fun formatMinute(minute: Int): String =
        "%02d:%02d".format(minute / 60, minute % 60)
}
