package com.mwvscript.app

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
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

// ============================================================
// WebViewService - マルチペインプラットフォーム
//
// WindowManager上にタブバーを持つフレームを常駐させ
// TERMINAL / WEBVIEW / LAUNCHER の3種類のペインを
// 最大MAX_TABSタブまで管理する。
//
// これによりMWV ScriptはAndroidのタスク管理を迂回して
// 複数アプリ・複数Webページをフォアグラウンド同等に
// 扱えるプラットフォームとなる。
// ============================================================
class WebViewService : Service() {

    companion object {
        private const val TAG      = "WebViewService"
        const val CHANNEL_ID       = "mwv_hub"
        const val NOTIF_ID         = 2002

        // SM-S938Z (RAM 12GB) 基準
        // WebView重量ページ想定200-400MB × 16 + 本体500MB ≒ 7GB
        // 安全マージンを取り16タブ上限
        const val MAX_TABS         = 16
        const val DEFAULT_SESSION  = "default"

        const val ACTION_OPEN      = "com.mwvscript.app.WEB_OPEN"
        const val ACTION_CLOSE     = "com.mwvscript.app.WEB_CLOSE"
        const val EXTRA_URL        = "url"
        const val EXTRA_SESSION    = "sessionId"
        const val EXTRA_TYPE       = "tabType"

        val sharedVars = mutableMapOf<String, Any?>()

        var instance: WebViewService? = null
            private set
    }

    // ----------------------------------------------------------
    // タブ種別
    // ----------------------------------------------------------
    enum class TabType { TERMINAL, WEBVIEW, LAUNCHER }

    // ----------------------------------------------------------
    // タブデータ
    // ----------------------------------------------------------
    inner class MWVTab(
        val sessionId: String,
        val type: TabType,
        var label: String,
        var contentView: View? = null,
        var webView: WebView?  = null,
        var keepAlive: Boolean = false,   // 非アクティブ時もJS継続
        var savedUrl: String?  = null     // メモリ解放時のURL保存
    ) {
        fun displayLabel(): String {
            val lock = if (keepAlive) "🔒" else ""
            return when (type) {
                TabType.TERMINAL -> "💻$lock ${label.take(6)}"
                TabType.WEBVIEW  -> "🌐$lock ${label.take(8)}"
                TabType.LAUNCHER -> "📱$lock ${label.take(6)}"
            }
        }
        fun activeView(): View? = webView ?: contentView
    }

    // ----------------------------------------------------------
    // フィールド
    // ----------------------------------------------------------
    private lateinit var wm: WindowManager
    private lateinit var mainHandler: Handler

    private val tabs = mutableListOf<MWVTab>()
    private var activeSession: String? = null

    private var rootView: View?               = null
    private var tabScrollView: HorizontalScrollView? = null
    private var tabBarInner: LinearLayout?    = null
    private var contentFrame: FrameLayout?   = null

    private val terminalContainers  = mutableMapOf<String, LinearLayout>()
    private val terminalScrollViews = mutableMapOf<String, ScrollView>()
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var isOverlayVisible = true

    // ----------------------------------------------------------
    // ライフサイクル
    // ----------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        mainHandler = Handler(Looper.getMainLooper())
        startForeground(NOTIF_ID, buildNotification("MWV Script 動作中"))
        Log.i(TAG, "WebViewService created MAX_TABS=$MAX_TABS")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OPEN -> {
                val url       = intent.getStringExtra(EXTRA_URL) ?: "about:blank"
                val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: DEFAULT_SESSION
                val typeStr   = intent.getStringExtra(EXTRA_TYPE) ?: "WEBVIEW"
                val type      = runCatching { TabType.valueOf(typeStr) }.getOrDefault(TabType.WEBVIEW)
                openTab(url, sessionId, type)
            }
            ACTION_CLOSE -> closeTab(intent.getStringExtra(EXTRA_SESSION) ?: DEFAULT_SESSION)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        tabs.forEach { it.webView?.destroy() }
        tabs.clear()
        removeRootView()
        super.onDestroy()
    }

    // ----------------------------------------------------------
    // 公開 API
    // ----------------------------------------------------------

    fun openTab(
        url: String = "about:blank",
        sessionId: String = DEFAULT_SESSION,
        type: TabType = TabType.WEBVIEW,
        autoJs: String? = null,
        label: String? = null
    ) {
        mainHandler.post {
            val existing = findTab(sessionId)
            if (existing != null) {
                if (type == TabType.WEBVIEW) {
                    existing.webView?.loadUrl(url)
                    autoJs?.let { existing.webView?.evaluateJavascript(it, null) }
                }
                activateTab(sessionId)
                return@post
            }
            if (tabs.size >= MAX_TABS) {
                Log.w(TAG, "MAX_TABS=$MAX_TABS に達しました")
                appendToTerminal(DEFAULT_SESSION, "⚠ タブ上限 $MAX_TABS に達しました")
                return@post
            }
            val tab = when (type) {
                TabType.TERMINAL -> createTerminalTab(sessionId, label ?: "Term")
                TabType.WEBVIEW  -> createWebViewTab(sessionId, url, label ?: url.take(20), autoJs)
                TabType.LAUNCHER -> createLauncherTab(sessionId, label ?: "Apps")
            }
            tabs.add(tab)
            ensureRootView()
            refreshTabBar()
            activateTab(sessionId)
        }
    }

    fun evalJs(sessionId: String, js: String, callback: ((String) -> Unit)? = null) {
        mainHandler.post {
            val wv = findTab(sessionId)?.webView
            if (wv == null) { callback?.invoke(""); return@post }
            wv.evaluateJavascript(js) { callback?.invoke(it ?: "") }
        }
    }

    fun closeTab(sessionId: String) {
        mainHandler.post {
            val tab = findTab(sessionId) ?: return@post
            tab.webView?.destroy()
            terminalContainers.remove(sessionId)
            terminalScrollViews.remove(sessionId)
            tabs.remove(tab)
            if (tabs.isEmpty()) removeRootView()
            else {
                if (activeSession == sessionId) activateTab(tabs.last().sessionId)
                else refreshTabBar()
            }
        }
    }

    fun appendToTerminal(sessionId: String, text: String) {
        mainHandler.post {
            val container = terminalContainers[sessionId] ?: return@post
            val sv        = terminalScrollViews[sessionId] ?: return@post
            container.addView(TextView(this).apply {
                this.text = text
                setTextColor(Color.parseColor("#00ff88"))
                textSize  = 11f
                typeface  = Typeface.MONOSPACE
                setPadding(dp(4), dp(1), dp(4), dp(1))
            })
            sv.post { sv.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    fun getCurrentUrl(sessionId: String): String =
        findTab(sessionId)?.webView?.url ?: ""

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
        val outputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        terminalContainers[sessionId] = outputContainer
        val sv = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(outputContainer)
        }
        terminalScrollViews[sessionId] = sv

        val input = EditText(this).apply {
            setTextColor(Color.WHITE)
            textSize   = 11f
            typeface   = Typeface.MONOSPACE
            setBackgroundColor(Color.TRANSPARENT)
            hint       = "> "
            setHintTextColor(Color.GRAY)
            inputType  = android.text.InputType.TYPE_CLASS_TEXT or
                         android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val runBtn = Button(this).apply {
            text     = "▶"
            textSize = 12f
            setTextColor(Color.parseColor("#00ff88"))
            setBackgroundColor(Color.parseColor("#1a1a2e"))
            layoutParams = LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT)
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
        container.addView(buildSymbolRow(input))
        container.addView(inputRow)
        return MWVTab(sessionId, TabType.TERMINAL, label, contentView = container)
    }

    private fun runTerminalInput(sessionId: String, input: EditText) {
        val code = input.text.toString().trim()
        if (code.isEmpty()) return
        appendToTerminal(sessionId, "> $code")
        input.setText("")
        Thread {
            val scope = HubService.rhinoScope ?: run {
                appendToTerminal(sessionId, "エラー: Rhinoエンジン未起動")
                return@Thread
            }
            try {
                val cx = org.mozilla.javascript.Context.enter()
                cx.optimizationLevel = -1
                val result = cx.evaluateString(scope, code, "<term:$sessionId>", 1, null)
                org.mozilla.javascript.Context.exit()
                val str = org.mozilla.javascript.Context.toString(result)
                if (str != "undefined") appendToTerminal(sessionId, str)
            } catch (e: Exception) {
                try { org.mozilla.javascript.Context.exit() } catch (_: Exception) {}
                appendToTerminal(sessionId, "エラー: ${e.message}")
            }
        }.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebViewTab(
        sessionId: String,
        url: String,
        label: String,
        autoJs: String?
    ): MWVTab {
        val wv = WebView(this).apply {
            settings.apply {
                javaScriptEnabled   = true
                domStorageEnabled   = true
                databaseEnabled     = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode    = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    autoJs?.let { view.evaluateJavascript(it, null) }
                    findTab(sessionId)?.let { refreshTabBar() }
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
            return LinearLayout(this@WebViewService).apply {
                orientation = LinearLayout.VERTICAL
                gravity     = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
                addView(ImageView(this@WebViewService).apply {
                    setImageDrawable(runCatching { info.loadIcon(pm) }.getOrNull())
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                    scaleType    = ImageView.ScaleType.FIT_CENTER
                })
                addView(TextView(this@WebViewService).apply {
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
    // UI
    // ----------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun ensureRootView() {
        if (rootView != null) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        // タブバー（横スクロール）
        val tabInner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT)
        }
        tabBarInner = tabInner
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
            setBackgroundColor(Color.parseColor("#0f0f1e"))
            addView(tabInner)
        }
        tabScrollView = tabScroll
        // コンテンツ
        val content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        contentFrame = content
        root.addView(tabScroll)
        root.addView(content)
        rootView = root
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_NOT_TOUCH_MODALのみだとフォーカスがなくタッチが下に抜ける
            // FLAG_LAYOUT_IN_SCREENでステータスバー下に正しく配置
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(root, lp)
        overlayLayoutParams = lp
    }

    /** オーバーレイ全体を非表示（最小化） */
    fun hideOverlay() {
        val root = rootView ?: return
        val lp   = overlayLayoutParams ?: return
        mainHandler.post {
            try {
                lp.width  = 1
                lp.height = 1
                lp.flags  = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                wm.updateViewLayout(root, lp)
                isOverlayVisible = false
            } catch (e: Exception) { Log.w(TAG, e) }
        }
    }

    /** オーバーレイ全体を再表示 */
    fun showOverlay() {
        val root = rootView ?: return
        val lp   = overlayLayoutParams ?: return
        mainHandler.post {
            try {
                lp.width  = WindowManager.LayoutParams.MATCH_PARENT
                lp.height = WindowManager.LayoutParams.MATCH_PARENT
                lp.flags  = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                wm.updateViewLayout(root, lp)
                isOverlayVisible = true
            } catch (e: Exception) { Log.w(TAG, e) }
        }
    }

    fun isVisible() = isOverlayVisible

    private fun removeRootView() {
        rootView?.let { try { wm.removeView(it) } catch (e: Exception) { Log.w(TAG, e) } }
        rootView = null; tabScrollView = null; tabBarInner = null; contentFrame = null
        overlayLayoutParams = null
    }

    private fun refreshTabBar() {
        val bar = tabBarInner ?: return
        mainHandler.post {
            bar.removeAllViews()
            tabs.forEach { tab ->
                val isActive = tab.sessionId == activeSession
                bar.addView(TextView(this).apply {
                    text     = tab.displayLabel()
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                    gravity  = Gravity.CENTER
                    setPadding(dp(10), 0, dp(10), 0)
                    setTextColor(if (isActive) Color.parseColor("#e94560") else Color.parseColor("#888899"))
                    setBackgroundColor(if (isActive) Color.parseColor("#1a1a2e") else Color.parseColor("#0f0f1e"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT)
                    setOnClickListener     { activateTab(tab.sessionId) }
                    setOnLongClickListener { showTabMenu(tab); true }
                })
            }
            if (tabs.size < MAX_TABS) {
                bar.addView(TextView(this).apply {
                    text     = " ⊕ "
                    textSize = 16f
                    gravity  = Gravity.CENTER
                    setTextColor(Color.parseColor("#e94560"))
                    setBackgroundColor(Color.parseColor("#0f0f1e"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT)
                    setOnClickListener { showNewTabDialog() }
                })
            }
            // 最小化ボタン（常に右端）
            bar.addView(TextView(this).apply {
                text     = " ━ "
                textSize = 16f
                gravity  = Gravity.CENTER
                setTextColor(Color.parseColor("#888899"))
                setBackgroundColor(Color.parseColor("#0f0f1e"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT)
                setOnClickListener { hideOverlay() }
            })
        }
    }

    private fun activateTab(sessionId: String) {
        val prev = activeSession
        activeSession = sessionId
        val frame = contentFrame ?: return
        mainHandler.post {
            // 前のWebViewタブを一時停止（keepAlive=falseの場合）
            if (prev != null && prev != sessionId) {
                findTab(prev)?.webView?.let { wv ->
                    if (findTab(prev)?.keepAlive == false) wv.pauseTimers()
                }
            }
            frame.removeAllViews()
            val tab  = findTab(sessionId) ?: return@post
            val view = tab.activeView() ?: return@post
            (view.parent as? ViewGroup)?.removeView(view)
            frame.addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
            // アクティブになったWebViewを再開
            tab.webView?.resumeTimers()
            refreshTabBar()
            tabScrollView?.post {
                val idx   = tabs.indexOfFirst { it.sessionId == sessionId }
                val child = tabBarInner?.getChildAt(idx) ?: return@post
                tabScrollView?.smoothScrollTo(child.left, 0)
            }
        }
    }

    /** アクティブキープON/OFF（rjsから呼ぶ） */
    fun setKeepAlive(sessionId: String, keep: Boolean) {
        mainHandler.post {
            val tab = findTab(sessionId) ?: return@post
            tab.keepAlive = keep
            if (!keep && sessionId != activeSession) {
                tab.webView?.pauseTimers()
            } else if (keep) {
                tab.webView?.resumeTimers()
            }
            refreshTabBar()
        }
    }

    // ----------------------------------------------------------
    // ダイアログ
    // ----------------------------------------------------------

    private fun showNewTabDialog() {
        val act = MainActivity.instance ?: return
        android.app.AlertDialog.Builder(act)
            .setTitle("新規タブ（${tabs.size} / $MAX_TABS）")
            .setItems(arrayOf("💻 ターミナル", "🌐 WebView", "📱 ランチャー")) { _, which ->
                when (which) {
                    0 -> openTab(sessionId = "term_${System.currentTimeMillis()}",
                                 type = TabType.TERMINAL, label = "Term")
                    1 -> showUrlInputDialog(act)
                    2 -> openTab(sessionId = "apps_${System.currentTimeMillis()}",
                                 type = TabType.LAUNCHER, label = "Apps")
                }
            }.show()
    }

    private fun showUrlInputDialog(act: Context) {
        val input = EditText(act).apply {
            hint      = "https://"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        android.app.AlertDialog.Builder(act)
            .setTitle("URL を入力")
            .setView(input)
            .setPositiveButton("開く") { _, _ ->
                val raw = input.text.toString().trim()
                val url = if (!raw.startsWith("http")) "https://$raw" else raw
                openTab(url, "web_${System.currentTimeMillis()}", TabType.WEBVIEW, label = raw.take(20))
            }
            .setNegativeButton("キャンセル", null).show()
    }

    private fun showTabMenu(tab: MWVTab) {
        val act = MainActivity.instance ?: return
        val keepLabel = if (tab.keepAlive) "🔒 アクティブキープ OFF にする"
                        else              "🔓 アクティブキープ ON にする"
        android.app.AlertDialog.Builder(act)
            .setTitle(tab.displayLabel())
            .setItems(arrayOf(keepLabel, "閉じる", "キャンセル")) { _, which ->
                when (which) {
                    0 -> setKeepAlive(tab.sessionId, !tab.keepAlive)
                    1 -> closeTab(tab.sessionId)
                }
            }.show()
    }

    // ----------------------------------------------------------
    // 記号キーボード
    // ----------------------------------------------------------

    private fun buildSymbolRow(input: EditText): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#111122"))
            setPadding(dp(2), dp(2), dp(2), 0)
            listOf("()", "\"", "'", ";", ".", "/", "{}", "[]").forEach { sym ->
                addView(Button(this@WebViewService).apply {
                    text     = sym
                    textSize = 10f
                    setTextColor(Color.parseColor("#aaaacc"))
                    setBackgroundColor(Color.parseColor("#1a1a2e"))
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(1), 0, dp(1), 0)
                    }
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                    setOnClickListener {
                        input.text.insert(input.selectionStart.coerceAtLeast(0), sym)
                    }
                })
            }
        }

    // ----------------------------------------------------------
    // ユーティリティ
    // ----------------------------------------------------------

    private fun findTab(sessionId: String) = tabs.firstOrNull { it.sessionId == sessionId }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MWV Script")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
}
