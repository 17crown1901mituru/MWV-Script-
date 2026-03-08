package com.mwvscript.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import com.faendir.rhino_android.RhinoAndroidHelper
import org.mozilla.javascript.*

class ScriptEngineService : Service() {

    companion object {
        var isRunning = false
        const val CHANNEL_ID = "mwv_engine"
        const val NOTIF_ID = 1001

        var rhinoContext: org.mozilla.javascript.Context? = null
        var rhinoScope: Scriptable? = null

        // MainActivityからセットする
        var activityRef: Activity? = null
    }

    private var engineWebView: WebView? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("エンジン起動中..."))
        isRunning = true
        initRhino()
        initEngineWebView()
    }

    private fun initRhino() {
        try {
            val helper = RhinoAndroidHelper(this)
            val cx = helper.enterContext()
            cx.optimizationLevel = -1
            val scope = ImporterTopLevel(cx)

            // ── パッケージエイリアス ──────────────────────────────────────
            cx.evaluateString(scope, """
                var android = Packages.android;
                var java    = Packages.java;
                var javax   = Packages.javax;
                var org     = Packages.org;
                var dalvik  = Packages.dalvik;
            """.trimIndent(), "<init>", 1, null)

            // ── ctx / pkg をセット（Activityが既にあれば即注入） ─────────
            injectActivityDependencies(cx, scope)

            rhinoContext = cx
            rhinoScope  = scope
            android.util.Log.d("MWVScript", "Rhinoエンジン初期化完了")
        } catch (e: Exception) {
            android.util.Log.e("MWVScript", "Rhino初期化失敗: ${e.message}")
        }
    }

    /** ActivityがセットされたタイミングでもRhinoに注入できるよう分離 */
    fun injectActivityDependencies(
        cx: org.mozilla.javascript.Context = rhinoContext ?: return,
        scope: Scriptable = rhinoScope ?: return
    ) {
        val activity = activityRef ?: return

        // ctx
        ScriptableObject.putProperty(scope, "ctx", activity)

        // pkg オブジェクト
        val pkg = cx.newObject(scope)
        val utils = MWVUtils(activity)
        ScriptableObject.putProperty(pkg as ScriptableObject, "Utils", utils)
        ScriptableObject.putProperty(pkg, "R", activity.resources)
        ScriptableObject.putProperty(scope, "pkg", pkg)

        // ── 組み込み関数 ─────────────────────────────────────────────────
        val mainLooper = Handler(Looper.getMainLooper())

        fun ui(block: () -> Unit) = mainLooper.post(block)

        // print
        ScriptableObject.putProperty(scope, "print",
            object : BaseFunction() {
                override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable,
                                  thisObj: Scriptable?, args: Array<out Any?>): Any {
                    android.util.Log.d("MWVScript", args.joinToString(" ") {
                        org.mozilla.javascript.Context.toString(it) })
                    return org.mozilla.javascript.Context.getUndefinedValue()
                }
            })

        // popup (Toast)
        ScriptableObject.putProperty(scope, "popup",
            object : BaseFunction() {
                override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable,
                                  thisObj: Scriptable?, args: Array<out Any?>): Any {
                    val msg = args.getOrNull(0)?.let { org.mozilla.javascript.Context.toString(it) } ?: ""
                    ui { android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show() }
                    return org.mozilla.javascript.Context.getUndefinedValue()
                }
            })

        // alert
        ScriptableObject.putProperty(scope, "alert",
            object : BaseFunction() {
                override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable,
                                  thisObj: Scriptable?, args: Array<out Any?>): Any {
                    val msg   = args.getOrNull(0)?.let { org.mozilla.javascript.Context.toString(it) } ?: ""
                    val title = args.getOrNull(1)?.let { org.mozilla.javascript.Context.toString(it) } ?: "Alert"
                    val result = java.util.concurrent.CountDownLatch(1)
                    ui {
                        android.app.AlertDialog.Builder(activity)
                            .setTitle(title).setMessage(msg)
                            .setPositiveButton("OK") { _, _ -> result.countDown() }
                            .setOnDismissListener { result.countDown() }
                            .show()
                    }
                    result.await()
                    return org.mozilla.javascript.Context.getUndefinedValue()
                }
            })

        // prompt
        ScriptableObject.putProperty(scope, "prompt",
            object : BaseFunction() {
                override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable,
                                  thisObj: Scriptable?, args: Array<out Any?>): Any {
                    val msg     = args.getOrNull(0)?.let { org.mozilla.javascript.Context.toString(it) } ?: ""
                    val default = args.getOrNull(1)?.let { org.mozilla.javascript.Context.toString(it) } ?: ""
                    val title   = args.getOrNull(2)?.let { org.mozilla.javascript.Context.toString(it) } ?: "Input"
                    var input: String? = null
                    val latch = java.util.concurrent.CountDownLatch(1)
                    ui {
                        val et = android.widget.EditText(activity)
                        et.setText(default)
                        android.app.AlertDialog.Builder(activity)
                            .setTitle(title).setMessage(msg).setView(et)
                            .setPositiveButton("OK") { _, _ -> input = et.text.toString(); latch.countDown() }
                            .setNegativeButton("Cancel") { _, _ -> latch.countDown() }
                            .setOnDismissListener { latch.countDown() }
                            .show()
                    }
                    latch.await()
                    return input ?: org.mozilla.javascript.Context.getUndefinedValue()
                }
            })

        // confirm
        ScriptableObject.putProperty(scope, "confirm",
            object : BaseFunction() {
                override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable,
                                  thisObj: Scriptable?, args: Array<out Any?>): Any {
                    val msg   = args.getOrNull(0)?.let { org.mozilla.javascript.Context.toString(it) } ?: ""
                    val title = args.getOrNull(1)?.let { org.mozilla.javascript.Context.toString(it) } ?: "Confirm"
                    var result = false
                    val latch = java.util.concurrent.CountDownLatch(1)
                    ui {
                        android.app.AlertDialog.Builder(activity)
                            .setTitle(title).setMessage(msg)
                            .setPositiveButton("OK")     { _, _ -> result = true;  latch.countDown() }
                            .setNegativeButton("Cancel") { _, _ -> result = false; latch.countDown() }
                            .setOnDismissListener { latch.countDown() }
                            .show()
                    }
                    latch.await()
                    return result
                }
            })

        // select
        ScriptableObject.putProperty(scope, "select",
            object : BaseFunction() {
                override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable,
                                  thisObj: Scriptable?, args: Array<out Any?>): Any {
                    val items = (args.getOrNull(0) as? NativeArray)
                        ?.map { org.mozilla.javascript.Context.toString(it) }?.toTypedArray() ?: return -1
                    var selected = -1
                    val latch = java.util.concurrent.CountDownLatch(1)
                    ui {
                        android.app.AlertDialog.Builder(activity)
                            .setItems(items) { _, which -> selected = which; latch.countDown() }
                            .setOnDismissListener { latch.countDown() }
                            .show()
                    }
                    latch.await()
                    return selected
                }
            })

        // setTimeout
        ScriptableObject.putProperty(scope, "setTimeout",
            object : BaseFunction() {
                override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable,
                                  thisObj: Scriptable?, args: Array<out Any?>): Any {
                    val fn    = args.getOrNull(0) as? Function ?: return org.mozilla.javascript.Context.getUndefinedValue()
                    val delay = (args.getOrNull(1) as? Number)?.toLong() ?: 0L
                    mainLooper.postDelayed({
                        try { fn.call(cx, scope, scope, emptyArray()) } catch (e: Exception) {
                            android.util.Log.e("MWVScript", "setTimeout error: ${e.message}") }
                    }, delay)
                    return org.mozilla.javascript.Context.getUndefinedValue()
                }
            })

        // setInterval
        ScriptableObject.putProperty(scope, "setInterval",
            object : BaseFunction() {
                override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable,
                                  thisObj: Scriptable?, args: Array<out Any?>): Any {
                    val fn       = args.getOrNull(0) as? Function ?: return org.mozilla.javascript.Context.getUndefinedValue()
                    val interval = (args.getOrNull(1) as? Number)?.toLong() ?: 1000L
                    var stopped  = false
                    val runnable = object : Runnable {
                        override fun run() {
                            if (stopped) return
                            try { fn.call(cx, scope, scope, emptyArray()) } catch (e: Exception) {
                                android.util.Log.e("MWVScript", "setInterval error: ${e.message}") }
                            if (!stopped) mainLooper.postDelayed(this, interval)
                        }
                    }
                    mainLooper.postDelayed(runnable, interval)
                    // stop()で止められるオブジェクトを返す
                    val handle = cx.newObject(scope) as ScriptableObject
                    ScriptableObject.putProperty(handle, "stop", object : BaseFunction() {
                        override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable,
                                          thisObj: Scriptable?, args: Array<out Any?>): Any {
                            stopped = true
                            return org.mozilla.javascript.Context.getUndefinedValue()
                        }
                    })
                    return handle
                }
            })

        // bThread
        ScriptableObject.putProperty(scope, "bThread",
            object : BaseFunction() {
                override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable,
                                  thisObj: Scriptable?, args: Array<out Any?>): Any {
                    val fn = args.getOrNull(0) as? Function ?: return org.mozilla.javascript.Context.getUndefinedValue()
                    Thread {
                        try { fn.call(cx, scope, scope, emptyArray()) } catch (e: Exception) {
                            android.util.Log.e("MWVScript", "bThread error: ${e.message}") }
                    }.start()
                    return org.mozilla.javascript.Context.getUndefinedValue()
                }
            })

        // bTask
        ScriptableObject.putProperty(scope, "bTask",
            object : BaseFunction() {
                override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable,
                                  thisObj: Scriptable?, args: Array<out Any?>): Any {
                    val bgFn    = args.getOrNull(0) as? Function ?: return org.mozilla.javascript.Context.getUndefinedValue()
                    val afterFn = args.getOrNull(1) as? Function
                    Thread {
                        try { bgFn.call(cx, scope, scope, emptyArray()) } catch (e: Exception) {
                            android.util.Log.e("MWVScript", "bTask bg error: ${e.message}") }
                        afterFn?.let { fn ->
                            mainLooper.post {
                                try { fn.call(cx, scope, scope, emptyArray()) } catch (e: Exception) {
                                    android.util.Log.e("MWVScript", "bTask after error: ${e.message}") }
                            }
                        }
                    }.start()
                    return org.mozilla.javascript.Context.getUndefinedValue()
                }
            })

        // runOnUIThread
        ScriptableObject.putProperty(scope, "runOnUIThread",
            object : BaseFunction() {
                override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable,
                                  thisObj: Scriptable?, args: Array<out Any?>): Any {
                    val fn = args.getOrNull(0) as? Function ?: return org.mozilla.javascript.Context.getUndefinedValue()
                    ui { try { fn.call(cx, scope, scope, emptyArray()) } catch (e: Exception) {
                        android.util.Log.e("MWVScript", "runOnUIThread error: ${e.message}") } }
                    return org.mozilla.javascript.Context.getUndefinedValue()
                }
            })

        // jsArray (Java配列→JS配列変換)
        ScriptableObject.putProperty(scope, "jsArray",
            object : BaseFunction() {
                override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable,
                                  thisObj: Scriptable?, args: Array<out Any?>): Any {
                    val javaArr = args.getOrNull(0) ?: return cx.newArray(scope, emptyArray())
                    return when (javaArr) {
                        is Array<*> -> cx.newArray(scope, javaArr)
                        else -> cx.newArray(scope, emptyArray())
                    }
                }
            })
    }

    fun evaluateRhino(script: String): String {
        val cx    = rhinoContext ?: return "Error: Rhinoエンジン未起動"
        val scope = rhinoScope   ?: return "Error: Rhinoスコープ未初期化"
        return try {
            val result = cx.evaluateString(scope, script, "<mwv>", 1, null)
            org.mozilla.javascript.Context.toString(result)
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /** スクリプト内のライフサイクル関数を呼び出す */
    fun callScriptFunction(name: String, vararg args: Any?) {
        val cx    = rhinoContext ?: return
        val scope = rhinoScope   ?: return
        try {
            val fn = scope.get(name, scope)
            if (fn is Function) fn.call(cx, scope, scope, args)
        } catch (e: Exception) {
            android.util.Log.e("MWVScript", "callScriptFunction($name) error: ${e.message}")
        }
    }

    private fun initEngineWebView() {
        engineWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(ScriptBridge(this@ScriptEngineService, "engine"), "MWVScript")
            loadDataWithBaseURL(null, "<html><body></body></html>", "text/html", "utf-8", null)
        }
    }

    fun executeScript(js: String, callback: ((String) -> Unit)? = null) {
        engineWebView?.evaluateJavascript(js) { result -> callback?.invoke(result ?: "") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateNotification("エンジン実行中")
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        engineWebView?.destroy()
        try { rhinoContext?.let { org.mozilla.javascript.Context.exit() } } catch (e: Exception) { }
        rhinoContext = null
        rhinoScope   = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "MWV Script Engine",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "バックグラウンドJSエンジン"
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

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
