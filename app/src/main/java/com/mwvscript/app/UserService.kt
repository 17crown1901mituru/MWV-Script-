package com.mwvscript.app

import android.os.Process
import com.mwvscript.app.IUserService

class UserService : IUserService.Stub() {
    
    // IUserService.aidl のメソッド実装
    override fun exec(command: String?): String {
        if (command.isNullOrBlank()) return ""
        return try {
            val process = Runtime.getRuntime().exec(command)
            val result = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            result
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    override fun destroy() {
        // サービス終了時にプロセスを確実に殺す
        Process.killProcess(Process.myPid())
    }
}
