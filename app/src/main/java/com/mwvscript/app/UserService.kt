package com.mwvscript.app

import android.os.IBinder
import com.mwvscript.app.IUserService
import java.io.BufferedReader
import java.io.InputStreamReader

class UserService : IUserService.Stub() {
    override fun exec(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readText()
            process.waitFor()
            result
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
