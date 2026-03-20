package com.mwvscript.app

import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.system.exitProcess

// ============================================================
// MainActivity - タブ型マルチペイン
//
// WebViewService の WindowManager UI を廃止し、
// MainActivity 内でタブ切り替えを行う。
// キーボード・ダイアログは全て MainActivity のウィンドウで
// 正常に描画される。
// ============================================================
class MainActivity : AppCompatActivity() {

    companion object {
        var instance: MainActivity? = null
    }

    // ----------------------------------------------------------
    // タブ種別
    // ----------------------------------------------------------
    enum class TabType { TERMINAL, WEBVIEW, LAUNCHER }

    inner class MWVTab(
        val sessionId: String,
        val type: TabType,
        var label: String,
        var webView: WebView? = null,
        var contentView: View? = null,
        var keepAlive: Boolean = false
    ) {
        fun displayLabel() = when (type) {
            TabType.TERMINAL -> "💻 ${label.take(6)}"
            TabType.WEBVIEW  -> "🌐 ${label.take(8)}"
            TabType.LAUNCHER -> "📱 ${label.take(6)}"
        }
        fun activeView(): View? = webView ?: contentView
    }

    // ----------------------------------------------------------
    // フィールド
    // ----------------------------------------------------------
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tabs = mutableListOf<MWVTab>()
    private var activeSession: String? = null

    // レイアウト
    private lateinit var contentFrame: FrameLayout
    private var floatingDrawerBtn: View? = null
    private lateinit var extraKeyboard: LinearLayout

    // ターミナル用
    private val terminalContainers  = mutableMapOf<String, EditText>()  // 行ごとではなく1つのEditTextに追記
    private val terminalScrollViews = mutableMapOf<String, ScrollView>()
    private val terminalInputs      = mutableMapOf<String, EditText>()

    // デフォルトターミナルセッションID
    private val DEFAULT_TERMINAL = "main_term"

    // ----------------------------------------------------------
    // ライフサイクル
    // ----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        requestPermissions()
        buildUI()
        startServices()
        // デフォルトターミナルタブを作成
        openTab(DEFAULT_TERMINAL, TabType.TERMINAL, "Term")
    }

    override fun onResume() {
        super.onResume()
        instance = this
        // アクティブWebViewを再開
        tabs.firstOrNull { it.sessionId == activeSession }?.webView?.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        // keepAlive=falseのWebViewを停止
        tabs.forEach { if (!it.keepAlive) it.webView?.pauseTimers() }
    }

    override fun onDestroy() {
        tabs.forEach { it.webView?.destroy() }
        tabs.clear()
        removeFloatingDrawerButton()
        instance = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        // バックキーでWebViewを戻る、できなければ何もしない
        val tab = tabs.firstOrNull { it.sessionId == activeSession }
        if (tab?.webView?.canGoBack() == true) {
            tab.webView?.goBack()
        }
        // アプリは終了しない
    }

    // ----------------------------------------------------------
    // UIビルド
    // ----------------------------------------------------------

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // コンテンツエリア（タブバーなし）
        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        // エクストラキーボード
        extraKeyboard = buildExtraKeyboard()

        root.addView(contentFrame)
        root.addView(extraKeyboard)
        setContentView(root)
    }

    private fun startServices() {
        startForegroundService(Intent(this, HubService::class.java))
        startForegroundService(Intent(this, OverlayService::class.java))
        startForegroundService(Intent(this, WebViewService::class.java))
        // フローティングDRAWERボタンを追加（全タブ共通）
        mainHandler.postDelayed({ addFloatingDrawerButton() }, 500)
    }

    // ----------------------------------------------------------
    // タブ管理（public: WebViewService / rjsブリッジから呼ぶ）
    // ----------------------------------------------------------

    fun openTab(
        sessionId: String,
        type: TabType,
        label: String,
        url: String = "about:blank",
        autoJs: String? = null
    ) {
        mainHandler.post {
            // 既存セッションならアクティブ化のみ
            val existing = findTab(sessionId)
            if (existing != null) {
                if (type == TabType.WEBVIEW) existing.webView?.loadUrl(url)
                activateTab(sessionId)
                return@post
            }
            val tab = when (type) {
                TabType.TERMINAL -> createTerminalTab(sessionId, label)
                TabType.WEBVIEW  -> createWebViewTab(sessionId, url, label, autoJs)
                TabType.LAUNCHER -> createLauncherTab(sessionId, label)
            }
            tabs.add(tab)
            refreshTabBar()
            activateTab(sessionId)
        }
    }

    fun closeTab(sessionId: String) {
        mainHandler.post {
            val tab = findTab(sessionId) ?: return@post
            tab.webView?.destroy()
            terminalContainers.remove(sessionId)
            terminalScrollViews.remove(sessionId)
            terminalInputs.remove(sessionId)
            tabs.remove(tab)
            if (tabs.isEmpty()) {
                // ターミナルタブを再作成
                openTab(DEFAULT_TERMINAL, TabType.TERMINAL, "Term")
            } else {
                if (activeSession == sessionId) activateTab(tabs.last().sessionId)
                else refreshTabBar()
            }
        }
    }

    fun evalJs(sessionId: String, js: String, callback: ((String) -> Unit)? = null) {
        mainHandler.post {
            val wv = findTab(sessionId)?.webView ?: run { callback?.invoke(""); return@post }
            wv.evaluateJavascript(js) { callback?.invoke(it ?: "") }
        }
    }

    fun appendToTerminal(sessionId: String, text: String) {
        mainHandler.post {
            val et = terminalContainers[sessionId] ?: return@post
            val sv = terminalScrollViews[sessionId] ?: return@post
            val current = et.text
            if (current.isEmpty()) {
                et.setText(text)
            } else {
                current.append("\n$text")
            }
            sv.post { sv.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    // デフォルトターミナルへの出力（HubService / OverlayServiceから）
    fun appendOutput(text: String) = appendToTerminal(DEFAULT_TERMINAL, text)

    fun setInput(text: String) {
        mainHandler.post {
            terminalInputs[activeSession ?: DEFAULT_TERMINAL]?.let {
                it.setText(text)
                it.setSelection(text.length)
            }
        }
    }

    fun setKeepAlive(sessionId: String, keep: Boolean) {
        mainHandler.post {
            val tab = findTab(sessionId) ?: return@post
            tab.keepAlive = keep
            if (!keep && sessionId != activeSession) tab.webView?.pauseTimers()
            else if (keep) tab.webView?.resumeTimers()
            refreshTabBar()
        }
    }

    fun getCurrentUrl(sessionId: String): String = findTab(sessionId)?.webView?.url ?: ""

    fun getCookies(sessionId: String): String =
        findTab(sessionId)?.let {
            CookieManager.getInstance().getCookie(it.webView?.url ?: "") ?: ""
        } ?: ""

    fun getSessionIds(): Array<String> = tabs.map { it.sessionId }.toTypedArray()

    // ----------------------------------------------------------
    // タブ生成
    // ----------------------------------------------------------

    private fun createTerminalTab(sessionId: String, label: String): MWVTab {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0a0a0f"))
        }
        val outputEdit = EditText(this).apply {
            setTextColor(Color.parseColor("#00ff88"))
            textSize     = 11f
            typeface     = Typeface.MONOSPACE
            setPadding(dp(8), dp(8), dp(8), dp(4))
            setBackgroundColor(Color.parseColor("#0a0a0f"))
            isFocusable     = false
            isClickable     = true
            isCursorVisible = false
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            keyListener = null
            setTextIsSelectable(true)
            // MATCH_PARENTを明示しないとScrollView内で幅0になり折り返しが起きない
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            // 折り返し強制
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                breakStrategy        = android.text.Layout.BREAK_STRATEGY_SIMPLE
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            }
        }
        terminalContainers[sessionId] = outputEdit
        val sv = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(outputEdit)
        }
        terminalScrollViews[sessionId] = sv

        val input = EditText(this).apply {
            setTextColor(Color.WHITE)
            textSize    = 11f
            typeface    = Typeface.MONOSPACE
            setBackgroundColor(Color.TRANSPARENT)
            hint        = "> "
            setHintTextColor(Color.GRAY)
            inputType   = android.text.InputType.TYPE_CLASS_TEXT or
                          android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions  = EditorInfo.IME_FLAG_NO_ENTER_ACTION
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        terminalInputs[sessionId] = input

        val runBtn = Button(this).apply {
            text     = "▶"
            textSize = 12f
            setTextColor(Color.parseColor("#00ff88"))
            setBackgroundColor(Color.parseColor("#1a1a2e"))
            layoutParams = LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { runTerminalInput(sessionId, input) }
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#111122"))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(input)
            addView(runBtn)
        }
        container.addView(sv)
        container.addView(inputRow)

        if (sessionId == DEFAULT_TERMINAL) {
            appendToTerminal(sessionId, "MWV Script v3.0")
            appendToTerminal(sessionId, "")
        }
        return MWVTab(sessionId, TabType.TERMINAL, label, contentView = container)
    }

    private fun runTerminalInput(sessionId: String, input: EditText) {
        val code = input.text.toString().trim()
        if (code.isEmpty()) return
        appendToTerminal(sessionId, "> $code")
        input.setText("")
        val hub = HubService.instance
        if (hub == null || !HubService.isReady) {
            appendToTerminal(sessionId, "エラー: Rhinoエンジン未起動")
            return
        }
        if (code.endsWith(".rjs") || code.endsWith(".js")) hub.loadAndExecute(code)
        else hub.executeAsync(code)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebViewTab(
        sessionId: String, url: String, label: String, autoJs: String?
    ): MWVTab {
        val wv = WebView(this).apply {
            settings.apply {
                javaScriptEnabled    = true
                domStorageEnabled    = true
                databaseEnabled      = true
                setSupportZoom(true)
                builtInZoomControls  = true
                displayZoomControls  = false
                mixedContentMode     = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    autoJs?.let { view.evaluateJavascript(it, null) }
                    refreshTabBar()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String) {
                    findTab(sessionId)?.label = title.take(12)
                    refreshTabBar()
                }
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.loadUrl(url)
        return MWVTab(sessionId, TabType.WEBVIEW, label, webView = wv)
    }

    private fun createLauncherTab(sessionId: String, label: String): MWVTab {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0a0a0f"))
        }
        val searchBox = EditText(this).apply {
            hint      = "🔍 アプリを検索..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1a1a2e"))
            textSize  = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val grid = GridView(this).apply {
            numColumns  = 4
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.parseColor("#0a0a0f"))
        }
        container.addView(searchBox)
        container.addView(grid)

        Thread {
            val pm   = packageManager
            val apps = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                PackageManager.MATCH_ALL
            ).sortedBy { it.loadLabel(pm).toString() }
            mainHandler.post {
                val adapter = LauncherAdapter(apps, pm)
                grid.adapter = adapter
                grid.setOnItemClickListener { _, _, pos, _ ->
                    val info = adapter.getItem(pos) ?: return@setOnItemClickListener
                    pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                        ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        ?.let { startActivity(it) }
                }
                searchBox.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                        adapter.filter(s?.toString() ?: "")
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }
        }.start()
        return MWVTab(sessionId, TabType.LAUNCHER, label, contentView = container)
    }

    // ----------------------------------------------------------
    // ランチャーAdapter
    // ----------------------------------------------------------
    inner class LauncherAdapter(
        private val allItems: List<android.content.pm.ResolveInfo>,
        private val pm: PackageManager
    ) : BaseAdapter() {
        private var items = allItems.toMutableList()
        fun filter(q: String) {
            items = if (q.isEmpty()) allItems.toMutableList()
            else allItems.filter { it.loadLabel(pm).toString().contains(q, true) }.toMutableList()
            notifyDataSetChanged()
        }
        override fun getCount()          = items.size
        override fun getItem(pos: Int)   = items.getOrNull(pos)
        override fun getItemId(pos: Int) = pos.toLong()
        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val info = items[pos]
            return LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity     = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
                addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(runCatching { info.loadIcon(pm) }.getOrNull())
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                    scaleType    = ImageView.ScaleType.FIT_CENTER
                })
                addView(TextView(this@MainActivity).apply {
                    text     = info.loadLabel(pm).toString()
                    setTextColor(Color.WHITE)
                    textSize = 9f
                    gravity  = Gravity.CENTER
                    maxLines = 2
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }
        }
    }

    // ----------------------------------------------------------
    // タブバーUI
    // ----------------------------------------------------------

    private fun refreshTabBar() {
        // タブ管理はドロワーに移管済み。何もしない。
    }

    // ドロワー側からタブ情報を取得するためのAPI
    fun getTabInfoList(): List<Map<String, String>> = tabs.map {
        mapOf("sessionId" to it.sessionId, "label" to it.displayLabel(),
              "type" to it.type.name, "active" to (it.sessionId == activeSession).toString())
    }

    fun activateTab(sessionId: String) {
        val prev = activeSession
        activeSession = sessionId
        mainHandler.post {
            // 前タブのWebViewを停止
            if (prev != null && prev != sessionId) {
                findTab(prev)?.let { if (!it.keepAlive) it.webView?.pauseTimers() }
            }
            contentFrame.removeAllViews()
            val tab  = findTab(sessionId) ?: return@post
            val view = tab.activeView() ?: return@post
            (view.parent as? ViewGroup)?.removeView(view)
            contentFrame.addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
            tab.webView?.resumeTimers()

            // ターミナルタブのときエクストラキーボードを表示、他は非表示
            extraKeyboard.visibility = if (tab.type == TabType.TERMINAL)
                View.VISIBLE else View.GONE

            refreshTabBar()
        }
    }

    // ----------------------------------------------------------
    // ダイアログ
    // ----------------------------------------------------------

    private fun showNewTabDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("新規タブ")
            .setItems(arrayOf("💻 ターミナル", "🌐 WebView", "📱 ランチャー")) { _, which ->
                when (which) {
                    0 -> openTab("term_${System.currentTimeMillis()}", TabType.TERMINAL, "Term")
                    1 -> showUrlInputDialog()
                    2 -> openTab("apps_${System.currentTimeMillis()}", TabType.LAUNCHER, "Apps")
                }
            }.show()
    }

    private fun showUrlInputDialog() {
        val input = EditText(this).apply {
            hint      = "https://"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("URL を入力")
            .setView(input)
            .setPositiveButton("開く") { _, _ ->
                val raw = input.text.toString().trim()
                val url = if (!raw.startsWith("http")) "https://$raw" else raw
                openTab("web_${System.currentTimeMillis()}", TabType.WEBVIEW, raw.take(20), url)
            }
            .setNegativeButton("キャンセル", null).show()
    }

    private fun showTabMenu(tab: MWVTab) {
        val keepLabel = if (tab.keepAlive) "🔒 アクティブキープ OFF" else "🔓 アクティブキープ ON"
        android.app.AlertDialog.Builder(this)
            .setTitle(tab.displayLabel())
            .setItems(arrayOf(keepLabel, "閉じる", "キャンセル")) { _, which ->
                when (which) {
                    0 -> setKeepAlive(tab.sessionId, !tab.keepAlive)
                    1 -> closeTab(tab.sessionId)
                }
            }.show()
    }

    // ----------------------------------------------------------
    // エクストラキーボード（ターミナルタブ用）
    // ----------------------------------------------------------

    private fun buildExtraKeyboard(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
        }
        val symbolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(2), dp(2), 0)
        }
        listOf("()", "\"", "'", ";", ".", "/", "{}", "[]").forEach { sym ->
            symbolRow.addView(extraKey(sym) {
                currentInput()?.let { it.text.insert(it.selectionStart.coerceAtLeast(0), sym) }
            })
        }
        container.addView(symbolRow)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        actionRow.addView(extraKey("RUN", Color.parseColor("#005500")) {
            activeSession?.let { sid ->
                terminalInputs[sid]?.let { runTerminalInput(sid, it) }
            }
        })
        actionRow.addView(extraKey("PASTE") {
            val cb   = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cb.primaryClip?.getItemAt(0)?.text?.toString() ?: return@extraKey
            currentInput()?.let { it.text.insert(it.selectionStart.coerceAtLeast(0), text) }
        })
        actionRow.addView(extraKey("COPY") {
            // 選択範囲があればそれを、なければ全テキストをコピー
            val sid = activeSession ?: DEFAULT_TERMINAL
            val et  = terminalContainers[sid] ?: return@extraKey
            val sel = et.text.substring(
                et.selectionStart.coerceAtLeast(0),
                et.selectionEnd.coerceAtLeast(0))
            val text = if (sel.isNotEmpty()) sel else et.text.toString()
            if (text.isNotEmpty()) {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("terminal", text))
                appendOutput("📋 コピー完了 (${text.lines().size}行)")
            }
        })
        actionRow.addView(extraKey("KB") {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val tab = tabs.firstOrNull { it.sessionId == activeSession }
            if (tab?.type == TabType.WEBVIEW) {
                // WebViewタブ: WebViewにフォーカスを与えてからキーボードを出す
                tab.webView?.let { wv ->
                    wv.requestFocus()
                    imm.showSoftInput(wv, InputMethodManager.SHOW_FORCED)
                }
            } else {
                // ターミナルタブ: EditTextにフォーカスを与えてキーボードを出す
                currentInput()?.let { input ->
                    input.requestFocus()
                    imm.showSoftInput(input, InputMethodManager.SHOW_FORCED)
                }
            }
        })
        actionRow.addView(extraKey("DRAWER", Color.parseColor("#003355")) {
            HubService.instance?.executeAsync("if(typeof drawer!=='undefined') drawer.toggle();")
        })
        actionRow.addView(extraKey("EXIT", Color.parseColor("#550000")) {
            stopService(Intent(this, HubService::class.java))
            stopService(Intent(this, WebViewService::class.java))
            stopService(Intent(this, OverlayService::class.java))
            finishAffinity()
            exitProcess(0)
        })
        container.addView(actionRow)
        return container
    }

    private fun currentInput(): EditText? =
        terminalInputs[activeSession ?: DEFAULT_TERMINAL]

    private fun extraKey(
        label: String,
        bgColor: Int = Color.parseColor("#222222"),
        onClick: () -> Unit
    ) = Button(this).apply {
        text = label
        textSize = 10f
        setTextColor(Color.WHITE)
        setBackgroundColor(bgColor)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(dp(1), dp(1), dp(1), dp(1)) }
        setPadding(dp(2), dp(6), dp(2), dp(6))
        setOnClickListener { onClick() }
    }

    // ----------------------------------------------------------
    // 権限取得
    // ----------------------------------------------------------

    private fun requestPermissions() {
        val perms = arrayOf(
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.READ_PHONE_STATE,
        )
        ActivityCompat.requestPermissions(this, perms, 0)
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !android.os.Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")))
        }
    }

    // ----------------------------------------------------------
    // フローティングDRAWERボタン（WindowManagerで常時表示）
    // ----------------------------------------------------------

    private fun addFloatingDrawerButton() {
        if (floatingDrawerBtn != null) return
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(200)
        }
        val btn = TextView(this).apply {
            text     = "☰"
            textSize = 18f
            gravity  = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#cc1a1a2e"))
            setPadding(dp(14), dp(12), dp(14), dp(12))

            var dX = 0f; var dY = 0f
            var isDragging = false
            var downX = 0f; var downY = 0f

            setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        dX = lp.x - event.rawX
                        dY = lp.y - event.rawY
                        downX = event.rawX; downY = event.rawY
                        isDragging = false
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = Math.abs(event.rawX - downX)
                        val dy = Math.abs(event.rawY - downY)
                        if (dx > dp(8) || dy > dp(8)) isDragging = true
                        if (isDragging) {
                            lp.x = (event.rawX + dX).toInt()
                            lp.y = (event.rawY + dY).toInt()
                            try { wm.updateViewLayout(v, lp) } catch (e: Exception) {}
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            // ドラッグでなければタップ → ドロワー開閉
                            HubService.instance?.executeAsync(
                                "if(typeof drawer!=='undefined') drawer.toggle();")
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        try {
            wm.addView(btn, lp)
            floatingDrawerBtn = btn
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "floatingBtn: ${e.message}")
        }
    }

    private fun removeFloatingDrawerButton() {
        val btn = floatingDrawerBtn ?: return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            wm.removeView(btn)
        } catch (e: Exception) {}
        floatingDrawerBtn = null
    }

    // ----------------------------------------------------------
    // ユーティリティ
    // ----------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun findTab(sessionId: String) = tabs.firstOrNull { it.sessionId == sessionId }
}
