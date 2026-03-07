package com.mwvscript.app

import android.content.Context
import android.webkit.JavascriptInterface
import java.io.File

class ScriptBridge(private val context: Context, private val accountId: String) {

    companion object {
        private val messageQueue = mutableMapOf<String, MutableList<String>>()

        fun postMessage(targetAccount: String, message: String) {
            messageQueue.getOrPut(targetAccount) { mutableListOf() }.add(message)
        }
    }

    @JavascriptInterface
    fun getAccountId(): String = accountId

    @JavascriptInterface
    fun postToAccount(targetAccountId: String, message: String) {
        postMessage(targetAccountId, message)
    }

    @JavascriptInterface
    fun pollMessages(): String {
        val msgs = messageQueue[accountId] ?: return "[]"
        val result = msgs.toList()
        msgs.clear()
        return com.google.gson.Gson().toJson(result)
    }

    // ── ファイル操作 ───────────────────────────────────────────────────────────

    @JavascriptInterface
    fun readFile(relativePath: String): String {
        return try {
            val file = File(context.getExternalFilesDir(null), relativePath)
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) { "" }
    }

    @JavascriptInterface
    fun writeFile(relativePath: String, content: String): Boolean {
        return try {
            val file = File(context.getExternalFilesDir(null), relativePath)
            file.parentFile?.mkdirs()
            file.writeText(content)
            true
        } catch (e: Exception) { false }
    }

    @JavascriptInterface
    fun listFiles(dirPath: String): String {
        return try {
            val dir = File(context.getExternalFilesDir(null), dirPath)
            val files = dir.listFiles()?.map { it.name } ?: emptyList()
            com.google.gson.Gson().toJson(files)
        } catch (e: Exception) { "[]" }
    }

    // ── Rhinoエンジン経由でAndroid APIを直叩き ─────────────────────────────────

    @JavascriptInterface
    fun rhinoEval(script: String): String {
        val service = ScriptEngineService.rhinoContext ?: return "Error: Rhinoエンジン未起動"
        val scope = ScriptEngineService.rhinoScope ?: return "Error: Rhinoスコープ未初期化"
        return try {
            val result = service.evaluateString(scope, script, "<rhino>", 1, null)
            org.mozilla.javascript.Context.toString(result)
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ── UI操作 ────────────────────────────────────────────────────────────────

    @JavascriptInterface
    fun log(message: String) {
        android.util.Log.d("MWVScript[$accountId]", message)
    }

    @JavascriptInterface
    fun toast(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, "[$accountId] $message", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
