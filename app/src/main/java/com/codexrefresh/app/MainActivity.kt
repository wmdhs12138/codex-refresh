package com.codexrefresh.app

import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val operationGeneration = AtomicLong(0)
    @Volatile private var cancelled = false
    @Volatile private var tokens: Tokens? = null
    private lateinit var client: CodexClient
    private lateinit var store: TokenStore
    private lateinit var autoStore: AutoStore
    private lateinit var quotaResetStore: QuotaResetStore
    private lateinit var workScheduleStore: WorkScheduleStore
    private var latestUsage: Usage? = null
    private var deviceCode: String? = null
    private var autoRestoreAttempted = false
    private var lastAutoRestoreAttemptElapsed = 0L
    private var notificationPermissionInFlight = false
    private var keepAliveStartFailed = false
    private var expressiveState by mutableStateOf(ExpressiveHomeState())

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationPermissionInFlight = false
        renderAuto()
    }

    private val ticker = Handler(Looper.getMainLooper())
    private var renderedDay: String? = null
    private val tick = object : Runnable {
        override fun run() {
            val day = AutoPolicy.localDay(System.currentTimeMillis() / 1000)
            if (day != renderedDay) {
                renderedDay = day
                renderAuto()
            } else {
                renderTiming()
            }
            ticker.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = CodexClient()
        store = TokenStore(this)
        autoStore = AutoStore(this)
        quotaResetStore = QuotaResetStore(this)
        workScheduleStore = WorkScheduleStore(this)
        setContent {
            CodexExpressiveTheme {
                ExpressiveHomeScreen(
                    state = expressiveState,
                    actions = ExpressiveHomeActions(
                        connect = { if (tokens == null) login() else refresh() },
                        probe = ::probe,
                        copyCode = ::copyDeviceCode,
                        logout = ::confirmLogout,
                        toggleAuto = ::setAuto,
                        toggleWorkSchedule = { checked ->
                            updateWorkSchedule(workScheduleStore.read().copy(enabled = checked))
                        },
                        pickWorkStart = { pickWorkTime(pickingStart = true) },
                        pickWorkEnd = { pickWorkTime(pickingStart = false) },
                        toggleContext = {
                            expressiveState = expressiveState.copy(
                                contextExpanded = !expressiveState.contextExpanded,
                            )
                        },
                        openBackgroundSettings = ::openBackgroundSettings,
                    ),
                )
            }
        }

        tokens = store.load()
        if (tokens == null && autoStore.read().enabled) {
            autoStore.disable("需要重新登录")
            cancelAuto(this)
            AutoKeepAlive.stop(this)
        }
        if (tokens == null) {
            renderDisconnected()
        } else {
            expressiveState = expressiveState.copy(logoutVisible = true)
            refresh()
        }
        renderAuto()
    }

    private fun login() {
        val operation = beginOperation("正在准备设备登录…") ?: return
        executor.execute {
            try {
                val device = client.beginDeviceAuth()
                if (!isCurrent(operation)) return@execute
                updateFor(operation) {
                    deviceCode = device.code
                    expressiveState = expressiveState.copy(
                        statusText = getString(R.string.device_login, device.code),
                        statusTone = StatusTone.NEUTRAL,
                        statusVisible = false,
                        actionFeedback = "浏览器已打开，请完成设备登录",
                        actionFeedbackTone = StatusTone.NEUTRAL,
                        actionFeedbackId = expressiveState.actionFeedbackId + 1,
                        deviceCode = device.code,
                        connectLabel = "等待登录…",
                    )
                    openBrowser()
                }
                val result = client.finishDeviceAuth(device) { !isCurrent(operation) }
                if (!isCurrent(operation)) return@execute
                store.save(result)
                tokens = result
                if (!isCurrent(operation)) {
                    store.clear()
                    tokens = null
                    return@execute
                }
                deviceCode = null
                fetchUsageAndRender(operation)
            } catch (e: Exception) {
                updateFor(operation) { fail(e) }
            } finally {
                finishOperation(operation)
            }
        }
    }

    private fun refresh() {
        if (tokens == null) return
        val operation = beginOperation("正在读取真实额度…") ?: return
        expressiveState = expressiveState.copy(logoutVisible = true)
        executor.execute {
            try {
                val fresh = TokenCoordinator.latest(store, client)
                    ?: error("需要重新登录")
                if (!isCurrent(operation)) return@execute
                tokens = fresh
                fetchUsageAndRender(operation)
            } catch (e: Exception) {
                updateFor(operation) { fail(e) }
            } finally {
                finishOperation(operation)
            }
        }
    }

    private fun fetchUsageAndRender(operation: Long, statusMessage: String? = null) {
        val requestTokens = TokenCoordinator.latest(store, client)
            ?: error("需要重新登录")
        val usage = client.usage(requestTokens)
        tokens = requestTokens
        updateFor(operation) {
            latestUsage = usage
            quotaResetStore.save(usage)
            AutoKeepAlive.refreshNotification(this)
            deviceCode = null
            expressiveState = expressiveState.copy(
                statusText = statusMessage ?: "● 已连接 Codex",
                statusTone = StatusTone.SUCCESS,
                statusVisible = false,
                actionFeedback = statusMessage ?: "额度已刷新",
                actionFeedbackTone = StatusTone.SUCCESS,
                actionFeedbackId = expressiveState.actionFeedbackId + 1,
                deviceCode = null,
                connectLabel = "重新读取额度",
                logoutVisible = true,
                probeVisible = true,
            )
            renderTiming()
        }
    }

    private fun probe() {
        if (tokens == null) return
        val usage = latestUsage
        val now = System.currentTimeMillis() / 1000
        fun exhausted(quota: Quota) = (quota.percent ?: 0.0) >= 100.0 && (quota.reset ?: 0) > now
        if (usage != null && (exhausted(usage.fiveHour) || exhausted(usage.weekly))) {
            updateActionStatus("额度已耗尽，暂不发送激活请求", StatusTone.ERROR)
            return
        }
        val operation = beginOperation("正在发送激活请求…") ?: return
        executor.execute {
            var manualLease: AutoState? = null
            try {
                var fresh = TokenCoordinator.latest(store, client)
                    ?: error("需要重新登录")
                if (!isCurrent(operation)) return@execute
                val manualStart = System.currentTimeMillis() / 1000
                val reservation = autoStore.reserveManualWindowResult(manualStart)
                if (reservation.first == ManualReservation.BUSY) {
                    updateFor(operation) {
                        updateActionStatus(getString(R.string.auto_busy), StatusTone.ACCENT)
                    }
                    return@execute
                }
                manualLease = reservation.second
                manualLease?.let { lease ->
                    scheduleAuto(this@MainActivity, lease.target!!, lease.generation)
                }
                fresh = TokenCoordinator.latest(store, client)
                    ?: error("需要重新登录")
                tokens = fresh
                val result = client.probe(fresh) { !isCurrent(operation) }
                if (!isCurrent(operation)) return@execute
                val postUsageAttempt = if (result.verified) {
                    val postTokens = TokenCoordinator.latest(store, client)
                    postTokens?.let {
                        fresh = it
                        runCatching { client.usage(it) }
                    }
                } else {
                    null
                }
                val postUsage = postUsageAttempt?.getOrNull()
                val expected = "PI_ANDROID_KICK_${result.challenge}"
                val verification = if (result.verified) "匹配" else "不匹配"
                val requestTime = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
                val totalTokens = result.inputTokens + result.outputTokens
                val contextPercent = totalTokens.toDouble() * 100.0 / CODEX_CONTEXT_REFERENCE
                val rateLimits = result.rateLimitHeaders.entries.joinToString(" · ") { "${it.key}=${it.value}" }
                val contextText = buildString {
                    append("上次请求：输入 ${result.inputTokens} · 输出 ${result.outputTokens} · 缓存 ${result.cachedTokens}\n")
                    append("挑战验证：$verification（$expected）\n")
                    append("模型：${result.model} · $totalTokens/$CODEX_CONTEXT_REFERENCE tokens（${String.format(Locale.US, "%.3f", contextPercent)}%）\n")
                    append("容量为 Pi 当前 Codex 元数据参考值，不是官方产品承诺\n")
                    append("时间：$requestTime\n")
                    if (rateLimits.isNotBlank()) append("响应限流头：$rateLimits\n")
                    append("仅代表本次请求，不是持久会话上下文")
                }
                val requestStatus = if (result.verified) {
                    "● 激活请求成功，挑战匹配"
                } else {
                    "● 激活请求完成，但挑战不匹配"
                }
                updateFor(operation) {
                    val statusText = when {
                        postUsage != null -> "$requestStatus · 额度已刷新"
                        postUsageAttempt?.isFailure == true -> {
                            val detail = redactTokenText(
                                postUsageAttempt.exceptionOrNull()?.message ?: "未知错误",
                            ).take(120)
                            "$requestStatus · 额度刷新失败：$detail"
                        }
                        else -> requestStatus
                    }
                    expressiveState = expressiveState.copy(
                        statusText = statusText,
                        statusTone = if (result.verified) StatusTone.SUCCESS else StatusTone.ACCENT,
                        statusVisible = false,
                        actionFeedback = statusText.removePrefix("● "),
                        actionFeedbackTone = if (result.verified) StatusTone.SUCCESS else StatusTone.ACCENT,
                        actionFeedbackId = expressiveState.actionFeedbackId + 1,
                        contextSummary = if (result.verified) {
                            getString(R.string.context_success)
                        } else {
                            getString(R.string.context_unverified)
                        },
                        contextDetails = contextText,
                        contextAvailable = true,
                    )
                    if (postUsage != null) {
                        latestUsage = postUsage
                        quotaResetStore.save(postUsage)
                        AutoKeepAlive.refreshNotification(this)
                    }

                    val expectedGeneration = manualLease?.generation
                        ?: autoStore.read().takeIf { it.enabled }?.generation
                    if (result.verified && expectedGeneration != null) {
                        val completion = System.currentTimeMillis() / 1000
                        val positiveReset = postUsage?.fiveHour
                            ?.takeIf { (it.percent ?: 0.0) > 0.0 }
                            ?.reset
                        val target = AutoPolicy.scheduledSuccess(
                            completion,
                            positiveReset,
                            workScheduleStore.read(),
                        )
                        val updated = autoStore.updateIfCurrent(expectedGeneration) { auto ->
                            auto.copy(
                                target = target,
                                seeded = true,
                                lastResult = "手动成功，已推迟自动任务",
                                attemptStartedAt = null,
                            )
                        }
                        if (updated != null) {
                            scheduleAuto(this@MainActivity, target, updated.generation)
                        }
                    } else if (manualLease != null) {
                        val lease = manualLease!!
                        val updated = autoStore.updateIfCurrent(lease.generation) { auto ->
                            auto.copy(
                                lastResult = "手动请求完成但挑战不匹配；自动任务继续暂缓",
                                attemptStartedAt = null,
                            )
                        }
                        if (updated?.target != null) {
                            scheduleAuto(this@MainActivity, updated.target, updated.generation)
                        }
                    }
                    renderAuto()
                }
            } catch (e: Exception) {
                manualLease?.let { lease ->
                    val updated = autoStore.updateIfCurrent(lease.generation) { auto ->
                        auto.copy(lastResult = "手动请求结果不确定；自动任务保守暂缓")
                    }
                    if (updated?.target != null) {
                        runCatching {
                            scheduleAuto(this@MainActivity, updated.target, updated.generation)
                        }
                    }
                }
                updateFor(operation) { fail(e) }
            } finally {
                finishOperation(operation)
            }
        }
    }

    private fun setAuto(enabled: Boolean) {
        if (!enabled) {
            autoStore.disable()
            cancelAuto(this)
            AutoKeepAlive.stop(this)
            renderAuto()
            return
        }
        if (tokens == null) {
            updateStatus(getString(R.string.connect_required), StatusTone.ERROR)
            return
        }
        val now = System.currentTimeMillis() / 1000
        val decision = AutoPolicy.enable(
            now,
            latestUsage?.fiveHour,
            latestUsage?.weekly,
            workScheduleStore.read(),
        )
        val enabledState = autoStore.enable(
            target = decision.target,
            seeded = decision.seeded ?: false,
            status = "已启用；${decision.reason}",
        )
        enabledState.target?.let { scheduleAuto(this, it, enabledState.generation) }
        autoRestoreAttempted = true
        lastAutoRestoreAttemptElapsed = SystemClock.elapsedRealtime()
        keepAliveStartFailed = !AutoKeepAlive.start(this)
        requestNotificationsPermissionIfNeeded()
        if (keepAliveStartFailed) {
            updateStatus(getString(R.string.keep_alive_start_failed), StatusTone.ERROR)
        }
        renderAuto()
    }

    private fun renderAuto() = refreshDerivedUi()

    private fun renderTiming() = refreshDerivedUi()

    private fun pickWorkTime(pickingStart: Boolean) {
        val current = workScheduleStore.read()
        val minute = if (pickingStart) current.startMinute else current.endMinute
        TimePickerDialog(
            this,
            { _, hour, selectedMinute ->
                val value = hour * 60 + selectedMinute
                val start = if (pickingStart) value else current.startMinute
                val end = if (pickingStart) current.endMinute else value
                if (start == end) {
                    updateStatus(getString(R.string.work_time_equal_error), StatusTone.ERROR)
                } else {
                    updateWorkSchedule(current.copy(startMinute = start, endMinute = end))
                }
            },
            minute / 60,
            minute % 60,
            true,
        ).show()
    }

    private fun updateWorkSchedule(schedule: WorkSchedule) {
        workScheduleStore.write(schedule)
        val state = autoStore.read()
        if (state.enabled && state.seeded && state.target != null) {
            val now = System.currentTimeMillis() / 1000
            val safeTarget = maxOf(state.target, now)
            val target = WorkSchedulePolicy.nextActivation(safeTarget, schedule)
            val updated = autoStore.reschedule(target, "上班时间计划已更新")
            scheduleAuto(this, target, updated.generation)
        }
        renderAuto()
    }

    override fun onStart() {
        super.onStart()
        ticker.removeCallbacks(tick)
        ticker.post(tick)
    }

    override fun onStop() {
        ticker.removeCallbacks(tick)
        if (!notificationPermissionInFlight) autoRestoreAttempted = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        restoreVisibleAutoServiceIfNeeded()
        renderAuto()
        if (tokens != null && !busy.get()) refresh()
    }

    private fun restoreVisibleAutoServiceIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        if (
            autoRestoreAttempted ||
            (lastAutoRestoreAttemptElapsed != 0L &&
                now - lastAutoRestoreAttemptElapsed < AUTO_RESTORE_COOLDOWN_MS) ||
            tokens == null ||
            !autoStore.read().enabled
        ) return
        autoRestoreAttempted = true
        lastAutoRestoreAttemptElapsed = now
        val restored = AutoKeepAlive.restore(this)
        keepAliveStartFailed = !restored && autoStore.read().enabled
        if (keepAliveStartFailed) {
            updateStatus(getString(R.string.keep_alive_start_failed), StatusTone.ERROR)
        }
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logout_confirm_title)
            .setMessage(R.string.logout_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.logout_confirm_action) { _, _ -> logout() }
            .show()
    }

    private fun logout() {
        autoStore.disable("已退出登录")
        cancelAuto(this)
        AutoKeepAlive.stop(this)
        cancelled = true
        operationGeneration.incrementAndGet()
        client.cancelProbe()
        store.clear()
        quotaResetStore.clear()
        tokens = null
        latestUsage = null
        deviceCode = null
        busy.set(false)
        renderDisconnected()
    }

    private fun copyDeviceCode() {
        deviceCode?.let { code ->
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Codex 验证码", code))
            updateActionStatus("验证码已复制，请在浏览器完成登录", StatusTone.SUCCESS)
        }
    }

    private fun beginOperation(message: String): Long? {
        if (!busy.compareAndSet(false, true)) return null
        cancelled = false
        val operation = operationGeneration.incrementAndGet()
        setBusy(message)
        return operation
    }

    private fun isCurrent(operation: Long): Boolean =
        !cancelled && operationGeneration.get() == operation && !isFinishing && !isDestroyed

    private fun finishOperation(operation: Long) {
        if (operationGeneration.get() != operation) return
        busy.set(false)
        updateFor(operation) {
            expressiveState = expressiveState.copy(
                connectEnabled = true,
                probeEnabled = tokens != null,
            )
        }
    }

    private inline fun updateFor(operation: Long, crossinline action: () -> Unit) {
        if (!isCurrent(operation)) return
        runOnUiThread {
            if (isCurrent(operation)) {
                action()
                refreshDerivedUi()
            }
        }
    }

    private fun openBrowser() =
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(client.verificationUrl())))

    private fun requestNotificationsPermissionIfNeeded() {
        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionInFlight = true
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openBackgroundSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun setBusy(message: String) {
        expressiveState = expressiveState.copy(
            statusText = getString(R.string.busy_status, message),
            statusTone = StatusTone.NEUTRAL,
            statusVisible = false,
            actionFeedback = message,
            actionFeedbackTone = StatusTone.NEUTRAL,
            actionFeedbackId = expressiveState.actionFeedbackId + 1,
            connectEnabled = false,
            probeEnabled = false,
        )
        refreshDerivedUi()
    }

    private fun renderDisconnected() {
        expressiveState = expressiveState.copy(
            statusText = getString(R.string.status_disconnected),
            statusTone = StatusTone.NEUTRAL,
            statusVisible = false,
            actionFeedback = null,
            deviceCode = null,
            usage = null,
            contextSummary = getString(R.string.context_empty),
            contextDetails = "",
            contextAvailable = false,
            contextExpanded = false,
            connectLabel = getString(R.string.connect),
            connectEnabled = true,
            probeVisible = false,
            probeEnabled = false,
            logoutVisible = false,
        )
        renderAuto()
    }

    private fun fail(error: Exception) {
        if (isAuthFailure(error)) {
            autoStore.disable("授权已失效，请重新登录")
            cancelAuto(this)
            AutoKeepAlive.stop(this)
        }
        val message = getString(R.string.connection_failed, safeMessage(error))
        expressiveState = expressiveState.copy(
            statusText = message,
            statusTone = StatusTone.ERROR,
            statusVisible = false,
            actionFeedback = message,
            actionFeedbackTone = StatusTone.ERROR,
            actionFeedbackId = expressiveState.actionFeedbackId + 1,
            connectLabel = if (tokens == null) getString(R.string.connect) else "重新读取额度",
            logoutVisible = tokens != null,
        )
    }

    private fun updateStatus(text: String, tone: StatusTone) {
        expressiveState = expressiveState.copy(
            statusText = text,
            statusTone = tone,
            statusVisible = true,
        )
        refreshDerivedUi()
    }

    private fun updateActionStatus(text: String, tone: StatusTone) {
        expressiveState = expressiveState.copy(
            statusText = text,
            statusTone = tone,
            statusVisible = false,
            actionFeedback = text.removePrefix("● "),
            actionFeedbackTone = tone,
            actionFeedbackId = expressiveState.actionFeedbackId + 1,
        )
        refreshDerivedUi()
    }

    private fun refreshDerivedUi() {
        if (!::autoStore.isInitialized || !::workScheduleStore.isInitialized) return
        val now = System.currentTimeMillis() / 1000
        val zone = ZoneId.systemDefault()
        val schedule = workScheduleStore.read()
        val auto = autoStore.read(now)
        val anchor = latestUsage?.fiveHour?.reset
        // An unseeded target is only a metadata refresh, not an activation.
        val nextEpoch = auto.target.takeIf { auto.enabled && auto.seeded }
        val nextLocal = nextEpoch?.let { Instant.ofEpochSecond(it).atZone(zone) }
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val dayLabel = nextLocal?.let {
            when (it.toLocalDate()) {
                today -> "今天"
                today.plusDays(1) -> "明天"
                else -> it.format(DateTimeFormatter.ofPattern("M月d日"))
            }
        }
        expressiveState = expressiveState.copy(
            connected = tokens != null,
            busy = busy.get(),
            deviceCode = deviceCode,
            usage = latestUsage,
            fiveResetText = latestUsage?.let {
                QuotaPresentation.resetLabel(it.fiveHour.reset, now, zone)
            } ?: getString(R.string.connect_to_read),
            weeklyResetText = latestUsage?.let {
                QuotaPresentation.resetLabel(it.weekly.reset, now, zone)
            } ?: getString(R.string.connect_to_read),
            countdown = TimelinePresentation.countdown(anchor, now),
            timeline = TimelinePresentation.dayTimeline(
                anchor = anchor,
                now = now,
                zone = zone,
                schedule = schedule,
                autoEnabled = auto.enabled,
                nextActivationEpoch = nextEpoch,
                completedActivations = auto.successfulActivations,
            ),
            timelineTitle = if (auto.enabled) "今日激活计划" else "今日额度窗口",
            nextActivationTime = nextLocal?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "--:--",
            nextActivationDate = when {
                !auto.enabled -> "未启用"
                nextLocal == null -> "等待计划"
                else -> dayLabel ?: "等待计划"
            },
            autoEnabled = auto.enabled,
            autoSuccesses = auto.successes,
            autoAttempts = auto.attempts,
            schedule = schedule,
            workPlan = TimelinePresentation.workPlanText(schedule),
        )
    }

    private fun safeMessage(error: Exception): String =
        redactTokenText(error.message ?: "未知错误").take(200)

    private fun isAuthFailure(error: Exception): Boolean =
        error.message?.contains("HTTP 401") == true || error.message?.contains("HTTP 403") == true

    private companion object {
        const val AUTO_RESTORE_COOLDOWN_MS = 60_000L
    }

    override fun onDestroy() {
        cancelled = true
        operationGeneration.incrementAndGet()
        client.cancelProbe()
        executor.shutdownNow()
        super.onDestroy()
    }
}
