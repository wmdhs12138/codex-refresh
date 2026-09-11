package com.codexrefresh.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Requests restoration only for a previously enabled schedule. */
class AutoBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        // Keep the receiver within a fast preference read. Keystore validation,
        // target repair, and WorkManager initialization happen after the FGS
        // has entered the foreground.
        val enabled = context.getSharedPreferences("auto_scheduler", Context.MODE_PRIVATE)
            .getBoolean("enabled", false)
        if (enabled) runCatching { AutoKeepAlive.start(context) }
    }
}
