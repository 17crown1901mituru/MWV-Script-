package com.mwvscript.app

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku
import com.mwvscript.app.IUserService

object ShizukuManager {
    private var userService: IUserService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            userService = IUserService.Stub.asInterface(service)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
        }
    }

    // どのコンテキストからでも呼び出せる初期化関数
    fun init(context: android.content.Context) {
        if (Shizuku.pingBinder()) {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, UserService::class.java.name)
            ).daemon(false).processNameSuffix("shizuku_service").debuggable(true)
            
            Shizuku.bindUserService(args, connection)
        }
    }

    @android.webkit.JavascriptInterface
    fun runShell(command: String): String {
        return userService?.exec(command) ?: "Shizuku Service not connected"
    }
}
