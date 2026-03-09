package com.mwvscript.app

import android.app.Activity
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
        requestPermissions()
        startForegroundService(Intent(this, HubService::class.java))
        startForegroundService(Intent(this, OverlayService::class.java))

        appendOutput("MWV Script Terminal v2.0")
        appendOutput("")

        // アカウント未登録なら登録ダイアログを表示
        if (AccountManager.getAccounts(this).isEmpty()) {
            mainHandler.postDelayed({ showAddAccountDialog() }, 500)
        }
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

    // ======================================================
    // アカウント管理
    // ======================================================

    fun showAddAccountDialog(onComplete: (() -> Unit)? = null) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        val etSession = EditText(this).apply {
            hint = "セッション名（例: account1）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val etEmail = EditText(this).apply {
            hint = "メールアドレス"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val etPassword = EditText(this).apply {
            hint = "パスワード"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        layout.addView(TextView(this).apply { text = "アカウント登録" ; textSize = 14f })
        layout.addView(etSession)
        layout.addView(etEmail)
        layout.addView(etPassword)

        android.app.AlertDialog.Builder(this)
            .setTitle("アカウント追加")
            .setView(layout)
            .setPositiveButton("追加") { _, _ ->
                val session  = etSession.text.toString().trim()
                val email    = etEmail.text.toString().trim()
                val password = etPassword.text.toString().trim()
                if (session.isNotEmpty() && email.isNotEmpty() && password.isNotEmpty()) {
                    AccountManager.addAccount(this, session, email, password)
                    appendOutput("アカウント登録完了: $session")
                    onComplete?.invoke()
                } else {
                    appendOutput("入力が不足しています")
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun requestPermissions() {
        // 通常権限（まとめてリクエスト）
        val perms = mutableListOf(
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.READ_PHONE_STATE,
        )
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 0)

        // オーバーレイ権限
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
        }

        // 全ファイルアクセス権限（Android 11以上）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            }
        }
    }

    // printLine（HubServiceから呼ばれる）
    fun printLine(text: String) {
        appendOutput(text)
    }

    // setInput（rjsのterminal.setInput()から呼ばれる）
    fun setInput(text: String) {
        mainHandler.post {
            if (::activeInput.isInitialized) {
                activeInput.setText(text)
                activeInput.setSelection(text.length)
            }
        }
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
