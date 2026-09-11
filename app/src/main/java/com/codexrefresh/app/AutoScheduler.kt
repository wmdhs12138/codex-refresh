package com.codexrefresh.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

const val AUTO_WORK = "codex-refresh-auto"
const val AUTO_FIVE_HOURS = 18_000L
const val AUTO_LEASE = AUTO_FIVE_HOURS + 10L
const val AUTO_GENERATION = "generation"

data class AutoState(
    val enabled: Boolean,
    val target: Long?,
    val attempts: Int,
    val successes: Int,
    val day: String,
    val lastResult: String?,
    val generation: Long = 0,
    val seeded: Boolean = true,
    val attemptStartedAt: Long? = null,
    val successfulActivations: List<Long> = emptyList(),
)

enum class AutoAction { ATTEMPT, WAIT, METADATA_RETRY, DAILY_LIMIT, DISABLED }

data class AutoDecision(
    val action: AutoAction,
    val target: Long? = null,
    val reason: String = "",
    val seeded: Boolean? = null,
)

enum class ManualReservation { DISABLED, BUSY, RESERVED }

/** Pure, fail-closed policy. All timestamps are epoch seconds. */
object AutoPolicy {
    /**
     * Seed an opt-in schedule without ever making a model request. A complete
     * 5-hour observation with a future reset is a safe first target even at
     * zero usage. A weekly exhaustion gate can only move that target later.
     */
    fun enable(
        now: Long,
        five: Quota?,
        weekly: Quota?,
        workSchedule: WorkSchedule = WorkSchedule(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): AutoDecision {
        val fiveAnchor = five?.reset?.takeIf { five.percent != null && it > now }
        val weeklyGate = weekly?.reset?.takeIf {
            weekly.percent != null && weekly.percent >= 100.0 && it > now
        }
        val target = listOfNotNull(fiveAnchor, weeklyGate).maxOrNull()

        return when {
            fiveAnchor != null -> AutoDecision(
                AutoAction.WAIT,
                WorkSchedulePolicy.nextActivation(target!! + 10, workSchedule, zone),
                "首次安全窗口已安排",
                seeded = true,
            )
            target != null -> AutoDecision(
                AutoAction.WAIT,
                target + 10,
                "额度窗口仍受限；继续等待首次窗口",
                seeded = false,
            )
            else -> AutoDecision(
                AutoAction.METADATA_RETRY,
                now + 300,
                "等待完整额度信息",
                seeded = false,
            )
        }
    }

    fun due(
        state: AutoState,
        now: Long,
        five: Quota?,
        weekly: Quota?,
        workSchedule: WorkSchedule = WorkSchedule(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): AutoDecision {
        if (!state.enabled) return AutoDecision(AutoAction.DISABLED)
        if (!state.seeded) {
            return AutoDecision(AutoAction.METADATA_RETRY, now + 300, "等待首次安全窗口", false)
        }
        if (state.target == null) {
            return AutoDecision(AutoAction.METADATA_RETRY, now + 300, "缺少下次任务时间")
        }
        if (state.target > now) return AutoDecision(AutoAction.WAIT, state.target)
        if (state.attempts >= 12 || state.successes >= 6) {
            return AutoDecision(
                AutoAction.DAILY_LIMIT,
                WorkSchedulePolicy.nextActivation(midnightPlus(now, zone), workSchedule, zone),
                "已达到今日安全上限",
            )
        }

        val blockers = listOfNotNull(
            five?.reset?.takeIf {
                five.percent != null && five.percent > 0.0 && it > now
            },
            weekly?.reset?.takeIf {
                weekly.percent != null && weekly.percent >= 100.0 && it > now
            },
        )
        if (blockers.isNotEmpty()) {
            return AutoDecision(
                AutoAction.WAIT,
                WorkSchedulePolicy.nextActivation(blockers.max() + 10, workSchedule, zone),
                "额度窗口尚未开放",
            )
        }
        if (!complete(five, weekly)) {
            return AutoDecision(AutoAction.METADATA_RETRY, now + 300, "额度信息不完整")
        }
        return AutoDecision(AutoAction.ATTEMPT, now, "调度已到期")
    }

    private fun complete(five: Quota?, weekly: Quota?): Boolean =
        five?.percent != null && five.reset != null &&
            weekly?.percent != null && weekly.reset != null

    fun unknown(start: Long): Long = start + AUTO_LEASE

    fun retainedAttemptStart(start: Long?, now: Long): Long? =
        start?.takeIf { unknown(it) > now }

    fun targetWithUncertaintyLease(target: Long?, attemptStartedAt: Long?, now: Long): Long? {
        val retained = retainedAttemptStart(attemptStartedAt, now)
        return if (retained == null) target else maxOf(target ?: 0L, unknown(retained))
    }

    fun success(completion: Long, positiveReset: Long?): Long {
        val localAnchor = completion + AUTO_LEASE
        val serverAnchor = positiveReset?.takeIf { it > completion }?.plus(10)
        return serverAnchor?.let { minOf(it, localAnchor) } ?: localAnchor
    }

    fun scheduledSuccess(
        completion: Long,
        positiveReset: Long?,
        workSchedule: WorkSchedule,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long = WorkSchedulePolicy.nextActivation(
        success(completion, positiveReset),
        workSchedule,
        zone,
    )

    fun localDay(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochSecond(now).atZone(zone).toLocalDate().toString()

    fun midnightPlus(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochSecond(now)
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .plusSeconds(30)
            .toEpochSecond()
}

class AutoStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("auto_scheduler", Context.MODE_PRIVATE)

    fun read(now: Long = System.currentTimeMillis() / 1000): AutoState =
        synchronized(LOCK) { readUnlocked(now) }

    /** Synchronous single-transaction replacement. */
    fun update(state: AutoState) {
        synchronized(LOCK) { writeUnlocked(state) }
    }

    /** Atomically update only the currently enabled generation. */
    fun updateIfCurrent(
        expectedGeneration: Long,
        transform: (AutoState) -> AutoState,
    ): AutoState? = synchronized(LOCK) {
        val current = readUnlocked()
        if (!current.enabled || current.generation != expectedGeneration) {
            return@synchronized null
        }
        val next = transform(current)
        require(next.enabled && next.generation == expectedGeneration) {
            "当前自动任务更新不得改变启用代次"
        }
        writeUnlocked(next)
        next
    }

    /**
     * Recheck the due intent and persist its automatic counter/lease as one
     * transaction. A concurrent manual probe can move the target before this
     * lock is acquired and thereby prevent a duplicate automatic request.
     */
    fun beginAutomaticAttempt(expectedGeneration: Long, start: Long): AutoState? =
        synchronized(LOCK) {
            val current = readUnlocked(start)
            if (
                !current.enabled ||
                current.generation != expectedGeneration ||
                !current.seeded ||
                current.target == null ||
                current.target > start ||
                current.attempts >= 12 ||
                current.successes >= 6
            ) {
                return@synchronized null
            }
            val next = current.copy(
                target = AutoPolicy.unknown(start),
                attempts = current.attempts + 1,
                lastResult = "自动尝试进行中",
                attemptStartedAt = start,
            )
            writeUnlocked(next)
            next
        }

    /**
     * A manual request does not increment automatic counters, but while auto is
     * enabled it reserves the same uncertainty window before network output.
     */
    fun reserveManualWindowResult(start: Long): Pair<ManualReservation, AutoState?> = synchronized(LOCK) {
        val current = readUnlocked(start)
        if (!current.enabled) return@synchronized (ManualReservation.DISABLED to null)
        val started = current.attemptStartedAt
        if (started != null && start - started < AUTO_LEASE) {
            return@synchronized (ManualReservation.BUSY to current)
        }
        val leaseTarget = AutoPolicy.unknown(start)
        val next = current.copy(
            target = maxOf(current.target ?: leaseTarget, leaseTarget),
            lastResult = "手动请求进行中；自动任务已暂缓",
            attemptStartedAt = start,
        )
        writeUnlocked(next)
        ManualReservation.RESERVED to next
    }

    fun reserveManualWindow(start: Long): AutoState? =
        reserveManualWindowResult(start).let { (result, state) ->
            state.takeIf { result == ManualReservation.RESERVED }
        }

    /** Enabling creates a fresh generation while preserving today's guards. */
    fun enable(target: Long?, seeded: Boolean, status: String): AutoState =
        synchronized(LOCK) {
            val current = readUnlocked()
            val now = System.currentTimeMillis() / 1000
            val retainedAttempt = AutoPolicy.retainedAttemptStart(current.attemptStartedAt, now)
            val next = current.copy(
                enabled = true,
                target = AutoPolicy.targetWithUncertaintyLease(target, retainedAttempt, now),
                lastResult = status,
                generation = current.generation + 1,
                seeded = seeded,
                attemptStartedAt = retainedAttempt,
            )
            writeUnlocked(next)
            next
        }

    /** Disabling invalidates old workers but does not reset daily safety counts. */
    fun disable(status: String? = null): AutoState = synchronized(LOCK) {
        val current = readUnlocked()
        val now = System.currentTimeMillis() / 1000
        val next = current.copy(
            enabled = false,
            target = null,
            lastResult = status ?: current.lastResult,
            generation = current.generation + 1,
            seeded = false,
            // Cancellation is best-effort; retain an active uncertainty lease
            // across a quick off/on cycle so it cannot permit a duplicate probe.
            attemptStartedAt = AutoPolicy.retainedAttemptStart(current.attemptStartedAt, now),
        )
        writeUnlocked(next)
        next
    }

    fun disableIfCurrent(expectedGeneration: Long, status: String): AutoState? =
        synchronized(LOCK) {
            val current = readUnlocked()
            if (!current.enabled || current.generation != expectedGeneration) {
                return@synchronized null
            }
            val now = System.currentTimeMillis() / 1000
            val next = current.copy(
                enabled = false,
                target = null,
                lastResult = status,
                generation = current.generation + 1,
                seeded = false,
                attemptStartedAt = AutoPolicy.retainedAttemptStart(current.attemptStartedAt, now),
            )
            writeUnlocked(next)
            next
        }

    /** Replaces a pending activation after the user changes work-hour preferences. */
    fun reschedule(target: Long, status: String): AutoState = synchronized(LOCK) {
        val current = readUnlocked()
        require(current.enabled) { "自动任务未启用" }
        val next = current.copy(
            target = target,
            generation = current.generation + 1,
            lastResult = status,
        )
        writeUnlocked(next)
        next
    }

    private fun readUnlocked(now: Long = System.currentTimeMillis() / 1000): AutoState {
        val day = AutoPolicy.localDay(now)
        val savedDay = preferences.getString("day", day) ?: day
        val sameDay = savedDay == day
        return AutoState(
            enabled = preferences.getBoolean("enabled", false),
            target = preferences.getLong("target", -1).takeIf { it >= 0 },
            attempts = if (sameDay) preferences.getInt("attempts", 0) else 0,
            successes = if (sameDay) preferences.getInt("successes", 0) else 0,
            day = day,
            lastResult = preferences.getString("last", null),
            generation = preferences.getLong("generation", 0),
            seeded = preferences.getBoolean("seeded", false),
            attemptStartedAt = preferences.getLong("attempt_started_at", -1)
                .takeIf { it >= 0 },
            successfulActivations = preferences.getString("successful_activations", "")
                .orEmpty()
                .split(',')
                .mapNotNull { it.toLongOrNull() }
                .filter { it >= now - 3 * 86_400L },
        )
    }

    private fun writeUnlocked(state: AutoState) {
        val editor = preferences.edit()
            .putBoolean("enabled", state.enabled)
            .putLong("target", state.target ?: -1)
            .putInt("attempts", state.attempts)
            .putInt("successes", state.successes)
            .putString("day", state.day)
            .putLong("generation", state.generation)
            .putBoolean("seeded", state.seeded)
            .putLong("attempt_started_at", state.attemptStartedAt ?: -1)
            .putString(
                "successful_activations",
                state.successfulActivations.takeLast(24).joinToString(","),
            )
        state.lastResult?.let { editor.putString("last", it.take(200)) }
        check(editor.commit()) { "无法持久化自动任务状态" }
    }

    private companion object {
        val LOCK = Any()
    }
}

private fun autoRequest(target: Long, generation: Long) =
    OneTimeWorkRequestBuilder<AutoWorker>()
        .setInputData(workDataOf(AUTO_GENERATION to generation))
        .setInitialDelay(
            (target - System.currentTimeMillis() / 1000).coerceAtLeast(0),
            TimeUnit.SECONDS,
        )
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build(),
        )
        .build()

fun scheduleAuto(
    context: Context,
    target: Long,
    generation: Long,
    appendToRunningWorker: Boolean = false,
) {
    val policy = if (appendToRunningWorker) {
        ExistingWorkPolicy.APPEND_OR_REPLACE
    } else {
        ExistingWorkPolicy.REPLACE
    }
    WorkManager.getInstance(context)
        .enqueueUniqueWork(AUTO_WORK, policy, autoRequest(target, generation))
}

/**
 * Watchdog-only repair: KEEP leaves a pending/running unique worker untouched.
 * Target changes and worker successors use the explicit policies above; this
 * function never cancels, appends, or creates periodic work.
 */
fun ensureAutoScheduled(context: Context, target: Long, generation: Long) {
    WorkManager.getInstance(context)
        .enqueueUniqueWork(AUTO_WORK, ExistingWorkPolicy.KEEP, autoRequest(target, generation))
}

fun cancelAuto(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork(AUTO_WORK)
}
