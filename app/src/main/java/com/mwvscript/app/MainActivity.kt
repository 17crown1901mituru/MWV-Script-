package com.mwvscript.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvOutput: TextView
    private lateinit var etInput: EditText
    private lateinit var scrollView: ScrollView
    val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        tvOutput = TextView(this).apply {
            setTextColor(android.graphics.Color.GREEN)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(16, 16, 16, 16)
        }

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(tvOutput)
        }

        etInput = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
            hint = "> "
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(android.graphics.Color.parseColor("#111111"))
            setPadding(16, 12, 16, 12)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        root.addView(scrollView)
        root.addView(etInput)
        setContentView(root)

        ScriptEngineService.activityRef = this
        startForegroundService(Intent(this, ScriptEngineService::class.java))

        etInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                val input = etInput.text.toString().trim()
                if (input.isNotEmpty()) {
                    printLine("> $input")
                    etInput.setText("")
                    handleInput(input)
                }
                true
            } else false
        }

        printLine("MWV Script Terminal v1.0")
        printLine("")
    }

    fun printLine(text: String) {
        mainHandler.post {
            tvOutput.append("$text\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun handleInput(input: String) {
        Thread {
            val cx = ScriptEngineService.rhinoContext
            val scope = ScriptEngineService.rhinoScope
            if (cx == null || scope == null) {
                printLine("エラー: Rhinoエンジン未起動")
                return@Thread
            }
            try {
                val result = cx.evaluateString(scope, input, "<terminal>", 1, null)
                val str = org.mozilla.javascript.Context.toString(result)
                if (str != "undefined") printLine(str)
            } catch (e: Exception) {
                printLine("エラー: ${e.message}")
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        ScriptEngineService.activityRef = this
    }

    override fun onDestroy() {
        super.onDestroy()
        ScriptEngineService.activityRef = null
    }
}
