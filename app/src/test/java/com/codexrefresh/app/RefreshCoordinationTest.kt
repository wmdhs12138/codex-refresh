package com.codexrefresh.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshCoordinationTest {
    private val old = Tokens("old-access", "old-refresh", 1L)
    private val fresh = Tokens("fresh-access", "fresh-refresh", 2L)
    private val login = Tokens("login-access", "login-refresh", 3L)

    @Test
    fun inFlightRefreshMakesAnotherCallerWait() {
        val result = RefreshCoordinationPolicy.begin(true, old, 0L, 60_000L)

        assertEquals(RefreshCoordinationPolicy.BeginAction.WAIT, result.action)
    }

    @Test
    fun validTokenDoesNotStartRefresh() {
        val result = RefreshCoordinationPolicy.begin(false, old.copy(expiresAt = 100_000L), 0L, 60_000L)

        assertEquals(RefreshCoordinationPolicy.BeginAction.USE_CURRENT, result.action)
    }

    @Test
    fun expiredTokenStartsExactlyOneRefreshDecision() {
        val result = RefreshCoordinationPolicy.begin(false, old, 0L, 60_000L)

        assertEquals(RefreshCoordinationPolicy.BeginAction.START_REFRESH, result.action)
    }

    @Test
    fun successfulRefreshIsCommittedOnlyForItsRevision() {
        val result = RefreshCoordinationPolicy.finish(4L, 4L, fresh, old)

        assertTrue(result.persist)
        assertEquals(fresh, result.tokens)
        assertFalse(result.propagateError)
    }

    @Test
    fun staleRefreshCannotReplaceNewLoginOrClear() {
        val loginResult = RefreshCoordinationPolicy.finish(4L, 5L, fresh, login)
        val logout = RefreshCoordinationPolicy.finish(4L, 5L, fresh, null)

        assertFalse(loginResult.persist)
        assertEquals(login, loginResult.tokens)
        assertFalse(loginResult.propagateError)
        assertFalse(logout.persist)
        assertEquals(null, logout.tokens)
        assertFalse(logout.propagateError)
    }

    @Test
    fun failedStaleRefreshReturnsNewDiskCredentials() {
        val result = RefreshCoordinationPolicy.finish(4L, 5L, null, fresh)

        assertFalse(result.persist)
        assertEquals(fresh, result.tokens)
        assertFalse(result.propagateError)
    }

    @Test
    fun failedCurrentRefreshStillPropagatesItsError() {
        val result = RefreshCoordinationPolicy.finish(4L, 4L, null, old)

        assertFalse(result.persist)
        assertEquals(old, result.tokens)
        assertTrue(result.propagateError)
    }
}
