package com.mwvscript.app

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.faendir.rhino_android.RhinoAndroidHelper
import org.mozilla.javascript.*
import org.mozilla.javascript.Context as RhinoContext

class HubService : Service() {

    companion object {
        const val TAG = "HubService"
        const val CHANNEL_ID = "mwv_hub"
        const val NOTIF_ID = 1001
        const val ACTION_EXECUTE = "com.mwvscript.app.EXECUTE"
        const val ACTION_BOOT = "com.mwvscript.app.INITIAL_BOOT"
        const val EXTRA_SCRIPT = "script"
        const val EXTRA_PATH = "path"

        var instance: HubService? = null
        var rhinoScope: Scriptable? = null
        var isReady = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var rhinoContext: RhinoContext? = null

    // EXECUTE Intentを受け取るレシーバー
    private val executeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val script = intent.getStringExtra(EXTRA_SCRIPT) ?: return
            executeAsync(script)
        }
    }

    // =========================================================
    // ライフサイクル
    // =========================================================

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("MWV Script 起動中..."))
        registerReceiver(executeReceiver, IntentFilter(ACTION_EXECUTE), RECEIVER_NOT_EXPORTED)
        Thread { initRhino() }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXECUTE -> {
                val script = intent.getStringExtra(EXTRA_SCRIPT)
                val path   = intent.getStringExtra(EXTRA_PATH)
                when {
                    script != null -> executeAsync(script)
                    path   != null -> loadAndExecute(path)
                }
            }
            ACTION_BOOT -> {
                Log.d(TAG, "BOOT intent受信")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        isReady = false
        unregisterReceiver(executeReceiver)
        try { RhinoContext.exit() } catch (_: Exception) {}
        rhinoContext = null
        rhinoScope = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =========================================================
    // Rhinoエンジン初期化
    // =========================================================

    private fun initRhino() {
        try {
            val helper = RhinoAndroidHelper(this)
            val cx = helper.enterContext()
            cx.optimizationLevel = -1
            val scope = ImporterTopLevel(cx)

            rhinoContext = cx
            rhinoScope = scope

            // パッケージエイリアス
            cx.evaluateString(scope, """
                var android = Packages.android;
                var java    = Packages.java;
                var javax   = Packages.javax;
                var org     = Packages.org;
                var dalvik  = Packages.dalvik;
            """.trimIndent(), "<init>", 1, null)

            // 標準ブリッジ注入
            injectBuiltins(cx, scope)

            // OverlayService・AccessibilityServiceのブリッジ注入
            OverlayService.instance?.injectOverlayBridge()
            MWVAccessibilityService.instance?.injectAccessibilityBridge()
            MWVNotificationListener.instance?.injectNotifyBridge()
            MWVTileService.instance?.injectTileBridge()

            isReady = true
            updateNotification("MWV Script 実行中")
            Log.d(TAG, "Rhinoエンジン初期化完了")

            // init.rjs 自動実行
            runInitScript()

        } catch (e: Exception) {
            Log.e(TAG, "Rhino初期化失敗: ${e.message}")
        }
    }

    private fun runInitScript() {
        val initFile = java.io.File(getExternalFilesDir(null), "init.rjs")
        if (!initFile.exists()) {
            Log.d(TAG, "init.rjs が見つかりません: ${initFile.absolutePath}")
            return
        }
        try {
            val source = initFile.readText()
            evaluateOnRhinoThread(source, "init.rjs")
            Log.d(TAG, "init.rjs 実行完了")
        } catch (e: Exception) {
            Log.e(TAG, "init.rjs エラー: ${e.message}")
        }
    }

    // =========================================================
    // スクリプト実行
    // =========================================================

    fun executeAsync(script: String) {
        Thread { evaluateOnRhinoThread(script, "<exec>") }.start()
    }

    fun loadAndExecute(path: String) {
        Thread {
            val file = if (java.io.File(path).isAbsolute) java.io.File(path)
                       else java.io.File(getExternalFilesDir(null), path)
            if (!file.exists()) {
                Log.e(TAG, "ファイルが見つかりません: ${file.absolutePath}")
                return@Thread
            }
            evaluateOnRhinoThread(file.readText(), file.name)
        }.start()
    }

    fun evaluateSync(script: String): String {
        val scope = rhinoScope ?: return "Error: 未初期化"
        return try {
            val cx = RhinoContext.enter()
            cx.optimizationLevel = -1
            val result = cx.evaluateString(scope, script, "<eval>", 1, null)
            RhinoContext.exit()
            RhinoContext.toString(result)
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun evaluateOnRhinoThread(script: String, sourceName: String) {
        val scope = rhinoScope ?: return
        try {
            val cx = RhinoContext.enter()
            cx.optimizationLevel = -1
            cx.evaluateString(scope, script, sourceName, 1, null)
            RhinoContext.exit()
        } catch (e: Exception) {
            Log.e(TAG, "[$sourceName] エラー: ${e.message}")
        }
    }

    // =========================================================
    // 標準ブリッジ注入（inc互換）
    // =========================================================

    private fun injectBuiltins(cx: RhinoContext, scope: Scriptable) {

        // ctx = HubService自身（Packages.android.content.ContextとしてJSから使用可能）
        ScriptableObject.putProperty(scope, "ctx", this)

        // pkg namespace（将来的にはutils.*をここに追加）
        val pkg = cx.newObject(scope) as ScriptableObject
        ScriptableObject.putProperty(scope, "pkg", pkg)

        // ---- print ----
        ScriptableObject.putProperty(scope, "print", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val msg = args.joinToString(" ") { RhinoContext.toString(it) }
                Log.d(TAG, msg)
                mainHandler.post {
                    MainActivity.instance?.printLine(msg)
                }
                return RhinoContext.getUndefinedValue()
            }
        })

        // ---- popup (Toast) ----
        ScriptableObject.putProperty(scope, "popup", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val msg = RhinoContext.toString(args.getOrNull(0))
                mainHandler.post {
                    android.widget.Toast.makeText(this@HubService, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
                return RhinoContext.getUndefinedValue()
            }
        })

        // ---- alert ----
        ScriptableObject.putProperty(scope, "alert", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val msg   = RhinoContext.toString(args.getOrNull(0))
                val title = if (args.size > 1) RhinoContext.toString(args[1]) else "Alert"
                val latch = java.util.concurrent.CountDownLatch(1)
                mainHandler.post {
                    val act = MainActivity.instance ?: run { latch.countDown(); return@post }
                    android.app.AlertDialog.Builder(act)
                        .setTitle(title).setMessage(msg)
                        .setPositiveButton("OK") { _, _ -> latch.countDown() }
                        .setOnDismissListener { latch.countDown() }
                        .show()
                }
                latch.await()
                return RhinoContext.getUndefinedValue()
            }
        })

        // ---- prompt ----
        ScriptableObject.putProperty(scope, "prompt", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val msg     = RhinoContext.toString(args.getOrNull(0))
                val default = if (args.size > 1) RhinoContext.toString(args[1]) else ""
                val title   = if (args.size > 2) RhinoContext.toString(args[2]) else "Input"
                var input: String? = null
                val latch = java.util.concurrent.CountDownLatch(1)
                mainHandler.post {
                    val act = MainActivity.instance ?: run { latch.countDown(); return@post }
                    val et = android.widget.EditText(act)
                    et.setText(default)
                    android.app.AlertDialog.Builder(act)
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

        // ---- confirm ----
        ScriptableObject.putProperty(scope, "confirm", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val msg   = RhinoContext.toString(args.getOrNull(0))
                val title = if (args.size > 1) RhinoContext.toString(args[1]) else "Confirm"
                var result = false
                val latch = java.util.concurrent.CountDownLatch(1)
                mainHandler.post {
                    val act = MainActivity.instance ?: run { latch.countDown(); return@post }
                    android.app.AlertDialog.Builder(act)
                        .setTitle(title).setMessage(msg)
                        .setPositiveButton("OK")     { _, _ -> result = true; latch.countDown() }
                        .setNegativeButton("Cancel") { _, _ -> latch.countDown() }
                        .setOnDismissListener { latch.countDown() }
                        .show()
                }
                latch.await()
                return result
            }
        })

        // ---- setTimeout ----
        ScriptableObject.putProperty(scope, "setTimeout", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val fn    = args.getOrNull(0) as? org.mozilla.javascript.Function ?: return RhinoContext.getUndefinedValue()
                val delay = (args.getOrNull(1) as? Number)?.toLong() ?: 0L
                mainHandler.postDelayed({
                    Thread {
                        val cx2 = RhinoContext.enter()
                        cx2.optimizationLevel = -1
                        try { fn.call(cx2, scope, scope, emptyArray()) }
                        catch (e: Exception) { Log.e(TAG, "setTimeout: ${e.message}") }
                        finally { RhinoContext.exit() }
                    }.start()
                }, delay)
                return RhinoContext.getUndefinedValue()
            }
        })

        // ---- setInterval ----
        ScriptableObject.putProperty(scope, "setInterval", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val fn       = args.getOrNull(0) as? org.mozilla.javascript.Function ?: return RhinoContext.getUndefinedValue()
                val interval = (args.getOrNull(1) as? Number)?.toLong() ?: 1000L
                var stopped  = false
                val runnable = object : Runnable {
                    override fun run() {
                        if (stopped) return
                        Thread {
                            val cx2 = RhinoContext.enter()
                            cx2.optimizationLevel = -1
                            try { fn.call(cx2, scope, scope, emptyArray()) }
                            catch (e: Exception) { Log.e(TAG, "setInterval: ${e.message}") }
                            finally { RhinoContext.exit() }
                        }.start()
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

        // ---- bThread ----
        ScriptableObject.putProperty(scope, "bThread", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val fn = args.getOrNull(0) as? org.mozilla.javascript.Function ?: return RhinoContext.getUndefinedValue()
                Thread {
                    val cx2 = RhinoContext.enter()
                    cx2.optimizationLevel = -1
                    try { fn.call(cx2, scope, scope, emptyArray()) }
                    catch (e: Exception) { Log.e(TAG, "bThread: ${e.message}") }
                    finally { RhinoContext.exit() }
                }.start()
                return RhinoContext.getUndefinedValue()
            }
        })

        // ---- bTask (AsyncTask互換: doInBackground → onPostExecute) ----
        ScriptableObject.putProperty(scope, "bTask", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val bg   = args.getOrNull(0) as? org.mozilla.javascript.Function ?: return RhinoContext.getUndefinedValue()
                val post = args.getOrNull(1) as? org.mozilla.javascript.Function
                Thread {
                    val cx2 = RhinoContext.enter()
                    cx2.optimizationLevel = -1
                    try { bg.call(cx2, scope, scope, emptyArray()) }
                    catch (e: Exception) { Log.e(TAG, "bTask bg: ${e.message}") }
                    finally { RhinoContext.exit() }
                    if (post != null) {
                        mainHandler.post {
                            val cx3 = RhinoContext.enter()
                            cx3.optimizationLevel = -1
                            try { post.call(cx3, scope, scope, emptyArray()) }
                            catch (e: Exception) { Log.e(TAG, "bTask post: ${e.message}") }
                            finally { RhinoContext.exit() }
                        }
                    }
                }.start()
                return RhinoContext.getUndefinedValue()
            }
        })

        // ---- runOnUIThread ----
        ScriptableObject.putProperty(scope, "runOnUIThread", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val fn = args.getOrNull(0) as? org.mozilla.javascript.Function ?: return RhinoContext.getUndefinedValue()
                mainHandler.post { try { fn.call(cx, scope, scope, emptyArray()) } catch (e: Exception) { Log.e(TAG, "runOnUIThread: ${e.message}") } }
                return RhinoContext.getUndefinedValue()
            }
        })

        // ---- load(path) ----
        val baseDir = getExternalFilesDir(null)
        ScriptableObject.putProperty(scope, "load", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val path = RhinoContext.toString(args.getOrNull(0))
                val file = if (java.io.File(path).isAbsolute) java.io.File(path)
                           else java.io.File(baseDir, path)
                if (!file.exists()) {
                    Log.e(TAG, "load: ファイルが見つかりません: ${file.absolutePath}")
                    return RhinoContext.getUndefinedValue()
                }
                return try {
                    val src = file.readText()
                    val cx2 = RhinoContext.enter()
                    cx2.optimizationLevel = -1
                    val result = cx2.evaluateString(scope, src, file.name, 1, null)
                    RhinoContext.exit()
                    result
                } catch (e: Exception) {
                    Log.e(TAG, "load [${file.name}]: ${e.message}")
                    RhinoContext.getUndefinedValue()
                }
            }
        })

        Log.d(TAG, "標準ブリッジ注入完了")
    }

    // =========================================================
    // 通知
    // =========================================================

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "MWV Script", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MWV Script")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
