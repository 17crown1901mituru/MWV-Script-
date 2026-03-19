package com.mwvscript.app

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.os.*
import android.webkit.*
import androidx.core.app.NotificationCompat

// ============================================================
// WebViewService - WebViewエンジン（UIなし）
//
// WebViewの生成・管理・JS評価のみを担当。
// UIはMainActivityのタブで描画するため、
// WindowManagerへのView追加は行わない。
// ============================================================
class WebViewService : Service() {

    companion object {
        private const val TAG      = "WebViewService"
        const val CHANNEL_ID       = "mwv_hub"
        const val NOTIF_ID         = 2002
        const val DEFAULT_SESSION  = "default"

        const val ACTION_OPEN      = "com.mwvscript.app.WEB_OPEN"
        const val ACTION_CLOSE     = "com.mwvscript.app.WEB_CLOSE"
        const val EXTRA_URL        = "url"
        const val EXTRA_SESSION    = "sessionId"
        const val EXTRA_TYPE       = "tabType"
        const val EXTRA_LABEL      = "label"

        val sharedVars = mutableMapOf<String, Any?>()

        var instance: WebViewService? = null
            private set
    }

    enum class TabType { TERMINAL, WEBVIEW, LAUNCHER }

    private lateinit var mainHandler: Handler

    override fun onCreate() {
        super.onCreate()
        instance = this
        mainHandler = Handler(Looper.getMainLooper())
        startForeground(NOTIF_ID, buildNotification("MWV Script 動作中"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OPEN -> {
                val url       = intent.getStringExtra(EXTRA_URL) ?: "about:blank"
                val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: DEFAULT_SESSION
                val typeStr   = intent.getStringExtra(EXTRA_TYPE) ?: "WEBVIEW"
                val label     = intent.getStringExtra(EXTRA_LABEL) ?: url.take(20)
                val type      = runCatching { TabType.valueOf(typeStr) }.getOrDefault(TabType.WEBVIEW)
                // MainActivityのタブで開く
                val act = MainActivity.instance ?: return START_STICKY
                act.openTab(sessionId, when(type) {
                    TabType.TERMINAL -> MainActivity.TabType.TERMINAL
                    TabType.LAUNCHER -> MainActivity.TabType.LAUNCHER
                    else             -> MainActivity.TabType.WEBVIEW
                }, label, url)
            }
            ACTION_CLOSE -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: DEFAULT_SESSION
                MainActivity.instance?.closeTab(sessionId)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ----------------------------------------------------------
    // 公開API（OverlayServiceのweb.*ブリッジから委譲）
    // 実処理はMainActivityに委譲する
    // ----------------------------------------------------------

    fun openTab(
        url: String = "about:blank",
        sessionId: String = DEFAULT_SESSION,
        type: TabType = TabType.WEBVIEW,
        autoJs: String? = null,
        label: String? = null
    ) {
        val act = MainActivity.instance ?: return
        act.openTab(sessionId, when(type) {
            TabType.TERMINAL -> MainActivity.TabType.TERMINAL
            TabType.LAUNCHER -> MainActivity.TabType.LAUNCHER
            else             -> MainActivity.TabType.WEBVIEW
        }, label ?: url.take(20), url, autoJs)
    }

    fun evalJs(sessionId: String, js: String, callback: ((String) -> Unit)? = null) {
        MainActivity.instance?.evalJs(sessionId, js, callback) ?: callback?.invoke("")
    }

    fun closeTab(sessionId: String) {
        MainActivity.instance?.closeTab(sessionId)
    }

    fun appendToTerminal(sessionId: String, text: String) {
        MainActivity.instance?.appendToTerminal(sessionId, text)
    }

    fun setKeepAlive(sessionId: String, keep: Boolean) {
        MainActivity.instance?.setKeepAlive(sessionId, keep)
    }

    fun getCurrentUrl(sessionId: String): String =
        MainActivity.instance?.getCurrentUrl(sessionId) ?: ""

    fun getCookies(sessionId: String): String =
        MainActivity.instance?.getCookies(sessionId) ?: ""

    fun getSessionIds(): Array<String> =
        MainActivity.instance?.getSessionIds() ?: emptyArray()

    fun hideOverlay() { /* WindowManagerオーバーレイ廃止済み */ }
    fun showOverlay() { /* WindowManagerオーバーレイ廃止済み */ }
    fun isVisible()   = true

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MWV Script")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
}
