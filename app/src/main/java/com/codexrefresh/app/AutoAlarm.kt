package com.codexrefresh.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf

private const val ACTION_AUTO_ALARM = "com.codexrefresh.app.AUTO_ALARM"
private const val EXTRA_ALARM_GENERATION = "alarm_generation"
private const val EXTRA_ALARM_TARGET = "alarm_target"
private const val AUTO_ALARM_REQUEST = 52_005
const val AUTO_WAKE_WORK = "codex-refresh-auto-wake"

enum class AutoAlarmPrecision { EXACT, INEXACT }

/** Small pure decisions kept separate so overnight scheduling can be unit-tested. */
object AutoAlarmPolicy {
    fun precision(sdk: Int, exactAllowed: Boolean): AutoAlarmPrecision =
        if (sdk < Build.VERSION_CODES.S || exactAllowed) {
            AutoAlarmPrecision.EXACT
        } else {
            AutoAlarmPrecision.INEXACT
        }

    fun triggerAtMillis(targetEpochSeconds: Long, nowMillis: Long): Long =
        maxOf(targetEpochSeconds * 1_000L, nowMillis + 1_000L)
}

/**
 * Silent RTC wake-up for the user-visible activation plan. This never uses
 * setAlarmClock(), so it has no sound, vibration, full-screen UI, or alarm-clock
 * indicator. WorkManager remains the owner of all network side effects.
 */
object AutoAlarm {
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager(context).canScheduleExactAlarms()
    }

    fun schedule(
        context: Context,
        targetEpochSeconds: Long,
        generation: Long,
    ): AutoAlarmPrecision {
        val manager = alarmManager(context)
        val operation = alarmIntent(context, generation, targetEpochSeconds)
        val triggerAt = AutoAlarmPolicy.triggerAtMillis(
            targetEpochSeconds,
            System.currentTimeMillis(),
        )
        val preferred = AutoAlarmPolicy.precision(
            Build.VERSION.SDK_INT,
            canScheduleExact(context),
        )
        if (preferred == AutoAlarmPrecision.EXACT) {
            try {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    operation,
                )
                return AutoAlarmPrecision.EXACT
            } catch (_: SecurityException) {
                // Permission can be revoked between the capability check and call.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        return AutoAlarmPrecision.INEXACT
    }

    fun cancel(context: Context) {
        val operation = PendingIntent.getBroadcast(
            context,
            AUTO_ALARM_REQUEST,
            Intent(context, AutoAlarmReceiver::class.java).setAction(ACTION_AUTO_ALARM),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager(context).cancel(operation)
        operation.cancel()
    }

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    private fun alarmIntent(
        context: Context,
        generation: Long,
        targetEpochSeconds: Long,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        AUTO_ALARM_REQUEST,
        Intent(context, AutoAlarmReceiver::class.java).apply {
            action = ACTION_AUTO_ALARM
            putExtra(EXTRA_ALARM_GENERATION, generation)
            putExtra(EXTRA_ALARM_TARGET, targetEpochSeconds)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Wakes an expedited worker without cancelling the durable delayed worker. */
class AutoAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            val state = AutoStore(context).read()
            if (state.enabled && state.target != null) {
                AutoAlarm.schedule(context, state.target, state.generation)
            }
            return
        }
        if (intent.action != ACTION_AUTO_ALARM) return

        val generation = intent.getLongExtra(EXTRA_ALARM_GENERATION, Long.MIN_VALUE)
        val target = intent.getLongExtra(EXTRA_ALARM_TARGET, Long.MIN_VALUE)
        val state = AutoStore(context).read()
        if (
            !state.enabled ||
            state.generation != generation ||
            state.target != target
        ) return

        val request = OneTimeWorkRequestBuilder<AutoWorker>()
            .setInputData(workDataOf(AUTO_GENERATION to generation))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            AUTO_WAKE_WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
