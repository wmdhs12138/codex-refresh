package com.codexrefresh.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class AutoPolicyTest {
    private val now = 1_700_000_000L

    private fun quota(percent: Double?, reset: Long?) = Quota(percent, reset)

    private fun state(
        target: Long? = now,
        attempts: Int = 0,
        successes: Int = 0,
        seeded: Boolean = true,
        enabled: Boolean = true,
    ) = AutoState(
        enabled = enabled,
        target = target,
        attempts = attempts,
        successes = successes,
        day = "2023-11-14",
        lastResult = null,
        seeded = seeded,
    )

    @Test
    fun initialEnableUsesFutureFiveHourResetWithoutAttempting() {
        val decision = AutoPolicy.enable(
            now,
            quota(0.0, now + 100),
            quota(40.0, now + 1_000),
        )

        assertEquals(AutoAction.WAIT, decision.action)
        assertEquals(now + 110, decision.target)
        assertEquals(true, decision.seeded)
    }

    @Test
    fun initialEnableWithoutFiveHourAnchorRemainsUnseeded() {
        val decision = AutoPolicy.enable(now, quota(null, null), quota(40.0, now + 1_000))

        assertEquals(AutoAction.METADATA_RETRY, decision.action)
        assertEquals(now + 300, decision.target)
        assertEquals(false, decision.seeded)
    }

    @Test
    fun initialWeeklyExhaustionCanDelayButNotCompleteSeeding() {
        val decision = AutoPolicy.enable(now, quota(null, null), quota(100.0, now + 200))

        assertEquals(AutoAction.WAIT, decision.action)
        assertEquals(now + 210, decision.target)
        assertEquals(false, decision.seeded)
    }

    @Test
    fun seededDueInactiveWindowAttempts() {
        val decision = AutoPolicy.due(
            state(),
            now,
            quota(0.0, now - 1),
            quota(40.0, now + 1_000),
        )

        assertEquals(AutoAction.ATTEMPT, decision.action)
    }

    @Test
    fun unseededDueStateNeverAttempts() {
        val decision = AutoPolicy.due(
            state(seeded = false),
            now,
            quota(0.0, now - 1),
            quota(40.0, now + 1_000),
        )

        assertEquals(AutoAction.METADATA_RETRY, decision.action)
    }

    @Test
    fun positiveWeeklyUsageAloneDoesNotBlock() {
        val decision = AutoPolicy.due(
            state(),
            now,
            quota(0.0, now - 1),
            quota(42.0, now + 10_000),
        )

        assertEquals(AutoAction.ATTEMPT, decision.action)
    }

    @Test
    fun positiveFiveHourUsageBlocksUntilItsReset() {
        val decision = AutoPolicy.due(
            state(),
            now,
            quota(1.0, now + 100),
            quota(42.0, now + 10_000),
        )

        assertEquals(AutoAction.WAIT, decision.action)
        assertEquals(now + 110, decision.target)
    }

    @Test
    fun overlappingFiveAndWeeklyBlocksUseLatestReset() {
        val decision = AutoPolicy.due(
            state(),
            now,
            quota(100.0, now + 100),
            quota(100.0, now + 200),
        )

        assertEquals(AutoAction.WAIT, decision.action)
        assertEquals(now + 210, decision.target)
    }

    @Test
    fun incompleteTelemetryFailsClosed() {
        val decision = AutoPolicy.due(state(), now, quota(null, null), quota(null, null))

        assertEquals(AutoAction.METADATA_RETRY, decision.action)
        assertEquals(now + 300, decision.target)
    }

    @Test
    fun missingPersistedTargetFailsClosed() {
        val decision = AutoPolicy.due(
            state(target = null),
            now,
            quota(0.0, now - 1),
            quota(40.0, now + 1_000),
        )

        assertEquals(AutoAction.METADATA_RETRY, decision.action)
    }

    @Test
    fun attemptsDailyGuardBlocks() {
        val decision = AutoPolicy.due(
            state(attempts = 12),
            now,
            quota(0.0, now - 1),
            quota(40.0, now + 1_000),
        )

        assertEquals(AutoAction.DAILY_LIMIT, decision.action)
        assertTrue(decision.target!! > now)
    }

    @Test
    fun successesDailyGuardBlocks() {
        val decision = AutoPolicy.due(
            state(successes = 6),
            now,
            quota(0.0, now - 1),
            quota(40.0, now + 1_000),
        )

        assertEquals(AutoAction.DAILY_LIMIT, decision.action)
    }

    @Test
    fun unknownUsesOriginalAttemptStart() {
        assertEquals(now + AUTO_LEASE, AutoPolicy.unknown(now))
    }

    @Test
    fun activeUncertaintyLeaseSurvivesOffOnTargetRecalculation() {
        assertEquals(
            now + AUTO_LEASE,
            AutoPolicy.targetWithUncertaintyLease(now + 300, now, now),
        )
        assertEquals(now, AutoPolicy.retainedAttemptStart(now, now))
    }

    @Test
    fun expiredUncertaintyLeaseDoesNotDelayNewTarget() {
        assertEquals(
            now + 300,
            AutoPolicy.targetWithUncertaintyLease(now + 300, now - AUTO_LEASE, now),
        )
        assertEquals(null, AutoPolicy.retainedAttemptStart(now - AUTO_LEASE, now))
    }

    @Test
    fun successUsesEarlierPositiveServerReset() {
        assertEquals(now + 110, AutoPolicy.success(now, now + 100))
    }

    @Test
    fun successNeverMovesLaterThanLocalCompletionAnchor() {
        assertEquals(now + AUTO_LEASE, AutoPolicy.success(now, now + AUTO_LEASE + 500))
        assertEquals(now + AUTO_LEASE, AutoPolicy.success(now, null))
    }

    @Test
    fun localDayAndMidnightAreCalendarBasedAcrossDstZone() {
        val zone = ZoneId.of("America/New_York")
        val input = ZonedDateTime.of(2024, 11, 2, 23, 45, 0, 0, zone).toEpochSecond()
        val target = AutoPolicy.midnightPlus(input, zone)
        val localTarget = Instant.ofEpochSecond(target).atZone(zone)

        assertEquals(LocalDate.of(2024, 11, 3), localTarget.toLocalDate())
        assertEquals(LocalTime.of(0, 0, 30), localTarget.toLocalTime())
        assertEquals("2024-11-02", AutoPolicy.localDay(input, zone))
    }

    @Test
    fun disabledStateNeverAttempts() {
        val decision = AutoPolicy.due(state(enabled = false), now, null, null)

        assertEquals(AutoAction.DISABLED, decision.action)
        assertFalse(decision.action == AutoAction.ATTEMPT)
    }
}
