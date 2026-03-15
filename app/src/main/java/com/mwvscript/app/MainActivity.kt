package com.mwvscript.app

import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    companion object {
        var instance: MainActivity? = null
    }

    private lateinit var scrollView: ScrollView
    private lateinit var terminalContainer: LinearLayout
    private lateinit var activeInput: EditText
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        terminalContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(terminalContainer)
        }

        root.addView(scrollView)
        root.addView(buildExtraKeyboard())
        setContentView(root)

        requestPermissions()

        // 各サービス起動
        startForegroundService(Intent(this, HubService::class.java))
        startForegroundService(Intent(this, OverlayService::class.java))
        startForegroundService(Intent(this, WebViewService::class.java))

        appendOutput("MWV Script Terminal v3.0")
        appendOutput("")
        addNewInput()
    }

    override fun onResume() {
        super.onResume()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ======================================================
    // ターミナル出力
    // ======================================================

    fun appendOutput(text: String) {
        mainHandler.post {
            terminalContainer.addView(TextView(this).apply {
                setTextColor(android.graphics.Color.GREEN)
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                this.text = text
            })
            scrollToBottom()
        }
    }

    fun printLine(text: String) = appendOutput(text)

    fun setInput(text: String) {
        mainHandler.post {
            if (::activeInput.isInitialized) {
                activeInput.setText(text)
                activeInput.setSelection(text.length)
            }
        }
    }

    // ======================================================
    // 入力
    // ======================================================

    private fun addNewInput() {
        mainHandler.post {
            activeInput = EditText(this).apply {
                setTextColor(android.graphics.Color.WHITE)
                textSize  = 12f
                typeface  = android.graphics.Typeface.MONOSPACE
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setPadding(0, 4, 0, 4)
                hint = "> "
                setHintTextColor(android.graphics.Color.GRAY)
                inputType  = android.text.InputType.TYPE_CLASS_TEXT or
                             android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                             android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
                isSingleLine = false
                minLines = 1
            }
            terminalContainer.addView(activeInput)
            activeInput.requestFocus()
            scrollToBottom()
        }
    }

    private fun runInput() {
        val input = activeInput.text.toString().trim()
        if (input.isEmpty()) return

        mainHandler.post {
            val text = activeInput.text.toString()
            terminalContainer.removeView(activeInput)
            terminalContainer.addView(TextView(this).apply {
                setTextColor(android.graphics.Color.CYAN)
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                this.text = "> $text"
            })
        }

        val hub = HubService.instance
        if (hub == null || !HubService.isReady) {
            appendOutput("エラー: Rhinoエンジン未起動")
            addNewInput()
            return
        }
        if (input.endsWith(".rjs") || input.endsWith(".js")) {
            hub.loadAndExecute(input)
        } else {
            hub.executeAsync(input)
        }
        addNewInput()
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ======================================================
    // 権限取得
    // ======================================================

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
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            }
        }
    }

    // ======================================================
    // エクストラキーボード
    // ======================================================

    private fun buildExtraKeyboard(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#111111"))
        }

        // 記号行
        val symbolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 4, 4, 0)
        }
        listOf("()", "\"", "'", ";", ".", "/").forEach { sym ->
            symbolRow.addView(extraKey(sym) {
                val pos = activeInput.selectionStart
                activeInput.text.insert(pos, sym)
            })
        }
        container.addView(symbolRow)

        // 操作行
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 4, 4, 4)
        }

        actionRow.addView(extraKey("RUN", android.graphics.Color.parseColor("#005500")) { runInput() })

        actionRow.addView(extraKey("PASTE") {
            val cb   = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cb.primaryClip?.getItemAt(0)?.text?.toString() ?: return@extraKey
            activeInput.text.insert(activeInput.selectionStart, text)
        })

        actionRow.addView(extraKey("COPY") {
            val sel = activeInput.text.substring(
                activeInput.selectionStart.coerceAtLeast(0),
                activeInput.selectionEnd.coerceAtLeast(0)
            )
            if (sel.isNotEmpty()) {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("copied", sel))
                appendOutput("コピーしました")
            }
        })

        actionRow.addView(extraKey("KB") {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            if (currentFocus != null) imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
        })

        actionRow.addView(extraKey("DRAWER", android.graphics.Color.parseColor("#003355")) {
            HubService.instance?.executeAsync("if(typeof drawer!=='undefined') drawer.toggle();")
        })

        actionRow.addView(extraKey("RESET", android.graphics.Color.parseColor("#553300")) {
            appendOutput("再起動が必要です。")
        })

        actionRow.addView(extraKey("EXIT", android.graphics.Color.parseColor("#550000")) {
            stopService(Intent(this, HubService::class.java))
            stopService(Intent(this, WebViewService::class.java))
            finishAffinity()
            exitProcess(0)
        })

        container.addView(actionRow)
        return container
    }

    private fun extraKey(
        label: String,
        bgColor: Int = android.graphics.Color.parseColor("#222222"),
        onClick: () -> Unit
    ) = Button(this).apply {
        text = label
        textSize = 11f
        setTextColor(android.graphics.Color.WHITE)
        setBackgroundColor(bgColor)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(2, 2, 2, 2) }
        setPadding(4, 8, 4, 8)
        setOnClickListener { onClick() }
    }
}
