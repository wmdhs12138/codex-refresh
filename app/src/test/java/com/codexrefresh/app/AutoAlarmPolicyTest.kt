package com.codexrefresh.app

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoAlarmPolicyTest {
    @Test
    fun preAndroid12UsesExactSchedulingWithoutSpecialAccess() {
        assertEquals(
            AutoAlarmPrecision.EXACT,
            AutoAlarmPolicy.precision(Build.VERSION_CODES.R, exactAllowed = false),
        )
    }

    @Test
    fun android12FallsBackWhenExactAccessIsMissing() {
        assertEquals(
            AutoAlarmPrecision.INEXACT,
            AutoAlarmPolicy.precision(Build.VERSION_CODES.S, exactAllowed = false),
        )
    }

    @Test
    fun grantedAccessUsesExactScheduling() {
        assertEquals(
            AutoAlarmPrecision.EXACT,
            AutoAlarmPolicy.precision(Build.VERSION_CODES.S, exactAllowed = true),
        )
    }

    @Test
    fun overdueAlarmIsMovedOneSecondIntoFuture() {
        assertEquals(11_000L, AutoAlarmPolicy.triggerAtMillis(9L, 10_000L))
    }
}
