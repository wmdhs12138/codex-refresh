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
