package com.codexrefresh.app

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class AutoWorker(appContext: Context, params: WorkerParameters) :
    Worker(appContext, params) {

    @Volatile private var activeClient: CodexClient? = null

    override fun onStopped() {
        activeClient?.cancelProbe()
        super.onStopped()
    }

    override fun doWork(): Result {
        val store = AutoStore(applicationContext)
        val generation = inputData.getLong(AUTO_GENERATION, Long.MIN_VALUE)
        var state = store.read()
        if (!state.enabled || state.generation != generation) return Result.success()

        val tokenStore = TokenStore(applicationContext)
        val client = CodexClient()
        val workSchedule = WorkScheduleStore(applicationContext).read()
        activeClient = client
        var tokens: Tokens
        var probeStartedAt: Long? = null

        try {
            tokens = TokenCoordinator.latest(tokenStore, client) ?: run {
                store.disableIfCurrent(generation, "需要重新登录")
                cancelAuto(applicationContext)
                AutoKeepAlive.stop(applicationContext)
                return Result.success()
            }

            // Every possible model request has a fresh, disk-synchronized preflight.
            val usage = client.usage(tokens)
            state = store.read()
            if (!state.enabled || state.generation != generation) return Result.success()
            val now = System.currentTimeMillis() / 1000

            // The first worker after opt-in is metadata-only by construction.
            if (!state.seeded) {
                val seed = AutoPolicy.enable(now, usage.fiveHour, usage.weekly, workSchedule)
                val updated = store.updateIfCurrent(generation) { current ->
                    current.copy(
                        target = seed.target,
                        baseTarget = seed.baseTarget,
                        seeded = seed.seeded ?: false,
                        lastResult = seed.reason,
                        attemptStartedAt = null,
                    )
                }
                if (updated != null && seed.target != null) {
                    scheduleSuccessorIfCurrent(store, seed.target, generation)
                }
                return Result.success()
            }

            val decision = AutoPolicy.due(state, now, usage.fiveHour, usage.weekly, workSchedule)
            if (decision.action != AutoAction.ATTEMPT) {
                val target = decision.target
                if (target != null) {
                    val updated = store.updateIfCurrent(generation) { current ->
                        current.copy(
                            target = target,
                            baseTarget = decision.baseTarget ?: current.baseTarget,
                            seeded = decision.seeded ?: current.seeded,
                            lastResult = decision.reason,
                            attemptStartedAt = null,
                        )
                    }
                    if (updated != null) {
                        scheduleSuccessorIfCurrent(store, target, generation)
                    }
                }
                return Result.success()
            }

            val start = System.currentTimeMillis() / 1000
            store.beginAutomaticAttempt(generation, start) ?: return Result.success()

            // The synchronous lease above must exist before this side effect.
            if (isStopped || !isCurrent(store, generation)) return Result.success()
            probeStartedAt = start
            tokens = TokenCoordinator.latest(tokenStore, client) ?: return Result.success()
            val probe = client.probe(tokens) {
                isStopped || !isCurrent(store, generation)
            }
            if (!isCurrent(store, generation)) return Result.success()

            val completion = System.currentTimeMillis() / 1000
            // Exactly one post-success usage refresh; failure keeps the local anchor.
            val refreshed = if (probe.verified) {
                val latest = TokenCoordinator.latest(tokenStore, client)
                latest?.let {
                    tokens = it
                    runCatching { client.usage(tokens) }.getOrNull()
                }
            } else {
                null
            }
            if (!isCurrent(store, generation)) return Result.success()

            val positiveReset = refreshed?.fiveHour
                ?.takeIf { (it.percent ?: 0.0) > 0.0 }
                ?.reset
            if (probe.verified && refreshed != null) {
                QuotaResetStore(applicationContext).save(refreshed)
                AutoKeepAlive.refreshNotification(applicationContext)
            }
            val baseTarget = if (probe.verified) {
                AutoPolicy.success(completion, positiveReset)
            } else {
                AutoPolicy.unknown(start)
            }
            val target = WorkSchedulePolicy.nextActivation(baseTarget, workSchedule)
            val updated = store.updateIfCurrent(generation) { current ->
                current.copy(
                    target = target,
                    baseTarget = baseTarget,
                    successes = current.successes + if (probe.verified) 1 else 0,
                    lastResult = if (probe.verified) {
                        "自动成功，挑战匹配"
                    } else {
                        "自动完成，但挑战不匹配"
                    },
                    attemptStartedAt = null,
                    successfulActivations = if (probe.verified) {
                        (current.successfulActivations + completion).distinct().takeLast(24)
                    } else {
                        current.successfulActivations
                    },
                )
            }
            if (updated != null) scheduleSuccessorIfCurrent(store, target, generation)
        } catch (error: Exception) {
            state = store.read()
            if (!state.enabled || state.generation != generation) return Result.success()

            if (isAuthFailure(error)) {
                store.disableIfCurrent(generation, "授权已失效，请重新登录")
                cancelAuto(applicationContext)
                AutoKeepAlive.stop(applicationContext)
                return Result.success()
            }

            val now = System.currentTimeMillis() / 1000
            val persistedStart = state.attemptStartedAt
            val baseTarget = when {
                probeStartedAt != null -> AutoPolicy.unknown(probeStartedAt)
                persistedStart != null && AutoPolicy.unknown(persistedStart) > now ->
                    AutoPolicy.unknown(persistedStart)
                else -> now + 300
            }
            val target = when {
                probeStartedAt != null -> WorkSchedulePolicy.nextActivation(baseTarget, workSchedule)
                persistedStart != null && baseTarget > now -> maxOf(state.target ?: 0, baseTarget)
                else -> baseTarget
            }
            val updated = store.updateIfCurrent(generation) { current ->
                current.copy(
                    target = target,
                    baseTarget = baseTarget,
                    lastResult = bounded(error),
                    // Keep the durable start only while its uncertainty lease matters.
                    attemptStartedAt = current.attemptStartedAt
                        ?.takeIf { AutoPolicy.unknown(it) > now },
                )
            }
            if (updated != null) scheduleSuccessorIfCurrent(store, target, generation)
        } finally {
            if (activeClient === client) activeClient = null
        }
        return Result.success()
    }

    private fun scheduleSuccessorIfCurrent(
        store: AutoStore,
        target: Long,
        generation: Long,
    ) {
        if (isCurrent(store, generation)) {
            scheduleAuto(
                applicationContext,
                target,
                generation,
                appendToRunningWorker = true,
            )
        }
    }

    private fun isCurrent(store: AutoStore, generation: Long): Boolean {
        val state = store.read()
        return state.enabled && state.generation == generation
    }

    private fun isAuthFailure(error: Exception): Boolean =
        error.message?.contains("HTTP 401") == true ||
            error.message?.contains("HTTP 403") == true

    private fun bounded(error: Exception): String =
        "自动任务未完成：${redactTokenText(error.message ?: "未知错误").take(120)}"
}
