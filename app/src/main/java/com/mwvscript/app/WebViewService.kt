package com.mwvscript.app

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.*
import android.util.Log
import android.view.*
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import androidx.core.app.NotificationCompat

class WebViewService : Service() {

    companion object {
        private const val TAG        = "WebViewService"
        const val CHANNEL_ID         = "mwv_hub"   // HubServiceと共通チャンネル
        const val NOTIF_ID           = 2002
        const val MAX_TABS           = 10
        const val DEFAULT_SESSION    = "default"

        const val ACTION_OPEN        = "com.mwvscript.app.WEB_OPEN"
        const val ACTION_CLOSE       = "com.mwvscript.app.WEB_CLOSE"
        const val EXTRA_URL          = "url"
        const val EXTRA_SESSION      = "sessionId"

        // web.set / web.get 用共有ストア
        val sharedVars = mutableMapOf<String, Any?>()

        var instance: WebViewService? = null
            private set
    }

    // ----------------------------------------------------------
    // 内部データ
    // ----------------------------------------------------------
    data class WebTab(
        val sessionId: String,
        val type: String,          // "webview" | "blank"
        val webView: WebView
    )

    // ----------------------------------------------------------
    // フィールド
    // ----------------------------------------------------------
    private lateinit var wm: WindowManager
    private lateinit var mainHandler: Handler

    private val tabs = mutableListOf<WebTab>()
    private var activeSession: String? = null

    private var rootView: View? = null
    private var tabBarContainer: LinearLayout? = null
    private var contentFrame: FrameLayout? = null

    // ----------------------------------------------------------
    // ライフサイクル
    // ----------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        mainHandler = Handler(Looper.getMainLooper())
        // Xperia電力管理対策：onCreate直後にstartForeground
        startForeground(NOTIF_ID, buildNotification())
        Log.i(TAG, "WebViewService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OPEN -> {
                val url       = intent.getStringExtra(EXTRA_URL) ?: "about:blank"
                val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: DEFAULT_SESSION
                openTab(url, sessionId)
            }
            ACTION_CLOSE -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: DEFAULT_SESSION
                closeTab(sessionId)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
        removeRootView()
        super.onDestroy()
    }

    // ----------------------------------------------------------
    // 公開 API（OverlayService の web.* ブリッジから呼ぶ）
    // ----------------------------------------------------------

    fun openTab(url: String, sessionId: String = DEFAULT_SESSION, autoJs: String? = null) {
        mainHandler.post {
            val existing = findTab(sessionId)
            if (existing != null) {
                existing.webView.loadUrl(url)
                autoJs?.let { existing.webView.evaluateJavascript(it, null) }
                activateTab(sessionId)
                return@post
            }
            if (tabs.size >= MAX_TABS) {
                Log.w(TAG, "MAX_TABS($MAX_TABS) に達しました")
                OverlayService.instance?.appendOutput("WebView: タブ上限 $MAX_TABS に達しました")
                return@post
            }
            val tab = createTab(sessionId, url, "webview", autoJs)
            tabs.add(tab)
            ensureRootView()
            refreshTabBar()
            activateTab(sessionId)
        }
    }

    fun evalJs(sessionId: String, js: String, callback: ((String) -> Unit)? = null) {
        mainHandler.post {
            val tab = findTab(sessionId) ?: run {
                Log.e(TAG, "evalJs: session not found: $sessionId")
                callback?.invoke("")
                return@post
            }
            tab.webView.evaluateJavascript(js) { result ->
                callback?.invoke(result ?: "")
            }
        }
    }

    fun closeTab(sessionId: String) {
        mainHandler.post {
            val tab = findTab(sessionId) ?: return@post
            tab.webView.destroy()
            tabs.remove(tab)
            if (tabs.isEmpty()) {
                removeRootView()
            } else {
                if (activeSession == sessionId) activateTab(tabs.last().sessionId)
                refreshTabBar()
            }
        }
    }

    fun getCurrentUrl(sessionId: String): String =
        findTab(sessionId)?.webView?.url ?: ""

    fun getCookies(sessionId: String): String =
        findTab(sessionId)?.let {
            CookieManager.getInstance().getCookie(it.webView.url ?: "") ?: ""
        } ?: ""

    fun getSessionIds(): Array<String> = tabs.map { it.sessionId }.toTypedArray()

    // ----------------------------------------------------------
    // タブ生成
    // ----------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTab(
        sessionId: String,
        url: String,
        type: String,
        autoJs: String? = null
    ): WebTab {
        val webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled     = true
                domStorageEnabled     = true
                databaseEnabled       = true
                setSupportZoom(true)
                builtInZoomControls   = true
                displayZoomControls   = false
                mixedContentMode      = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString       = settings.userAgentString
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    autoJs?.let { view.evaluateJavascript(it, null) }
                }
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.loadUrl(url)
        return WebTab(sessionId, type, webView)
    }

    // ----------------------------------------------------------
    // UI
    // ----------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun ensureRootView() {
        if (rootView != null) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0f0f1e"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            )
        }
        tabBarContainer = tabBar

        val content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        contentFrame = content

        root.addView(tabBar)
        root.addView(content)
        rootView = root

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(root, lp)
    }

    private fun removeRootView() {
        rootView?.let {
            try { wm.removeView(it) } catch (e: Exception) { Log.w(TAG, e) }
        }
        rootView       = null
        tabBarContainer = null
        contentFrame   = null
    }

    private fun refreshTabBar() {
        val bar = tabBarContainer ?: return
        mainHandler.post {
            bar.removeAllViews()
            tabs.forEach { tab ->
                val isActive = tab.sessionId == activeSession
                val label = when (tab.type) {
                    "webview" -> "🌐 ${tab.sessionId.take(6)}"
                    else      -> "📄 ${tab.sessionId.take(6)}"
                }
                val btn = TextView(this).apply {
                    text      = label
                    textSize  = 10f
                    typeface  = Typeface.MONOSPACE
                    gravity   = Gravity.CENTER
                    setPadding(dp(8), 0, dp(8), 0)
                    setTextColor(if (isActive) Color.parseColor("#e94560") else Color.parseColor("#888899"))
                    setBackgroundColor(if (isActive) Color.parseColor("#1a1a2e") else Color.parseColor("#0f0f1e"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                    setOnClickListener      { activateTab(tab.sessionId) }
                    setOnLongClickListener  { closeTab(tab.sessionId); true }
                }
                bar.addView(btn)
            }
            // ⊕ ボタン
            if (tabs.size < MAX_TABS) {
                val addBtn = TextView(this).apply {
                    text     = " ⊕ "
                    textSize = 16f
                    gravity  = Gravity.CENTER
                    setTextColor(Color.parseColor("#e94560"))
                    setBackgroundColor(Color.parseColor("#0f0f1e"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                    setOnClickListener { showNewTabDialog() }
                }
                bar.addView(addBtn)
            }
        }
    }

    private fun activateTab(sessionId: String) {
        activeSession = sessionId
        val frame = contentFrame ?: return
        mainHandler.post {
            frame.removeAllViews()
            val tab = findTab(sessionId) ?: return@post
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            frame.addView(tab.webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            refreshTabBar()
        }
    }

    // ----------------------------------------------------------
    // 新規タブダイアログ
    // ----------------------------------------------------------

    private fun showNewTabDialog() {
        val act = MainActivity.instance ?: run {
            Log.w(TAG, "showNewTabDialog: no Activity context")
            return
        }
        val choices = arrayOf(
            "🌐  WebView（URL を開く）",
            "📄  空白ページ"
        )
        android.app.AlertDialog.Builder(act)
            .setTitle("新規タブ（${tabs.size} / $MAX_TABS）")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> showUrlInputDialog(act)
                    1 -> {
                        val sid = "blank_${System.currentTimeMillis()}"
                        val tab = createTab(sid, "about:blank", "blank")
                        tabs.add(tab)
                        ensureRootView()
                        refreshTabBar()
                        activateTab(sid)
                    }
                }
            }
            .show()
    }

    private fun showUrlInputDialog(act: Context) {
        val input = EditText(act).apply {
            hint       = "https://"
            inputType  = android.text.InputType.TYPE_CLASS_TEXT or
                         android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        android.app.AlertDialog.Builder(act)
            .setTitle("URL を入力")
            .setView(input)
            .setPositiveButton("開く") { _, _ ->
                val raw = input.text.toString().trim()
                val url = if (!raw.startsWith("http")) "https://$raw" else raw
                openTab(url, "web_${System.currentTimeMillis()}")
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ----------------------------------------------------------
    // ユーティリティ
    // ----------------------------------------------------------

    private fun findTab(sessionId: String) = tabs.firstOrNull { it.sessionId == sessionId }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MWV Script")
            .setContentText("WebView Service 動作中")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
}
