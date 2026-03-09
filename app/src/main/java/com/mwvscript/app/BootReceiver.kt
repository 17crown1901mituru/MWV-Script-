package com.mwvscript.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val i = Intent(context, HubService::class.java).apply {
                    action = HubService.ACTION_BOOT
                }
                context.startForegroundService(i)
            }
        }
    }
}
