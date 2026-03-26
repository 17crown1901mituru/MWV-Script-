package com.mwvscript.app

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Shizuku UserService として動作するサービス
 * ADB権限(uid=2000)で起動され、シェルコマンドを実行する
 */
class ShizukuUserService : IShizukuUserService.Stub() {

    override fun exec(cmd: String): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out  = proc.inputStream.bufferedReader().readText()
            val err  = proc.errorStream.bufferedReader().readText()
            proc.waitFor()
            if (err.isNotEmpty()) err else out
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    override fun destroy() {
        // UserServiceのライフサイクル終了
    }
}
