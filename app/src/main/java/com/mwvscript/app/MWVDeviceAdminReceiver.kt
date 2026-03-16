package com.mwvscript.app
import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class MWVDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // 有効化された時の処理
    }
}
