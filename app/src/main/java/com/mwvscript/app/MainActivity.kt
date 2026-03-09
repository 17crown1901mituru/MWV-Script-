package com.mwvscript.app

import android.app.Activity
import android.content.*
import android.os.*
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    companion object {
        var instance: MainActivity? = null
    }

    private lateinit var scrollView: ScrollView
    private lateinit var terminalContainer: LinearLayout
    private lateinit var activeInput: EditText
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inputHistory = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        // ターミナル本体（出力+入力が一体化）
        terminalContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(terminalContainer)
        }

        // エクストラキーボード
        val extraKeyboard = buildExtraKeyboard()

        root.addView(scrollView)
        root.addView(extraKeyboard)
        setContentView(root)

        instance = this
        startForegroundService(Intent(this, HubService::class.java))
        startForegroundService(Intent(this, OverlayService::class.java))

        appendOutput("MWV Script Terminal v2.0")
        appendOutput("")
        addNewInput()
    }

    // 出力テキストを追加
    fun appendOutput(text: String) {
        mainHandler.post {
            val tv = TextView(this).apply {
                setTextColor(android.graphics.Color.GREEN)
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                this.text = text
            }
            terminalContainer.addView(tv)
            scrollToBottom()
        }
    }

    // 新しい入力欄を追加
    private fun addNewInput() {
        mainHandler.post {
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
            terminalContainer.addView(activeInput)
            activeInput.requestFocus()
            scrollToBottom()
        }
    }

    // RUNボタン実行
    private fun runInput() {
        val input = activeInput.text.toString().trim()
        if (input.isEmpty()) return

        inputHistory.add(input)

        // 入力欄を読み取り専用テキストに変換
        mainHandler.post {
            val inputText = activeInput.text.toString()
            terminalContainer.removeView(activeInput)
            val tv = TextView(this).apply {
                setTextColor(android.graphics.Color.CYAN)
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                text = "> $inputText"
            }
            terminalContainer.addView(tv)
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

    // printLine（ScriptEngineServiceから呼ばれる）
    fun printLine(text: String) {
        appendOutput(text)
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // エクストラキーボード構築
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
        listOf("()", "\"", "'", ";", ".", "/").forEach { symbol ->
            symbolRow.addView(extraKey(symbol) {
                val pos = activeInput.selectionStart
                activeInput.text.insert(pos, symbol)
            })
        }
        container.addView(symbolRow)

        // 操作行
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 4, 4, 4)
        }

        // RUN
        actionRow.addView(extraKey("RUN", android.graphics.Color.parseColor("#005500")) {
            runInput()
        })

        // PASTE
        actionRow.addView(extraKey("PASTE") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: return@extraKey
            val pos = activeInput.selectionStart
            activeInput.text.insert(pos, text)
        })

        // COPY
        actionRow.addView(extraKey("COPY") {
            val selected = activeInput.text.substring(
                activeInput.selectionStart.coerceAtLeast(0),
                activeInput.selectionEnd.coerceAtLeast(0)
            )
            if (selected.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("copied", selected))
                appendOutput("コピーしました")
            }
        })

        // KB（ソフトウェアキーボードトグル）
        actionRow.addView(extraKey("KB") {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            if (currentFocus != null) {
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
            }
        })

        // RESET（スコープリセット）
        actionRow.addView(extraKey("RESET", android.graphics.Color.parseColor("#553300")) {
            appendOutput("再起動が必要です。")
        })

        // EXIT（完全終了）
        actionRow.addView(extraKey("EXIT", android.graphics.Color.parseColor("#550000")) {
            stopService(Intent(this, HubService::class.java))
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

    override fun onResume() {
        super.onResume()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
