package com.mwvscript.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.core.app.NotificationCompat
import org.mozilla.javascript.ScriptableObject

class OverlayService : Service() {

    companion object {
        var instance: OverlayService? = null
        const val CHANNEL_ID = "mwv_overlay"
        const val NOTIF_ID = 1002
        const val ACTION_TOGGLE = "com.mwvscript.app.OVERLAY_TOGGLE"
        const val ACTION_EXIT = "com.mwvscript.app.APP_EXIT"

        fun toggle(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_TOGGLE
            }
            context.startForegroundService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var isShowing = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // WebView（INVISIBLEのままWindowManagerに保持）
    private var webView: WebView? = null
    private var webViewShowing = false
    private var currentSessionId = "default"

    // オーバーレイターミナル用
    private lateinit var terminalContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var activeInput: EditText

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        injectOverlayBridge()
        initWebView()
    }

    private fun initWebView() {
        mainHandler.post {
            val wv = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                webViewClient = WebViewClient()
                visibility = View.INVISIBLE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            try {
                windowManager.addView(wv, params)
                webView = wv
            } catch (e: Exception) {
                android.util.Log.e("MWVScript", "WebView初期化失敗: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) {
            if (isShowing) hideOverlay() else showOverlay()
        }
        if (intent?.action == ACTION_EXIT) {
            hideOverlay()
            stopSelf()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hideOverlay()
        webView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
            it.destroy()
            webView = null
        }
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ======================================================
    // オーバーレイ表示/非表示
    // ======================================================

    private fun showOverlay() {
        if (isShowing) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            startActivity(intent)
            return
        }

        val view = buildOverlayView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        windowManager.addView(view, params)
        overlayView = view
        overlayParams = params
        isShowing = true
        updateNotification()

        // 入力欄タップ時にフォーカスを有効化
        activeInput.setOnTouchListener { _, _ ->
            val lp = params.apply {
                flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            }
            windowManager.updateViewLayout(view, lp)
            activeInput.requestFocus()
            false
        }
    }

    private fun hideOverlay() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { }
            overlayView = null
            overlayParams = null
        }
        isShowing = false
        updateNotification()
    }

    // ======================================================
    // Cookie セッション管理
    // ======================================================

    private fun saveCookies(sessionId: String, url: String) {
        val cookieManager = android.webkit.CookieManager.getInstance()
        val cookies = cookieManager.getCookie(url) ?: return
        getSharedPreferences("mwv_cookies", Context.MODE_PRIVATE)
            .edit()
            .putString("cookie_$sessionId", cookies)
            .putString("url_$sessionId", url)
            .apply()
    }

    private fun restoreCookies(sessionId: String) {
        val prefs = getSharedPreferences("mwv_cookies", Context.MODE_PRIVATE)
        val cookies = prefs.getString("cookie_$sessionId", null) ?: return
        val url = prefs.getString("url_$sessionId", null) ?: return
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookies.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { cookie ->
            cookieManager.setCookie(url, cookie)
        }
        cookieManager.flush()
    }

    private fun switchSession(newSessionId: String, url: String) {
        // 現在のセッションのCookieを保存
        val currentUrl = getSharedPreferences("mwv_cookies", Context.MODE_PRIVATE)
            .getString("url_$currentSessionId", null)
        if (currentUrl != null) saveCookies(currentSessionId, currentUrl)

        // 新しいセッションのCookieを復元
        currentSessionId = newSessionId
        restoreCookies(newSessionId)
    }

    private fun getScreenHeight(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            val displayMetrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            displayMetrics.heightPixels
        }
    }

    // ======================================================
    // オーバーレイUI構築
    // ======================================================

    // 履歴パネル表示状態
    private var historyExpanded = false
    private var historyPanel: LinearLayout? = null
    private val outputHistory = mutableListOf<String>()
    private var isShiftOn = false

    private fun buildOverlayView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#EE000000"))
        }

        // ── ヘッダー ──────────────────────────────
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#1a1a1a"))
            setPadding(12, 4, 4, 4)
        }
        val title = TextView(this).apply {
            text = "MWV"
            setTextColor(android.graphics.Color.GREEN)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val histBtn = termKey("LOG") { toggleHistory() }
        val closeBtn = termKey("✕", android.graphics.Color.parseColor("#550000")) { hideOverlay() }
        header.addView(title)
        header.addView(histBtn)
        header.addView(closeBtn)

        // ── 履歴パネル（折りたたみ） ────────────────
        val hPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            setBackgroundColor(android.graphics.Color.parseColor("#CC111111"))
        }
        val hScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 160)
        }
        terminalContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 4, 12, 4)
        }
        hScroll.addView(terminalContainer)
        hPanel.addView(hScroll)
        historyPanel = hPanel
        scrollView = hScroll

        // ── 最新出力2行 ───────────────────────────
        val recentArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 4, 12, 2)
            setBackgroundColor(android.graphics.Color.parseColor("#CC000000"))
        }
        recentLine1 = TextView(this).apply {
            setTextColor(android.graphics.Color.parseColor("#888888"))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
            text = ""
        }
        recentLine2 = TextView(this).apply {
            setTextColor(android.graphics.Color.GREEN)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
            text = ""
        }
        recentArea.addView(recentLine1)
        recentArea.addView(recentLine2)

        // ── 入力欄 ────────────────────────────────
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#1a1a1a"))
            setPadding(8, 2, 8, 2)
        }
        activeInput = EditText(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(4, 4, 4, 4)
            hint = "> "
            setHintTextColor(android.graphics.Color.GRAY)
            // システムキーボードを抑制
            inputType = android.text.InputType.TYPE_NULL
            isSingleLine = true
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val runBtn = termKey("▶", android.graphics.Color.parseColor("#004400")) { runInput() }
        inputRow.addView(activeInput)
        inputRow.addView(runBtn)

        // ── 独自キーボード ────────────────────────
        val keyboard = buildCustomKeyboard()

        root.addView(header)
        root.addView(hPanel)
        root.addView(recentArea)
        root.addView(inputRow)
        root.addView(keyboard)

        return root
    }

    private lateinit var recentLine1: TextView
    private lateinit var recentLine2: TextView

    private fun toggleHistory() {
        historyExpanded = !historyExpanded
        historyPanel?.visibility = if (historyExpanded) android.view.View.VISIBLE else android.view.View.GONE
        if (historyExpanded) {
            scrollView.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun buildCustomKeyboard(): LinearLayout {
        val kb = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#222222"))
            setPadding(2, 2, 2, 2)
        }

        val rows = listOf(
            listOf("1","2","3","4","5","6","7","8","9","0","-","_"),
            listOf("q","w","e","r","t","y","u","i","o","p"),
            listOf("a","s","d","f","g","h","j","k","l"),
            listOf("SHIFT","z","x","c","v","b","n","m","BS"),
            listOf("(",")","\\'","\"",";",".","/","SPACE","↵")
        )

        for (row in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            for (key in row) {
                val weight = when (key) {
                    "SPACE" -> 3f
                    "SHIFT", "BS", "↵" -> 1.5f
                    else -> 1f
                }
                val bgColor = when (key) {
                    "↵" -> android.graphics.Color.parseColor("#004400")
                    "BS" -> android.graphics.Color.parseColor("#442200")
                    "SHIFT" -> android.graphics.Color.parseColor("#222266")
                    else -> android.graphics.Color.parseColor("#333333")
                }
                val btn = android.widget.Button(this).apply {
                    text = key
                    textSize = 10f
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(bgColor)
                    setPadding(0, 6, 0, 6)
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
                        setMargins(1, 1, 1, 1)
                    }
                    setOnClickListener { onKeyPress(key) }
                }
                rowLayout.addView(btn)
            }
            kb.addView(rowLayout)
        }
        return kb
    }

    private fun onKeyPress(key: String) {
        when (key) {
            "BS" -> {
                val text = activeInput.text
                val pos = activeInput.selectionStart
                if (pos > 0) text.delete(pos - 1, pos)
            }
            "↵" -> runInput()
            "SPACE" -> activeInput.text.insert(activeInput.selectionStart, " ")
            "SHIFT" -> {
                isShiftOn = !isShiftOn
            }
            else -> {
                val c = if (isShiftOn) key.uppercase() else key.lowercase()
                activeInput.text.insert(activeInput.selectionStart, c)
                if (isShiftOn) isShiftOn = false
            }
        }
    }


    // ======================================================
    // ターミナル入出力
    // ======================================================

    fun appendOutput(text: String) {
        mainHandler.post {
            if (!::terminalContainer.isInitialized) return@post

            // 履歴に追加
            outputHistory.add(text)

            // 履歴パネル用TextView
            val tv = TextView(this).apply {
                setTextColor(android.graphics.Color.GREEN)
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                this.text = text
            }
            terminalContainer.addView(tv)
            if (historyExpanded) {
                scrollView.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
            }

            // 最新2行を更新
            val size = outputHistory.size
            if (::recentLine1.isInitialized && ::recentLine2.isInitialized) {
                recentLine1.text = if (size >= 2) outputHistory[size - 2] else ""
                recentLine2.text = if (size >= 1) outputHistory[size - 1] else ""
            }
        }
    }

    private fun addNewInput() {
        mainHandler.post {
            if (!::activeInput.isInitialized) return@post
            activeInput.setText("")
            activeInput.requestFocus()
        }
    }

    private fun runInput() {
        val input = activeInput.text.toString().trim()
        if (input.isEmpty()) return

        mainHandler.post {
            val inputText = activeInput.text.toString()
            val tv = TextView(this).apply {
                setTextColor(android.graphics.Color.CYAN)
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                text = "> $inputText"
            }
            terminalContainer.addView(tv)
            activeInput.setText("")
        }

        Thread {
            val scope = ScriptEngineService.rhinoScope
            if (scope == null) {
                appendOutput("エラー: Rhinoエンジン未起動")
                return@Thread
            }
            try {
                val cx = org.mozilla.javascript.Context.enter()
                cx.optimizationLevel = -1
                val result = if (input.endsWith(".rjs") || input.endsWith(".js")) {
                    val file = java.io.File(
                        getExternalFilesDir(null), input)
                    if (!file.exists()) {
                        appendOutput("エラー: ファイルが見つかりません: ${file.absolutePath}")
                        org.mozilla.javascript.Context.exit()
                        return@Thread
                    }
                    cx.evaluateString(scope, file.readText(), file.name, 1, null)
                } else {
                    cx.evaluateString(scope, input, "<overlay>", 1, null)
                }
                org.mozilla.javascript.Context.exit()
                val str = org.mozilla.javascript.Context.toString(result)
                if (str != "undefined") appendOutput(str)
            } catch (e: Exception) {
                try { org.mozilla.javascript.Context.exit() } catch (_: Exception) {}
                appendOutput("エラー: ${e.message}")
            }
        }.start()
    }

    // ======================================================
    // Rhinoブリッジ
    // ======================================================

    internal fun injectOverlayBridge() {
        val scope = ScriptEngineService.rhinoScope ?: return
        val service = this

        val cx = org.mozilla.javascript.Context.enter()
        cx.optimizationLevel = -1
        val overlay = cx.newObject(scope) as org.mozilla.javascript.NativeObject

        ScriptableObject.putProperty(overlay, "show", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                mainHandler.post { service.showOverlay() }
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(overlay, "hide", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                mainHandler.post { service.hideOverlay() }
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(overlay, "print", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val msg = args.joinToString(" ") { org.mozilla.javascript.Context.toString(it) }
                service.appendOutput(msg)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(scope, "overlay", overlay)
        android.util.Log.d("MWVScript", "overlayブリッジ注入完了")

        // WebViewブリッジ
        val web = cx.newObject(scope) as org.mozilla.javascript.NativeObject
        org.mozilla.javascript.Context.exit()

        ScriptableObject.putProperty(web, "open", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val url = args.getOrNull(0)?.let { org.mozilla.javascript.Context.toString(it) } ?: return null
                val sessionId = args.getOrNull(1)?.let { org.mozilla.javascript.Context.toString(it) } ?: "default"
                mainHandler.post {
                    // セッション切り替え（Cookie保存→復元）
                    if (sessionId != currentSessionId) {
                        switchSession(sessionId, url)
                    }
                    webView?.let {
                        it.loadUrl(url)
                        it.visibility = View.VISIBLE
                        webViewShowing = true
                        val params = (it.layoutParams as WindowManager.LayoutParams).apply {
                            flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                        }
                        windowManager.updateViewLayout(it, params)
                    }
                }
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(web, "close", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                mainHandler.post {
                    webView?.let {
                        // 現在のURLのCookieを保存
                        val currentUrl = it.url
                        if (currentUrl != null) saveCookies(currentSessionId, currentUrl)
                        it.visibility = View.INVISIBLE
                        webViewShowing = false
                        val params = (it.layoutParams as WindowManager.LayoutParams).apply {
                            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        }
                        windowManager.updateViewLayout(it, params)
                    }
                }
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(web, "eval", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val js = args.getOrNull(0)?.let { org.mozilla.javascript.Context.toString(it) } ?: return null
                mainHandler.post { webView?.evaluateJavascript(js, null) }
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(scope, "web", web)
    }

    // ======================================================
    // 通知
    // ======================================================

    private fun termKey(
        label: String,
        bgColor: Int = android.graphics.Color.parseColor("#333333"),
        onClick: () -> Unit
    ): android.widget.Button {
        return android.widget.Button(this).apply {
            text = label
            textSize = 10f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(bgColor)
            setPadding(8, 4, 8, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(2, 0, 2, 0)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "MWV Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val toggleIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_TOGGLE
        }
        val togglePi = PendingIntent.getService(
            this, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exitIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_EXIT
        }
        val exitPi = PendingIntent.getService(
            this, 1, exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val label = if (isShowing) "ターミナルを隠す" else "ターミナルを表示"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MWV Script")
            .setContentText("オーバーレイターミナル")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .addAction(android.R.drawable.ic_menu_manage, label, togglePi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "EXIT", exitPi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
    }
}