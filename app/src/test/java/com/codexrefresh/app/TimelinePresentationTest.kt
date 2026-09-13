package com.codexrefresh.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class TimelinePresentationTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun expiredWindowBecomesReadyInsteadOfStartingAnotherCountdown() {
        assertEquals("等待激活", TimelinePresentation.countdown(1000, 1000))
        assertEquals("等待激活", TimelinePresentation.countdown(999, 1000))
        assertEquals(FiveHourWindowPhase.READY, TimelinePresentation.windowPhase(999, 1000))
    }

    @Test fun missingWindowMetadataRemainsUnknown() {
        assertEquals("—", TimelinePresentation.countdown(null, 1000))
        assertEquals(FiveHourWindowPhase.UNKNOWN, TimelinePresentation.windowPhase(null, 1000))
    }

    @Test fun dayTimelineShowsPlannedAndCompletedMarkers() {
        val now = java.time.ZonedDateTime.of(2026, 9, 10, 12, 0, 0, 0, zone).toEpochSecond()
        val completed = java.time.ZonedDateTime.of(2026, 9, 10, 5, 30, 0, 0, zone).toEpochSecond()
        val result = TimelinePresentation.dayTimeline(
            anchor = now + 3600,
            now = now,
            zone = zone,
            schedule = WorkSchedule(true, 8 * 60 + 30, 17 * 60 + 30),
            autoEnabled = true,
            completedActivations = listOf(completed),
        )

        assertEquals(2, result.markers.count { it.kind == TimelineMarkerKind.PLANNED })
        assertEquals(1, result.markers.count { it.kind == TimelineMarkerKind.COMPLETED })
        assertEquals(3, result.markers.size)
        assertEquals(1, result.workRanges.size)
        assertEquals(12 * 60f, result.nowMinute!!, 0.01f)
    }

    @Test fun disabledAutoShowsOnlyTheConfirmedCurrentWindowEnd() {
        val now = java.time.ZonedDateTime.of(2026, 9, 10, 8, 0, 0, 0, zone).toEpochSecond()
        val result = TimelinePresentation.dayTimeline(
            anchor = now + 3600,
            now = now,
            zone = zone,
            schedule = WorkSchedule(true, 8 * 60 + 30, 17 * 60 + 30),
            autoEnabled = false,
            completedActivations = emptyList(),
        )

        assertEquals(1, result.markers.size)
        assertEquals(TimelineMarkerKind.CONFIRMED, result.markers.single().kind)
        assertTrue(result.workRanges.isEmpty())
    }

    @Test fun expiredWindowDoesNotProjectAnotherFiveHourWindow() {
        val now = java.time.ZonedDateTime.of(2026, 9, 10, 14, 3, 0, 0, zone).toEpochSecond()
        val result = TimelinePresentation.dayTimeline(
            anchor = now - 60,
            now = now,
            zone = zone,
            schedule = WorkSchedule(),
            autoEnabled = false,
            completedActivations = emptyList(),
        )

        assertTrue(result.markers.isEmpty())
        assertTrue(result.accessibilityText.contains("等待激活"))
    }

    @Test fun nextActivationReplacesNearbyPlanPointWithNextMarker() {
        val now = java.time.ZonedDateTime.of(2026, 9, 10, 8, 0, 0, 0, zone).toEpochSecond()
        val schedule = WorkSchedule(true, 8 * 60 + 30, 17 * 60 + 30)
        val next = WorkSchedulePolicy.activationEpochsForShift(
            java.time.LocalDate.of(2026, 9, 10),
            schedule,
            zone,
        )[1] + 10
        val result = TimelinePresentation.dayTimeline(
            anchor = now + 3600,
            now = now,
            zone = zone,
            schedule = schedule,
            autoEnabled = true,
            nextActivationEpoch = next,
            completedActivations = emptyList(),
        )

        assertEquals(1, result.markers.count { it.kind == TimelineMarkerKind.NEXT })
        assertEquals(2, result.markers.count { it.kind == TimelineMarkerKind.PLANNED })
        assertEquals("工作时段 08:30—17:30", result.workLabel)
    }
}
