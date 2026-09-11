package com.codexrefresh.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WorkSchedulePolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val office = WorkSchedule(enabled = true, startMinute = 8 * 60 + 30, endMinute = 17 * 60 + 30)

    @Test fun nineHourDayUsesPreActivationAndThreeQuotaBuckets() {
        assertEquals(
            listOf(5 * 60 + 30, 10 * 60 + 30, 15 * 60 + 30),
            WorkSchedulePolicy.activationMinutes(office),
        )
    }

    @Test fun nextActivationCanUseTenSecondServerSafetyAtSameSlot() {
        val points = WorkSchedulePolicy.activationEpochsForShift(LocalDate.of(2026, 9, 10), office, zone)
        assertEquals(points[1] + 10, WorkSchedulePolicy.nextActivation(points[1] + 10, office, zone))
    }

    @Test fun nextActivationSkipsMissedPlanPoint() {
        val points = WorkSchedulePolicy.activationEpochsForShift(LocalDate.of(2026, 9, 10), office, zone)
        assertEquals(points[2], WorkSchedulePolicy.nextActivation(points[1] + 601, office, zone))
    }

    @Test fun overnightShiftStillProducesMaximumOverlappingBuckets() {
        val overnight = WorkSchedule(enabled = true, startMinute = 22 * 60, endMinute = 6 * 60)
        val points = WorkSchedulePolicy.activationEpochsForShift(LocalDate.of(2026, 9, 10), overnight, zone)
        assertEquals(3, points.size)
        assertTrue(points.zipWithNext().all { (first, second) -> second - first == AUTO_FIVE_HOURS })
    }
}
