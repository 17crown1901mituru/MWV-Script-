package com.mwvscript.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.app.NotificationCompat
import org.mozilla.javascript.ScriptableObject

class OverlayService : Service() {

    companion object {
        var instance: OverlayService? = null
        const val CHANNEL_ID = "mwv_hub"  // HubServiceと同じチャンネルに統合
        const val NOTIF_ID = 1002
        const val ACTION_TOGGLE    = "com.mwvscript.app.OVERLAY_TOGGLE"
        const val ACTION_NOTIF_RUN = "com.mwvscript.app.NOTIF_RUN"
        const val EXTRA_NOTIF_SCRIPT = "notif_script"

        var lastOutput: String = "待機中"

        fun toggle(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_TOGGLE
            }
            context.startForegroundService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var isShowing = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // オーバーレイターミナル用
    private lateinit var terminalContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var activeInput: EditText

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // HubServiceと同じ通知チャンネルを使用（独自チャンネル不要）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        injectOverlayBridge()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> if (isShowing) hideOverlay() else showOverlay()
            ACTION_NOTIF_RUN -> {
                val script = intent.getStringExtra(EXTRA_NOTIF_SCRIPT) ?: return START_STICKY
                HubService.instance?.executeAsync(script)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hideOverlay()
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
            getScreenHeight() / 2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        windowManager.addView(view, params)
        overlayView = view
        isShowing = true
        updateNotification()

        // フォーカスを有効にして入力できるようにする
        mainHandler.postDelayed({
            try {
                val lp = params.apply {
                    flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                }
                windowManager.updateViewLayout(view, lp)
                activeInput.requestFocus()
            } catch (e: Exception) { }
        }, 100)
    }

    private fun hideOverlay() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { }
            overlayView = null
        }
        isShowing = false
        updateNotification()
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

    private fun buildOverlayView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#DD000000"))
        }

        // ヘッダーバー
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#1a1a1a"))
            setPadding(16, 8, 8, 8)
        }

        val title = TextView(this).apply {
            text = "MWV Terminal"
            setTextColor(android.graphics.Color.GREEN)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = Button(this).apply {
            text = "✕"
            textSize = 11f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#550000"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(4, 0, 4, 0)
            }
            setPadding(16, 4, 16, 4)
            setOnClickListener { hideOverlay() }
        }

        header.addView(title)
        header.addView(closeBtn)

        // ターミナル本体
        val termBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
        }
        terminalContainer = termBody

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(terminalContainer)
        }

        // 入力欄
        activeInput = EditText(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(0, 4, 0, 4)
            hint = "> "
            setHintTextColor(android.graphics.Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
            isSingleLine = false
            minLines = 1
        }

        // エクストラキーボード
        val extraKeyboard = buildExtraKeyboard()

        root.addView(header)
        root.addView(scrollView)
        root.addView(activeInput)
        root.addView(extraKeyboard)

        return root
    }

    private fun buildExtraKeyboard(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#111111"))
        }

        val symbolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 4, 4, 0)
        }
        listOf("()", "\"", "'", ";", ".", "/").forEach { symbol ->
            symbolRow.addView(extraKey(symbol) {
                val pos = activeInput.selectionStart
                activeInput.text.insert(pos, symbol)
            })
        }
        container.addView(symbolRow)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 4, 4, 4)
        }

        actionRow.addView(extraKey("RUN", android.graphics.Color.parseColor("#005500")) {
            runInput()
        })

        actionRow.addView(extraKey("PASTE") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: return@extraKey
            val pos = activeInput.selectionStart
            activeInput.text.insert(pos, text)
        })

        actionRow.addView(extraKey("COPY") {
            val selected = activeInput.text.substring(
                activeInput.selectionStart.coerceAtLeast(0),
                activeInput.selectionEnd.coerceAtLeast(0)
            )
            if (selected.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("copied", selected))
                appendOutput("コピーしました")
            }
        })

        actionRow.addView(extraKey("KB") {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
        })

        container.addView(actionRow)
        return container
    }

    private fun extraKey(
        label: String,
        bgColor: Int = android.graphics.Color.parseColor("#222222"),
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(bgColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(2, 2, 2, 2)
            }
            setPadding(4, 8, 4, 8)
            setOnClickListener { onClick() }
        }
    }

    // ======================================================
    // ターミナル入出力
    // ======================================================

    fun appendOutput(text: String) {
        lastOutput = text
        updateNotification()
        mainHandler.post {
            if (!::terminalContainer.isInitialized) return@post
            val tv = TextView(this).apply {
                setTextColor(android.graphics.Color.GREEN)
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                this.text = text
            }
            terminalContainer.addView(tv)
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
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
            val scope = HubService.rhinoScope
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
        val scope = HubService.rhinoScope ?: return
        val service = this

        val cx = org.mozilla.javascript.Context.enter()
        cx.optimizationLevel = -1
        val overlay = cx.newObject(scope) as org.mozilla.javascript.NativeObject
        org.mozilla.javascript.Context.exit()

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

        // ======================================================
        // web オブジェクト（複数セッション対応）
        // ======================================================
        val cx2 = org.mozilla.javascript.Context.enter()
        cx2.optimizationLevel = -1
        val web = cx2.newObject(scope) as org.mozilla.javascript.NativeObject
        org.mozilla.javascript.Context.exit()

        // web.open(url, sessionId?)
        ScriptableObject.putProperty(web, "open", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                // drawer.isWebViewEnabled()チェック
                try {
                    val drawerObj = scope.get("drawer", scope)
                    if (drawerObj is org.mozilla.javascript.Scriptable) {
                        val fn = drawerObj.get("isWebViewEnabled", drawerObj)
                        if (fn is org.mozilla.javascript.Function) {
                            val result = fn.call(cx, scope, drawerObj, emptyArray())
                            if (result == false || result?.toString() == "false") {
                                android.util.Log.d("MWVScript", "web.open: WebViewがOFFのためブロック")
                                return org.mozilla.javascript.Context.getUndefinedValue()
                            }
                        }
                    }
                } catch (_: Exception) {}

                val url       = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: "about:blank")
                val sessionId = if (args.size > 1) org.mozilla.javascript.Context.toString(args[1])
                                else WebViewActivity.DEFAULT_SESSION
                mainHandler.post {
                    val intent = Intent(service, WebViewActivity::class.java).apply {
                        putExtra("url", url)
                        putExtra("sessionId", sessionId)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    service.startActivity(intent)
                }
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // web.eval(js, sessionId?, callback?)
        ScriptableObject.putProperty(web, "eval", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val js        = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: "")
                val sessionId = if (args.size > 1) org.mozilla.javascript.Context.toString(args[1])
                                else WebViewActivity.DEFAULT_SESSION
                val callback  = args.getOrNull(2) as? org.mozilla.javascript.Function
                val activity  = WebViewActivity.sessions[sessionId]
                if (activity == null) {
                    android.util.Log.e("MWVScript", "web.eval: セッション未存在: $sessionId")
                    return org.mozilla.javascript.Context.getUndefinedValue()
                }
                activity.evaluateJs(js) { result ->
                    if (callback != null) {
                        try {
                            val cx2 = org.mozilla.javascript.Context.enter()
                            cx2.optimizationLevel = -1
                            callback.call(cx2, scope, scope, arrayOf(result))
                            org.mozilla.javascript.Context.exit()
                        } catch (e: Exception) {
                            android.util.Log.e("MWVScript", "web.eval callback: ${e.message}")
                        }
                    }
                }
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // web.evalOn(sessionId, js, callback?) - evalの糖衣構文
        ScriptableObject.putProperty(web, "evalOn", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val sessionId = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: WebViewActivity.DEFAULT_SESSION)
                val js        = org.mozilla.javascript.Context.toString(args.getOrNull(1) ?: "")
                val callback  = args.getOrNull(2) as? org.mozilla.javascript.Function
                val activity  = WebViewActivity.sessions[sessionId]
                if (activity == null) {
                    android.util.Log.e("MWVScript", "web.evalOn: セッション未存在: $sessionId")
                    return org.mozilla.javascript.Context.getUndefinedValue()
                }
                activity.evaluateJs(js) { result ->
                    if (callback != null) {
                        try {
                            val cx2 = org.mozilla.javascript.Context.enter()
                            cx2.optimizationLevel = -1
                            callback.call(cx2, scope, scope, arrayOf(result))
                            org.mozilla.javascript.Context.exit()
                        } catch (e: Exception) {
                            android.util.Log.e("MWVScript", "web.evalOn callback: ${e.message}")
                        }
                    }
                }
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // web.close(sessionId?)
        ScriptableObject.putProperty(web, "close", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val sessionId = if (args.isNotEmpty()) org.mozilla.javascript.Context.toString(args[0])
                                else WebViewActivity.DEFAULT_SESSION
                mainHandler.post { WebViewActivity.sessions[sessionId]?.finish() }
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // web.url(sessionId?) → 現在のURL
        ScriptableObject.putProperty(web, "url", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val sessionId = if (args.isNotEmpty()) org.mozilla.javascript.Context.toString(args[0])
                                else WebViewActivity.DEFAULT_SESSION
                return WebViewActivity.sessions[sessionId]?.getCurrentUrl() ?: ""
            }
        })

        // web.cookies(sessionId?) → Cookie文字列
        ScriptableObject.putProperty(web, "cookies", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val sessionId = if (args.isNotEmpty()) org.mozilla.javascript.Context.toString(args[0])
                                else WebViewActivity.DEFAULT_SESSION
                return WebViewActivity.sessions[sessionId]?.getCookies() ?: ""
            }
        })

        // web.sessions() → 起動中のセッションID一覧
        ScriptableObject.putProperty(web, "sessions", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val cx2 = org.mozilla.javascript.Context.enter()
                cx2.optimizationLevel = -1
                val arr = cx2.newArray(scope, WebViewActivity.sessions.keys.toTypedArray())
                org.mozilla.javascript.Context.exit()
                return arr
            }
        })

        // web.set(key, value) → セッション間共有変数
        // web.get(key)        → セッション間共有変数取得
        val sharedVars = mutableMapOf<String, Any?>()
        ScriptableObject.putProperty(web, "set", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val key   = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: "")
                val value = args.getOrNull(1)
                sharedVars[key] = value
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })
        ScriptableObject.putProperty(web, "get", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val key = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: "")
                return sharedVars[key] ?: org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(scope, "web", web)

        // ======================================================
        // terminal オブジェクト
        // ======================================================
        val cx3 = org.mozilla.javascript.Context.enter()
        cx3.optimizationLevel = -1
        val terminal = cx3.newObject(scope) as org.mozilla.javascript.NativeObject
        org.mozilla.javascript.Context.exit()

        // terminal.setInput(text) → MainActivityの入力欄にテキストをセット
        ScriptableObject.putProperty(terminal, "setInput", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val text = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: "")
                mainHandler.post { MainActivity.instance?.setInput(text) }
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // terminal.run(script) → 入力欄にセットしてそのまま実行
        ScriptableObject.putProperty(terminal, "run", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val script = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: "")
                HubService.instance?.executeAsync(script)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(scope, "terminal", terminal)

        // ======================================================
        // shell オブジェクト
        // ======================================================
        val cxShell = org.mozilla.javascript.Context.enter()
        cxShell.optimizationLevel = -1
        val shell = cxShell.newObject(scope) as org.mozilla.javascript.NativeObject
        org.mozilla.javascript.Context.exit()

        // shell.exec(cmd, callback?) → Runtime.exec で実行
        ScriptableObject.putProperty(shell, "exec", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val cmd      = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: "")
                val callback = args.getOrNull(1) as? org.mozilla.javascript.Function
                Thread {
                    try {
                        val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                        val out  = proc.inputStream.bufferedReader().readText()
                        val err  = proc.errorStream.bufferedReader().readText()
                        proc.waitFor()
                        val result = if (err.isNotEmpty()) err else out
                        if (callback != null) {
                            val cx2 = org.mozilla.javascript.Context.enter()
                            cx2.optimizationLevel = -1
                            callback.call(cx2, scope, scope, arrayOf(result))
                            org.mozilla.javascript.Context.exit()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MWVScript", "shell.exec: ${e.message}")
                    }
                }.start()
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // shell.termux(cmd) → Termuxにコマンドを送る
        ScriptableObject.putProperty(shell, "termux", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val cmd = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: "")
                val intent = Intent().apply {
                    setClassName("com.termux", "com.termux.app.RunCommandService")
                    action = "com.termux.RUN_COMMAND"
                    putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                    putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
                    putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                }
                try { service.startService(intent) }
                catch (e: Exception) { android.util.Log.e("MWVScript", "shell.termux: ${e.message}") }
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // shell.tap(x, y) → アクセシビリティ経由タップ
        ScriptableObject.putProperty(shell, "tap", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val x = (args.getOrNull(0) as? Number)?.toFloat() ?: 0f
                val y = (args.getOrNull(1) as? Number)?.toFloat() ?: 0f
                MWVAccessibilityService.instance?.tap(x, y)
                    ?: android.util.Log.e("MWVScript", "shell.tap: アクセシビリティ未接続")
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // shell.swipe(x1, y1, x2, y2, duration?)
        ScriptableObject.putProperty(shell, "swipe", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val x1       = (args.getOrNull(0) as? Number)?.toFloat() ?: 0f
                val y1       = (args.getOrNull(1) as? Number)?.toFloat() ?: 0f
                val x2       = (args.getOrNull(2) as? Number)?.toFloat() ?: 0f
                val y2       = (args.getOrNull(3) as? Number)?.toFloat() ?: 0f
                val duration = (args.getOrNull(4) as? Number)?.toLong() ?: 300L
                MWVAccessibilityService.instance?.swipe(x1, y1, x2, y2, duration)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(scope, "shell", shell)

        // ======================================================
        // alarm オブジェクト（スケジュール実行）
        // ======================================================
        val cxAlarm = org.mozilla.javascript.Context.enter()
        cxAlarm.optimizationLevel = -1
        val alarm = cxAlarm.newObject(scope) as org.mozilla.javascript.NativeObject
        org.mozilla.javascript.Context.exit()

        val alarmManager = service.getSystemService(android.app.AlarmManager::class.java)

        // alarm.set(delayMs, script) → delay後にscriptを実行
        ScriptableObject.putProperty(alarm, "set", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val delayMs = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                val script  = org.mozilla.javascript.Context.toString(args.getOrNull(1) ?: "")
                val intent  = Intent(service, HubService::class.java).apply {
                    action = HubService.ACTION_EXECUTE
                    putExtra(HubService.EXTRA_SCRIPT, script)
                }
                val pi = android.app.PendingIntent.getService(
                    service, script.hashCode(), intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + delayMs, pi
                )
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // alarm.cancel(script) → 登録済みのアラームをキャンセル
        ScriptableObject.putProperty(alarm, "cancel", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val script = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: "")
                val intent = Intent(service, HubService::class.java).apply {
                    action = HubService.ACTION_EXECUTE
                    putExtra(HubService.EXTRA_SCRIPT, script)
                }
                val pi = android.app.PendingIntent.getService(
                    service, script.hashCode(), intent,
                    android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                if (pi != null) alarmManager.cancel(pi)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(scope, "alarm", alarm)

        // ======================================================
        // screen オブジェクト（スクリーンショット）
        // ======================================================
        val cxScreen = org.mozilla.javascript.Context.enter()
        cxScreen.optimizationLevel = -1
        val screen = cxScreen.newObject(scope) as org.mozilla.javascript.NativeObject
        org.mozilla.javascript.Context.exit()

        // screen.capture(callback) → スクリーンショットをBase64でcallbackに渡す
        ScriptableObject.putProperty(screen, "capture", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val callback = args.getOrNull(0) as? org.mozilla.javascript.Function
                MediaProjectionActivity.pendingCallback = { intentData ->
                    if (intentData != null && callback != null) {
                        try {
                            val cx2 = org.mozilla.javascript.Context.enter()
                            cx2.optimizationLevel = -1
                            // intentDataをBase64文字列としてコールバックに渡す
                            // 実際の画像取得はMediaProjectionManagerで別途実装
                            callback.call(cx2, scope, scope, arrayOf(intentData.toString()))
                            org.mozilla.javascript.Context.exit()
                        } catch (e: Exception) {
                            android.util.Log.e("MWVScript", "screen.capture callback: ${e.message}")
                        }
                    }
                }
                val intent = Intent(service, MediaProjectionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(intent)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(scope, "screen", screen)

        android.util.Log.d("MWVScript", "overlayブリッジ注入完了")
    }

    // ======================================================
    // 通知
    // ======================================================

    private fun buildNotification(): Notification {
        // メインアクティビティ起動
        val mainIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val mainPi = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // JS実行: MainActivityを開いてJS入力
        val jsIntent = Intent(this, MainActivity::class.java).apply {
            action = "com.mwvscript.app.OPEN_INPUT"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val jsPi = PendingIntent.getActivity(
            this, 1, jsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ドロワー開閉
        val drawerScript = "if(typeof drawer!=='undefined') drawer.toggle();"
        val drawerIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_NOTIF_RUN
            putExtra(EXTRA_NOTIF_SCRIPT, drawerScript)
        }
        val drawerPi = PendingIntent.getService(
            this, 2, drawerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MWV Script")
            .setContentText(lastOutput)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(mainPi)
            .addAction(android.R.drawable.ic_menu_edit, "JS実行", jsPi)
            .addAction(android.R.drawable.ic_menu_manage, "ドロワー", drawerPi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
    }
}