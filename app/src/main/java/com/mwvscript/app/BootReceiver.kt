package com.mwvscript.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Android 14以降はBroadcastReceiverから直接startForegroundService不可
                // AlarmManagerで数秒後に起動する
                val serviceIntent = Intent(context, HubService::class.java).apply {
                    action = HubService.ACTION_BOOT
                }
                val pi = PendingIntent.getService(
                    context, 0, serviceIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val am = context.getSystemService(AlarmManager::class.java)
                am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 3000L,
                    pi
                )
            }
        }
    }
}
