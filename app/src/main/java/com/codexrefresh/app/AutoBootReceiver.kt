package com.codexrefresh.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Requests restoration only for a previously enabled schedule. */
class AutoBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED
        ) return

        // Keep recovery network-free. It only restores the persisted alarm,
        // durable worker, and (after boot/update) the user-enabled supervisor.
        val enabled = context.getSharedPreferences("auto_scheduler", Context.MODE_PRIVATE)
            .getBoolean("enabled", false)
        if (!enabled) return

        val state = AutoStore(context).read()
        state.target?.let { target ->
            runCatching { AutoAlarm.schedule(context, target, state.generation) }
            runCatching { ensureAutoScheduled(context, target, state.generation) }
        }
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching { AutoKeepAlive.start(context) }
        }
    }
}
