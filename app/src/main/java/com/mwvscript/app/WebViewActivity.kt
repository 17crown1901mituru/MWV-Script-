package com.mwvscript.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class WebViewActivity : AppCompatActivity() {

    companion object {
        // セッションIDで複数WebViewインスタンスを管理
        val sessions = mutableMapOf<String, WebViewActivity>()
        const val DEFAULT_SESSION = "default"
    }

    lateinit var webView: WebView
    var sessionId: String = DEFAULT_SESSION
    private lateinit var etConsole: EditText
    private lateinit var tvConsoleOutput: TextView
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sessionId = intent.getStringExtra("sessionId") ?: DEFAULT_SESSION
        sessions[sessionId] = this

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 3f)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val js = intent.getStringExtra("js") ?: return
                    if (js.isNotEmpty()) view.evaluateJavascript(js, null)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    printConsole("[${msg.messageLevel()}] ${msg.message()}")
                    return true
                }
            }
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        tvConsoleOutput = TextView(this).apply {
            setTextColor(android.graphics.Color.GREEN)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(8, 8, 8, 8)
        }

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(android.graphics.Color.parseColor("#0a0a0a"))
            addView(tvConsoleOutput)
        }

        etConsole = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
            hint = "js> "
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(android.graphics.Color.parseColor("#111111"))
            setPadding(16, 12, 16, 12)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        root.addView(webView)
        root.addView(scrollView)
        root.addView(etConsole)
        setContentView(root)

        etConsole.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                val js = etConsole.text.toString().trim()
                if (js.isNotEmpty()) {
                    printConsole("> $js")
                    etConsole.setText("")
                    webView.evaluateJavascript(js) { result ->
                        if (result != null && result != "null") printConsole(result)
                    }
                }
                true
            } else false
        }

        val url = intent.getStringExtra("url") ?: "about:blank"
        webView.loadUrl(url)

        HubService.rhinoScope?.let { scope ->
            org.mozilla.javascript.ScriptableObject.putProperty(scope, "webView_$sessionId", webView)
        }
    }

    fun evaluateJs(js: String, callback: ((String) -> Unit)? = null) {
        runOnUiThread {
            webView.evaluateJavascript(js) { result ->
                callback?.invoke(result ?: "")
            }
        }
    }

    fun getCurrentUrl(): String = webView.url ?: ""

    fun getCookies(): String {
        return CookieManager.getInstance().getCookie(webView.url ?: "") ?: ""
    }

    private fun printConsole(text: String) {
        runOnUiThread {
            tvConsoleOutput.append("$text\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        sessions.remove(sessionId)
        webView.destroy()
        HubService.rhinoScope?.let { scope ->
            org.mozilla.javascript.ScriptableObject.putProperty(scope, "webView_$sessionId", null)
        }
        super.onDestroy()
    }
}
