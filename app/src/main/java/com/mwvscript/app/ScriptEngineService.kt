package com.mwvscript.app

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import com.faendir.rhino_android.RhinoAndroidHelper
import org.mozilla.javascript.Context
import org.mozilla.javascript.ImporterTopLevel
import org.mozilla.javascript.Scriptable

class ScriptEngineService : Service() {

    companion object {
        var isRunning = false
        const val CHANNEL_ID = "mwv_engine"
        const val NOTIF_ID = 1001

        // Rhinoスコープをサービス外から参照できるようにシングルトンで保持
        var rhinoContext: Context? = null
        var rhinoScope: Scriptable? = null
    }

    private var engineWebView: WebView? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("エンジン起動中..."))
        isRunning = true
        initRhino()
        initEngineWebView()
    }

    private fun initRhino() {
        try {
            val helper = RhinoAndroidHelper(this)
            rhinoContext = helper.enterContext()
            rhinoContext?.optimizationLevel = -1  // Android上では-1必須
            rhinoScope = ImporterTopLevel(rhinoContext)
            android.util.Log.d("MWVScript", "Rhinoエンジン初期化完了")
        } catch (e: Exception) {
            android.util.Log.e("MWVScript", "Rhino初期化失敗: ${e.message}")
        }
    }

    fun evaluateRhino(script: String): String {
        return try {
            val result = rhinoContext?.evaluateString(rhinoScope, script, "<mwv>", 1, null)
            Context.toString(result)
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun initEngineWebView() {
        engineWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(ScriptBridge(this@ScriptEngineService, "engine"), "MWVScript")
            loadDataWithBaseURL(null, "<html><body></body></html>", "text/html", "utf-8", null)
        }
    }

    fun executeScript(js: String, callback: ((String) -> Unit)? = null) {
        engineWebView?.evaluateJavascript(js) { result ->
            callback?.invoke(result ?: "")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateNotification("エンジン実行中")
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        engineWebView?.destroy()
        try {
            rhinoContext?.let { Context.exit() }
        } catch (e: Exception) { }
        rhinoContext = null
        rhinoScope = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MWV Script Engine",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "バックグラウンドJSエンジン"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MWV Script")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notif = buildNotification(text)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }
}
