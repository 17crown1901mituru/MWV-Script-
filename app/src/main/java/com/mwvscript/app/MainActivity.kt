package com.mwvscript.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import java.io.File

data class Tab(
    val id: Int,
    val label: String,
    val url: String,
    val accountId: String,
    var webView: WebView? = null,
    var cookieStore: String = "account_$accountId"
)

class MainActivity : AppCompatActivity() {

    private val tabs = mutableListOf<Tab>()
    private var activeTabIndex = 0
    private val gson = Gson()

    private lateinit var tabBar: LinearLayout
    private lateinit var webViewContainer: FrameLayout
    private lateinit var btnAddTab: ImageButton
    private lateinit var btnScripts: ImageButton
    private lateinit var btnService: ImageButton
    private lateinit var urlBar: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabBar = findViewById(R.id.tabBar)
        webViewContainer = findViewById(R.id.webViewContainer)
        btnAddTab = findViewById(R.id.btnAddTab)
        btnScripts = findViewById(R.id.btnScripts)
        btnService = findViewById(R.id.btnService)
        urlBar = findViewById(R.id.urlBar)

        WebView.setWebContentsDebuggingEnabled(true)

        btnAddTab.setOnClickListener { showAddTabDialog() }
        btnScripts.setOnClickListener { showScriptManager() }
        btnService.setOnClickListener { toggleService() }

        urlBar.setOnEditorActionListener { _, _, _ ->
            tabs.getOrNull(activeTabIndex)?.webView?.loadUrl(urlBar.text.toString())
            true
        }

        // Load saved tabs or open default
        loadSavedTabs()
        if (tabs.isEmpty()) addTab("Account 1", "https://www.google.com", "1")
    }

    // ── TAB MANAGEMENT ────────────────────────────────────────────────────────

    fun addTab(label: String, url: String, accountId: String) {
        val id = System.currentTimeMillis().toInt()
        val tab = Tab(id, label, url, accountId)
        tab.webView = createWebView(accountId)
        tab.webView!!.loadUrl(url)
        tabs.add(tab)
        renderTabBar()
        switchTab(tabs.size - 1)
        saveTabs()
    }

    fun switchTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        activeTabIndex = index
        webViewContainer.removeAllViews()
        tabs[index].webView?.let {
            webViewContainer.addView(it, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            urlBar.setText(it.url ?: tabs[index].url)
        }
        renderTabBar()
    }

    fun closeTab(index: Int) {
        if (tabs.size <= 1) return
        tabs[index].webView?.destroy()
        tabs.removeAt(index)
        val newIndex = if (activeTabIndex >= tabs.size) tabs.size - 1 else activeTabIndex
        renderTabBar()
        switchTab(newIndex)
        saveTabs()
    }

    private fun renderTabBar() {
        tabBar.removeAllViews()
        tabs.forEachIndexed { i, tab ->
            val view = LayoutInflater.from(this)
                .inflate(R.layout.tab_item, tabBar, false)
            view.findViewById<TextView>(R.id.tabLabel).text = tab.label
            view.findViewById<ImageButton>(R.id.tabClose).setOnClickListener { closeTab(i) }
            view.isSelected = i == activeTabIndex
            view.setOnClickListener { switchTab(i) }
            tabBar.addView(view)
        }
    }

    // ── WEBVIEW FACTORY ───────────────────────────────────────────────────────

    private fun createWebView(accountId: String): WebView {
        val wv = WebView(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = userAgentString.replace("wv", "")
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // Isolated cookie store per account
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(wv, true)

        wv.addJavascriptInterface(ScriptBridge(this, accountId), "MWVScript")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                urlBar.setText(url)
                injectScripts(view, url)
            }
        }

        return wv
    }

    // ── SCRIPT INJECTION ──────────────────────────────────────────────────────

    private fun injectScripts(webView: WebView, url: String) {
        val scriptDir = File(getExternalFilesDir(null), "scripts")
        if (!scriptDir.exists()) return

        scriptDir.listFiles { f -> f.extension == "js" }?.forEach { file ->
            val meta = parseScriptMeta(file.readText())
            val matchPattern = meta["@match"] ?: "*"
            if (urlMatchesPattern(url, matchPattern)) {
                val js = file.readText()
                webView.evaluateJavascript(js, null)
            }
        }
    }

    private fun parseScriptMeta(source: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val regex = Regex("//\\s*@(\\w+)\\s+(.+)")
        regex.findAll(source).forEach {
            result[it.groupValues[1]] = it.groupValues[2].trim()
        }
        return result
    }

    private fun urlMatchesPattern(url: String, pattern: String): Boolean {
        if (pattern == "*") return true
        val regex = pattern.replace(".", "\\.").replace("*", ".*")
        return Regex(regex).containsMatchIn(url)
    }

    // ── DIALOGS ───────────────────────────────────────────────────────────────

    private fun showAddTabDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_tab, null)
        val etLabel = view.findViewById<EditText>(R.id.etLabel)
        val etUrl = view.findViewById<EditText>(R.id.etUrl)
        val etAccount = view.findViewById<EditText>(R.id.etAccount)

        etAccount.setText((tabs.size + 1).toString())

        AlertDialog.Builder(this)
            .setTitle("新しいタブ")
            .setView(view)
            .setPositiveButton("追加") { _, _ ->
                val label = etLabel.text.toString().ifEmpty { "Account ${tabs.size + 1}" }
                val url = etUrl.text.toString().ifEmpty { "https://www.google.com" }
                val account = etAccount.text.toString().ifEmpty { (tabs.size + 1).toString() }
                addTab(label, url, account)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showScriptManager() {
        val scriptDir = File(getExternalFilesDir(null), "scripts")
        scriptDir.mkdirs()
        val files = scriptDir.listFiles { f -> f.extension == "js" }
            ?.map { it.name }?.toTypedArray() ?: arrayOf()

        val msg = if (files.isEmpty())
            "スクリプトなし\n\n${scriptDir.absolutePath}\nにJSファイルを置いてください"
        else files.joinToString("\n")

        AlertDialog.Builder(this)
            .setTitle("スクリプト一覧")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toggleService() {
        val intent = Intent(this, ScriptEngineService::class.java)
        if (ScriptEngineService.isRunning) {
            stopService(intent)
            btnService.setImageResource(android.R.drawable.ic_media_play)
            Toast.makeText(this, "エンジン停止", Toast.LENGTH_SHORT).show()
        } else {
            startForegroundService(intent)
            btnService.setImageResource(android.R.drawable.ic_media_pause)
            Toast.makeText(this, "エンジン起動", Toast.LENGTH_SHORT).show()
        }
    }

    // ── PERSISTENCE ───────────────────────────────────────────────────────────

    private fun saveTabs() {
        val data = tabs.map { mapOf("label" to it.label, "url" to it.webView?.url ?: it.url, "accountId" to it.accountId) }
        getSharedPreferences("mwv", MODE_PRIVATE).edit()
            .putString("tabs", gson.toJson(data)).apply()
    }

    private fun loadSavedTabs() {
        val json = getSharedPreferences("mwv", MODE_PRIVATE).getString("tabs", null) ?: return
        try {
            val list = gson.fromJson(json, Array<Map<String, String>>::class.java)
            list.forEach { addTab(it["label"] ?: "Tab", it["url"] ?: "https://google.com", it["accountId"] ?: "1") }
        } catch (e: Exception) { }
    }

    override fun onBackPressed() {
        val wv = tabs.getOrNull(activeTabIndex)?.webView
        if (wv?.canGoBack() == true) wv.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        tabs.forEach { it.webView?.destroy() }
        super.onDestroy()
    }
}