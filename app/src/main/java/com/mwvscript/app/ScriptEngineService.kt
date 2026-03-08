package com.mwvscript.app

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import com.faendir.rhino_android.RhinoAndroidHelper
import org.mozilla.javascript.*
import org.mozilla.javascript.Context as RhinoContext

class ScriptEngineService : Service() {

    companion object {
        var isRunning = false
        const val CHANNEL_ID = "mwv_engine"
        const val NOTIF_ID = 1001

        var rhinoContext: RhinoContext? = null
        var rhinoScope: Scriptable? = null
        var activityRef: Activity? = null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("MWV Script 実行中"))
        isRunning = true
        Thread { initRhino() }.start()
    }

    private fun initRhino() {
        try {
            val helper = RhinoAndroidHelper(this)
            val cx = helper.enterContext()
            cx.optimizationLevel = -1
            val scope = ImporterTopLevel(cx)

            // パッケージエイリアス
            cx.evaluateString(scope, """
                var android = Packages.android;
                var java    = Packages.java;
                var javax   = Packages.javax;
                var org     = Packages.org;
                var dalvik  = Packages.dalvik;
            """.trimIndent(), "<init>", 1, null)

            rhinoContext = cx
            rhinoScope = scope

            injectBuiltins()
            android.util.Log.d("MWVScript", "Rhinoエンジン初期化完了")

            // init.rjsを自動実行
            runInitScript()

        } catch (e: Exception) {
            android.util.Log.e("MWVScript", "Rhino初期化失敗: ${e.message}")
            (activityRef as? MainActivity)?.printLine("Rhinoエラー: ${e.message}")
        }
    }

    private fun runInitScript() {
        val initFile = java.io.File(getExternalFilesDir(null), "init.rjs")
        if (!initFile.exists()) {
            // init.rjsがなければデフォルトを生成
            initFile.writeText("""
// MWV Script init.rjs
// このファイルはアプリ起動時に自動実行されます
// ここに読み込みたいScriptのパスを記述してください

print("MWV Script 起動完了");
print("init.rjs: " + "${initFile.absolutePath}");
            """.trimIndent())
            (activityRef as? MainActivity)?.printLine("init.rjs を作成しました")
            (activityRef as? MainActivity)?.printLine(initFile.absolutePath)
        }

        try {
            val cx = rhinoContext ?: return
            val scope = rhinoScope ?: return
            val source = initFile.readText()
            cx.evaluateString(scope, source, "init.rjs", 1, null)
        } catch (e: Exception) {
            android.util.Log.e("MWVScript", "init.rjs エラー: ${e.message}")
            (activityRef as? MainActivity)?.printLine("init.rjsエラー: ${e.message}")
        }
    }

    private fun injectBuiltins() {
        val cx = rhinoContext ?: return
        val scope = rhinoScope ?: return
        val mainHandler = Handler(Looper.getMainLooper())
        // print → ターミナルに出力
        ScriptableObject.putProperty(scope, "print", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val msg = args.joinToString(" ") { RhinoContext.toString(it) }
                android.util.Log.d("MWVScript", msg)
                (activityRef as? MainActivity)?.printLine(msg)
                return RhinoContext.getUndefinedValue()
            }
        })

        // popup → Toast
        ScriptableObject.putProperty(scope, "popup", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val msg = RhinoContext.toString(args.getOrNull(0))
                mainHandler.post {
                    android.widget.Toast.makeText(this@ScriptEngineService, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
                return RhinoContext.getUndefinedValue()
            }
        })

        // alert
        ScriptableObject.putProperty(scope, "alert", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val msg = RhinoContext.toString(args.getOrNull(0))
                val title = if (args.size > 1) RhinoContext.toString(args[1]) else "Alert"
                val latch = java.util.concurrent.CountDownLatch(1)
                mainHandler.post {
                    android.app.AlertDialog.Builder(activityRef ?: return@post)
                        .setTitle(title).setMessage(msg)
                        .setPositiveButton("OK") { _, _ -> latch.countDown() }
                        .setOnDismissListener { latch.countDown() }
                        .show()
                }
                latch.await()
                return RhinoContext.getUndefinedValue()
            }
        })

        // prompt
        ScriptableObject.putProperty(scope, "prompt", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val msg = RhinoContext.toString(args.getOrNull(0))
                val default = if (args.size > 1) RhinoContext.toString(args[1]) else ""
                val title = if (args.size > 2) RhinoContext.toString(args[2]) else "Input"
                var input: String? = null
                val latch = java.util.concurrent.CountDownLatch(1)
                mainHandler.post {
                    val et = android.widget.EditText(activityRef ?: return@post)
                    et.setText(default)
                    android.app.AlertDialog.Builder(activityRef ?: return@post)
                        .setTitle(title).setMessage(msg).setView(et)
                        .setPositiveButton("OK") { _, _ -> input = et.text.toString(); latch.countDown() }
                        .setNegativeButton("Cancel") { _, _ -> latch.countDown() }
                        .setOnDismissListener { latch.countDown() }
                        .show()
                }
                latch.await()
                return input ?: RhinoContext.getUndefinedValue()
            }
        })

        // confirm
        ScriptableObject.putProperty(scope, "confirm", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val msg = RhinoContext.toString(args.getOrNull(0))
                val title = if (args.size > 1) RhinoContext.toString(args[1]) else "Confirm"
                var result = false
                val latch = java.util.concurrent.CountDownLatch(1)
                mainHandler.post {
                    android.app.AlertDialog.Builder(activityRef ?: return@post)
                        .setTitle(title).setMessage(msg)
                        .setPositiveButton("OK") { _, _ -> result = true; latch.countDown() }
                        .setNegativeButton("Cancel") { _, _ -> latch.countDown() }
                        .setOnDismissListener { latch.countDown() }
                        .show()
                }
                latch.await()
                return result
            }
        })

        // setTimeout
        ScriptableObject.putProperty(scope, "setTimeout", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val fn = args.getOrNull(0) as? org.mozilla.javascript.Function ?: return RhinoContext.getUndefinedValue()
                val delay = (args.getOrNull(1) as? Number)?.toLong() ?: 0L
                mainHandler.postDelayed({
                    try { fn.call(cx, scope, scope, emptyArray()) }
                    catch (e: Exception) { android.util.Log.e("MWVScript", "setTimeout: ${e.message}") }
                }, delay)
                return RhinoContext.getUndefinedValue()
            }
        })

        // setInterval
        ScriptableObject.putProperty(scope, "setInterval", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val fn = args.getOrNull(0) as? org.mozilla.javascript.Function ?: return RhinoContext.getUndefinedValue()
                val interval = (args.getOrNull(1) as? Number)?.toLong() ?: 1000L
                var stopped = false
                val runnable = object : Runnable {
                    override fun run() {
                        if (stopped) return
                        try { fn.call(cx, scope, scope, emptyArray()) }
                        catch (e: Exception) { android.util.Log.e("MWVScript", "setInterval: ${e.message}") }
                        if (!stopped) mainHandler.postDelayed(this, interval)
                    }
                }
                mainHandler.postDelayed(runnable, interval)
                val handle = cx.newObject(scope) as ScriptableObject
                ScriptableObject.putProperty(handle, "stop", object : BaseFunction() {
                    override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                        stopped = true
                        return RhinoContext.getUndefinedValue()
                    }
                })
                return handle
            }
        })

        // bThread
        ScriptableObject.putProperty(scope, "bThread", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val fn = args.getOrNull(0) as? org.mozilla.javascript.Function ?: return RhinoContext.getUndefinedValue()
                Thread {
                    try { fn.call(cx, scope, scope, emptyArray()) }
                    catch (e: Exception) { android.util.Log.e("MWVScript", "bThread: ${e.message}") }
                }.start()
                return RhinoContext.getUndefinedValue()
            }
        })

        // runOnUIThread
        ScriptableObject.putProperty(scope, "runOnUIThread", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val fn = args.getOrNull(0) as? org.mozilla.javascript.Function ?: return RhinoContext.getUndefinedValue()
                mainHandler.post {
                    try { fn.call(cx, scope, scope, emptyArray()) }
                    catch (e: Exception) { android.util.Log.e("MWVScript", "runOnUIThread: ${e.message}") }
                }
                return RhinoContext.getUndefinedValue()
            }
        })

        // openWebView(url, js?)
        ScriptableObject.putProperty(scope, "openWebView", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val url = RhinoContext.toString(args.getOrNull(0))
                val js = if (args.size > 1) RhinoContext.toString(args[1]) else ""
                mainHandler.post {
                    val intent = Intent(this@ScriptEngineService, WebViewActivity::class.java).apply {
                        putExtra("url", url)
                        putExtra("js", js)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
                return RhinoContext.getUndefinedValue()
            }
        })

        // ctx
        ScriptableObject.putProperty(scope, "ctx", this)
    }

    fun evaluateRhino(script: String): String {
        val cx = rhinoContext ?: return "Error: 未起動"
        val scope = rhinoScope ?: return "Error: 未初期化"
        return try {
            val result = cx.evaluateString(scope, script, "<eval>", 1, null)
            RhinoContext.toString(result)
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        try { rhinoContext?.let { RhinoContext.exit() } } catch (e: Exception) { }
        rhinoContext = null
        rhinoScope = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "MWV Script",
            NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MWV Script").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi).setOngoing(true).build()
    }
}
