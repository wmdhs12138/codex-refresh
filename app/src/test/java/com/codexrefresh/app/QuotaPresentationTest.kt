package com.codexrefresh.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class QuotaPresentationTest {
    @Test fun convertsUsedPercentToRemainingPercent() {
        assertEquals(67.6, QuotaPresentation.remainingPercent(32.4)!!, 0.0001)
    }

    @Test fun clampsUnexpectedServerValues() {
        assertEquals(100.0, QuotaPresentation.remainingPercent(-10.0)!!, 0.0)
        assertEquals(0.0, QuotaPresentation.remainingPercent(140.0)!!, 0.0)
    }

    @Test fun leavesMissingOrInvalidUsageUnknown() {
        assertNull(QuotaPresentation.remainingPercent(null))
        assertNull(QuotaPresentation.remainingPercent(Double.NaN))
    }

    @Test fun resetLabelUsesTodayForSameLocalDate() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 9, 11, 9, 0, 0, 0, zone).toEpochSecond()
        val reset = ZonedDateTime.of(2026, 9, 11, 14, 0, 0, 0, zone).toEpochSecond()
        assertEquals("今天 14:00 重置", QuotaPresentation.resetLabel(reset, now, zone))
    }

    @Test fun resetLabelUsesCalendarDateForAnotherDay() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 9, 11, 9, 0, 0, 0, zone).toEpochSecond()
        val reset = ZonedDateTime.of(2026, 9, 15, 12, 30, 0, 0, zone).toEpochSecond()
        assertEquals("9月15日 12:30 重置", QuotaPresentation.resetLabel(reset, now, zone))
    }

    @Test fun expiredFiveHourWindowIsPresentedAsReady() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 9, 11, 14, 3, 0, 0, zone).toEpochSecond()
        val reset = ZonedDateTime.of(2026, 9, 11, 14, 2, 0, 0, zone).toEpochSecond()

        assertEquals("窗口待激活", QuotaPresentation.fiveHourResetLabel(reset, now, zone))
    }

    @Test fun notificationTextUsesPunctuationInsteadOfFragileWhitespace() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 9, 11, 9, 0, 0, 0, zone).toEpochSecond()
        val five = ZonedDateTime.of(2026, 9, 11, 17, 30, 0, 0, zone).toEpochSecond()
        val seven = ZonedDateTime.of(2026, 9, 15, 12, 0, 0, 0, zone).toEpochSecond()

        val text = QuotaPresentation.notificationText(QuotaResetSnapshot(five, seven), now, zone)
        assertEquals("5小时：17:30重置｜7天：9月15日·12:00重置", text)
    }

    @Test fun notificationStopsShowingExpiredFiveHourReset() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 9, 11, 17, 31, 0, 0, zone).toEpochSecond()
        val five = ZonedDateTime.of(2026, 9, 11, 17, 30, 0, 0, zone).toEpochSecond()
        val seven = ZonedDateTime.of(2026, 9, 15, 12, 0, 0, 0, zone).toEpochSecond()

        val text = QuotaPresentation.notificationText(QuotaResetSnapshot(five, seven), now, zone)
        assertEquals("5小时：等待激活｜7天：9月15日·12:00重置", text)
    }
}
