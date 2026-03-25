package com.mwvscript.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MWVDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "MWVDeviceAdmin"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled")
        // Rhinoスコープが起動済みであれば通知
        HubService.instance?.executeAsync(
            "if(typeof print==='function') print('Device Admin: 有効化されました');"
        )
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device Admin disabled")
        HubService.instance?.executeAsync(
            "if(typeof print==='function') print('Device Admin: 無効化されました');"
        )
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Profile provisioning complete")
    }
}
