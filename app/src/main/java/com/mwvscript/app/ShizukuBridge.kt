package com.mwvscript.app

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku

object ShizukuBridge {
    private var userService: IUserService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            userService = IUserService.Stub.asInterface(service)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
        }
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.mwvscript.app", UserService::class.java.name)
    ).daemon(false).processNameSuffix("shizuku_service").debuggable(true)

    fun bind(context: android.content.Context) {
        if (Shizuku.pingBinder()) {
            Shizuku.bindUserService(userServiceArgs, connection)
        }
    }

    @android.webkit.JavascriptInterface
    fun runShell(command: String): String {
        return userService?.exec(command) ?: "Service not connected"
    }
}
