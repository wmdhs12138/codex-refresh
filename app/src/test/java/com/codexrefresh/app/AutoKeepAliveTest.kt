package com.codexrefresh.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AutoKeepAliveTest {
    private val now = 1_700_000_000L

    private fun state(
        enabled: Boolean = true,
        target: Long? = now + 100,
    ) = AutoState(
        enabled = enabled,
        target = target,
        attempts = 4,
        successes = 2,
        day = "2023-11-14",
        lastResult = null,
    )

    @Test
    fun notificationShowsSavedResetTimesWithoutQuotaUsage() {
        val zone = ZoneId.of("Asia/Shanghai")
        val viewedAt = ZonedDateTime.of(2026, 9, 11, 9, 0, 0, 0, zone).toEpochSecond()
        val fiveHourReset = ZonedDateTime.of(2026, 9, 11, 17, 30, 0, 0, zone).toEpochSecond()
        val sevenDayReset = ZonedDateTime.of(2026, 9, 15, 12, 0, 0, 0, zone).toEpochSecond()
        val text = KeepAlivePresentation.text(
            QuotaResetSnapshot(fiveHourReset, sevenDayReset),
            viewedAt,
            zone,
        )
        assertEquals("5小时：17:30重置｜7天：9月15日·12:00重置", text)
        assertFalse(text.contains("%"))
    }

    @Test
    fun notificationReportsStandbyAfterTheKnownWindowExpires() {
        val zone = ZoneId.of("Asia/Shanghai")
        val viewedAt = ZonedDateTime.of(2026, 9, 11, 17, 31, 0, 0, zone).toEpochSecond()
        val expiredReset = ZonedDateTime.of(2026, 9, 11, 17, 30, 0, 0, zone).toEpochSecond()
        val sevenDayReset = ZonedDateTime.of(2026, 9, 15, 12, 0, 0, 0, zone).toEpochSecond()

        val text = KeepAlivePresentation.text(
            QuotaResetSnapshot(expiredReset, sevenDayReset),
            viewedAt,
            zone,
        )

        assertEquals("5小时：等待激活｜7天：9月15日·12:00重置", text)
    }

    @Test
    fun notificationUsesEvidenceInsteadOfFloatingFutureReset() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 9, 13, 17, 18, 13, 0, zone).toEpochSecond()
        val first = QuotaObservation(
            fetchedAt = now - 12,
            source = QuotaObservationSource.APP_OPEN,
            appRequestSent = false,
            usedPercentRaw = "0",
            resetAt = now - 12 + 18_000,
            resetAfterSeconds = 18_000,
            windowSeconds = 18_000,
        )
        val second = first.copy(
            fetchedAt = now,
            source = QuotaObservationSource.WINDOW_CONFIRMATION,
            resetAt = now + 18_000,
        )
        val sevenDayReset = ZonedDateTime.of(2026, 9, 15, 12, 0, 0, 0, zone).toEpochSecond()

        val text = KeepAlivePresentation.text(
            snapshot = QuotaResetSnapshot(second.resetAt, sevenDayReset),
            now = now,
            zone = zone,
            observations = listOf(first, second),
        )

        assertEquals("5小时：等待激活｜7天：9月15日·12:00重置", text)
    }

    @Test
    fun deniedNotificationsAreNotReportedAsVisible() {
        val text = KeepAlivePresentation.notificationStatus(false)

        assertTrue(text.contains("未允许"))
        assertTrue(text.contains("不可见"))
        assertFalse(text.contains("已允许显示"))
    }

    @Test
    fun recoverySeedsMissingTargetWithoutNetwork() {
        val decision = KeepAliveRecoveryPolicy.decide(state(target = null), hasTokens = true, now = now)

        assertTrue(decision.shouldRun)
        assertEquals(now + 300, decision.target)
        assertFalse(decision.shouldDisable)
    }

    @Test
    fun recoveryDisablesPersistedOptInWithoutCredentials() {
        val decision = KeepAliveRecoveryPolicy.decide(state(), hasTokens = false, now = now)

        assertFalse(decision.shouldRun)
        assertTrue(decision.shouldDisable)
    }

    @Test
    fun recoveryDoesNothingWhenAutoIsOff() {
        val decision = KeepAliveRecoveryPolicy.decide(state(enabled = false), hasTokens = true, now = now)

        assertFalse(decision.shouldRun)
        assertFalse(decision.shouldDisable)
    }
}
