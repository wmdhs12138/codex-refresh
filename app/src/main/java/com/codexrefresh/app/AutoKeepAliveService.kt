package com.codexrefresh.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import java.time.ZoneId

private const val LEGACY_KEEP_ALIVE_CHANNEL = "codex_auto_guard"
private const val KEEP_ALIVE_CHANNEL = "codex_auto_guard_v2"
private const val FIRST_LEGACY_NOTIFICATION = 52001
private const val SECOND_LEGACY_NOTIFICATION = 52002
private const val THIRD_LEGACY_NOTIFICATION = 52003
private const val KEEP_ALIVE_NOTIFICATION = 52004
private const val ACTION_REFRESH_NOTIFICATION = "com.codexrefresh.app.REFRESH_NOTIFICATION"
private const val ENSURE_INTERVAL_MS = 15 * 60 * 1000L
private const val NOTIFICATION_INTERVAL_MS = 60 * 1000L

/**
 * User-enabled foreground supervisor. It does not call Codex itself: the
 * existing quota-aware Worker remains the sole owner of network side effects.
 */
class AutoKeepAliveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var autoStore: AutoStore
    private lateinit var quotaResetStore: QuotaResetStore
    private var lastEnsureAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            val state = autoStore.read()
            if (!state.enabled) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            val now = System.currentTimeMillis()
            if (now - lastEnsureAt >= ENSURE_INTERVAL_MS) {
                state.target?.let { ensureAutoScheduled(applicationContext, it, state.generation) }
                lastEnsureAt = now
            }
            notificationManager().notify(KEEP_ALIVE_NOTIFICATION, notification())
            handler.postDelayed(this, NOTIFICATION_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        autoStore = AutoStore(applicationContext)
        quotaResetStore = QuotaResetStore(applicationContext)
        createChannel()
        notificationManager().cancel(FIRST_LEGACY_NOTIFICATION)
        notificationManager().cancel(SECOND_LEGACY_NOTIFICATION)
        notificationManager().cancel(THIRD_LEGACY_NOTIFICATION)
        notificationManager().deleteNotificationChannel(LEGACY_KEEP_ALIVE_CHANNEL)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Enter FGS immediately. Keystore and persisted-state checks happen only
        // after this deadline-sensitive call has completed.
        startForegroundNow(
            if (intent?.action == ACTION_REFRESH_NOTIFICATION) {
                notification()
            } else {
                restoringNotification()
            },
        )

        val state = autoStore.read()
        if (!state.enabled || TokenStore(applicationContext).load() == null) {
            if (state.enabled) {
                autoStore.disable("需要重新登录")
                cancelAuto(applicationContext)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val decision = KeepAliveRecoveryPolicy.decide(
            state,
            hasTokens = true,
            now = System.currentTimeMillis() / 1000,
        )
        val current = if (state.target == null) {
            autoStore.updateIfCurrent(state.generation) {
                it.copy(target = decision.target, baseTarget = decision.target)
            }
                ?: autoStore.read()
        } else {
            state
        }
        startForegroundNow(notification())
        current.target?.let { ensureAutoScheduled(applicationContext, it, current.generation) }
        lastEnsureAt = System.currentTimeMillis()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, NOTIFICATION_INTERVAL_MS)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val state = autoStore.read()
        if (state.enabled) {
            state.target?.let { ensureAutoScheduled(applicationContext, it, state.generation) }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            KEEP_ALIVE_CHANNEL,
            getString(R.string.keep_alive_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.keep_alive_channel_description)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun startForegroundNow(value: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                KEEP_ALIVE_NOTIFICATION,
                value,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(KEEP_ALIVE_NOTIFICATION, value)
        }
    }

    private fun restoringNotification(): Notification = buildNotification(
        getString(R.string.keep_alive_restoring_title),
        getString(R.string.keep_alive_restoring_text),
    )

    private fun notification(): Notification {
        val now = System.currentTimeMillis() / 1000
        val observations = QuotaDiagnosticsStore(applicationContext).read()
        return buildNotification(
            title = getString(R.string.keep_alive_title),
            text = KeepAlivePresentation.text(
                snapshot = quotaResetStore.read(),
                now = now,
                observations = observations,
            ),
        )
    }

    private fun buildNotification(
        title: String,
        text: String,
        expandedText: String = text,
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, KEEP_ALIVE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_flash)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(expandedText))
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)
}

object KeepAlivePresentation {
    fun text(
        snapshot: QuotaResetSnapshot,
        now: Long = System.currentTimeMillis() / 1000,
        zone: ZoneId = ZoneId.systemDefault(),
        observations: List<QuotaObservation> = emptyList(),
    ): String = QuotaPresentation.notificationText(
        snapshot,
        now,
        zone,
        FiveHourEvidencePolicy.evaluate(observations, now).takeIf { observations.isNotEmpty() },
    )

    fun notificationStatus(notificationsVisible: Boolean): String = if (notificationsVisible) {
        "常驻通知：已允许显示"
    } else {
        "常驻通知：通知权限未允许，通知抽屉中不可见；后台任务仍受系统调度限制"
    }
}

data class KeepAliveRecovery(
    val shouldRun: Boolean,
    val target: Long?,
    val shouldDisable: Boolean,
)

/** Pure recovery decision used by boot/package-replacement restoration. */
object KeepAliveRecoveryPolicy {
    fun decide(state: AutoState, hasTokens: Boolean, now: Long): KeepAliveRecovery {
        if (!state.enabled) return KeepAliveRecovery(false, null, false)
        if (!hasTokens) return KeepAliveRecovery(false, null, true)
        return KeepAliveRecovery(true, state.target ?: now + 300, false)
    }
}

object AutoKeepAlive {
    /** Requests restoration; all validation and scheduling run inside the FGS. */
    fun restore(context: Context): Boolean = start(context)

    fun start(context: Context): Boolean = runCatching {
        ContextCompat.startForegroundService(
            context.applicationContext,
            Intent(context.applicationContext, AutoKeepAliveService::class.java),
        )
    }.isSuccess

    /** Rebuilds the foreground notification from the latest persisted quota snapshot. */
    fun refreshNotification(context: Context): Boolean {
        if (!AutoStore(context.applicationContext).read().enabled) return false
        return runCatching {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, AutoKeepAliveService::class.java).apply {
                    action = ACTION_REFRESH_NOTIFICATION
                },
            )
        }.isSuccess
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, AutoKeepAliveService::class.java))
    }
}
