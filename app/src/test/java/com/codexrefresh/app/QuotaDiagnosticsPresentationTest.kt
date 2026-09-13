package com.codexrefresh.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class QuotaDiagnosticsPresentationTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun summaryIdentifiesLatestObservationAndCount() {
        val first = observation(1_700_000_000, QuotaObservationSource.APP_OPEN)
        val latest = observation(1_700_000_100, QuotaObservationSource.MANUAL_REFRESH)

        assertEquals(
            "最近：手动刷新 · 11-15 06:15:00 · 共 2 条",
            QuotaDiagnosticsPresentation.summary(listOf(first, latest), zone),
        )
    }

    @Test
    fun detailsPreserveRawPercentEpochAndFetchDelta() {
        val fetchedAt = ZonedDateTime.of(2026, 9, 13, 8, 0, 0, 0, zone).toEpochSecond()
        val entry = QuotaObservation(
            fetchedAt = fetchedAt,
            source = QuotaObservationSource.MANUAL_ACTIVATION,
            appRequestSent = true,
            usedPercentRaw = "0.0004",
            resetAt = fetchedAt + 18_000,
            resetAfterSeconds = 18_000,
            windowSeconds = 18_000,
        )

        val text = QuotaDiagnosticsPresentation.details(listOf(entry), zone)

        assertTrue(text.contains("used_percent=0.0004"))
        assertTrue(text.contains("reset_at=${fetchedAt + 18_000}"))
        assertTrue(text.contains("delta=18000s"))
        assertTrue(text.contains("app_request=yes"))
        assertTrue(text.contains("reset_after_seconds=18000"))
        assertTrue(text.contains("limit_window_seconds=18000"))
    }

    @Test
    fun movingFullWindowResetMeansStandby() {
        val entries = listOf(
            observation(1_000, QuotaObservationSource.APP_OPEN),
            observation(1_012, QuotaObservationSource.WINDOW_CONFIRMATION),
        )

        assertEquals(
            FiveHourEvidenceKind.STANDBY,
            FiveHourEvidencePolicy.evaluate(entries, 1_012).kind,
        )
    }

    @Test
    fun movingResetFromOrdinaryReadRemainsAmbiguousUntilConfirmation() {
        val entries = listOf(
            observation(1_000, QuotaObservationSource.APP_OPEN),
            observation(1_012, QuotaObservationSource.MANUAL_REFRESH),
        )

        assertEquals(
            FiveHourEvidenceKind.AMBIGUOUS,
            FiveHourEvidencePolicy.evaluate(entries, 1_012).kind,
        )
    }

    @Test
    fun stableResetAcrossReadsMeansExternallyActivated() {
        val resetAt = 19_000L
        val entries = listOf(
            observation(1_000, QuotaObservationSource.MANUAL_REFRESH).copy(
                resetAt = resetAt,
                resetAfterSeconds = 18_000,
            ),
            observation(1_012, QuotaObservationSource.WINDOW_CONFIRMATION).copy(
                resetAt = resetAt,
                resetAfterSeconds = 17_988,
            ),
        )

        val evidence = FiveHourEvidencePolicy.evaluate(entries, 1_012)
        assertEquals(FiveHourEvidenceKind.ACTIVE, evidence.kind)
        assertEquals(resetAt, evidence.resetAt)
    }

    @Test
    fun firstZeroPercentFullWindowReadNeedsConfirmation() {
        val evidence = FiveHourEvidencePolicy.evaluate(
            listOf(observation(1_000, QuotaObservationSource.APP_OPEN)),
            1_000,
        )

        assertEquals(FiveHourEvidenceKind.AMBIGUOUS, evidence.kind)
    }

    @Test
    fun zeroPercentResetAlreadyCountingDownMeansActive() {
        val evidence = FiveHourEvidencePolicy.evaluate(
            listOf(
                observation(1_008, QuotaObservationSource.MANUAL_REFRESH).copy(
                    resetAt = 19_000,
                    resetAfterSeconds = 17_992,
                ),
            ),
            1_008,
        )

        assertEquals(FiveHourEvidenceKind.ACTIVE, evidence.kind)
    }

    @Test
    fun verifiedLocalRequestIsActiveEvenAtZeroPercent() {
        val evidence = FiveHourEvidencePolicy.evaluate(
            listOf(
                observation(1_000, QuotaObservationSource.MANUAL_ACTIVATION).copy(
                    appRequestSent = true,
                ),
            ),
            1_000,
        )

        assertEquals(FiveHourEvidenceKind.ACTIVE, evidence.kind)
    }

    private fun observation(
        fetchedAt: Long,
        source: QuotaObservationSource,
    ) = QuotaObservation(
        fetchedAt = fetchedAt,
        source = source,
        appRequestSent = false,
        usedPercentRaw = "0",
        resetAt = fetchedAt + 18_000,
        resetAfterSeconds = 18_000,
        windowSeconds = 18_000,
    )
}
