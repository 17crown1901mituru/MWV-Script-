package com.mwvscript.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import com.faendir.rhino_android.RhinoAndroidHelper
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ImporterTopLevel
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
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

            cx.evaluateString(scope, """
                var android = Packages.android;
                var java    = Packages.java;
                var javax   = Packages.javax;
                var org     = Packages.org;
                var dalvik  = Packages.dalvik;
            """.trimIndent(), "<init>", 1, null)

            rhinoContext = cx
            rhinoScope = scope

            injectActivityDependencies()
            android.util.Log.d("MWVScript", "Rhinoエンジン初期化完了")
        } catch (e: Exception) {
            android.util.Log.e("MWVScript", "Rhino初期化失敗: ${e.message}")
        }
    }

    fun injectActivityDependencies() {
        val cx = rhinoContext ?: return
        val scope = rhinoScope ?: return
        val activity = activityRef ?: return
        val mainHandler = Handler(Looper.getMainLooper())

        ScriptableObject.putProperty(scope, "ctx", activity)

        val pkg = cx.newObject(scope) as ScriptableObject
        ScriptableObject.putProperty(pkg, "Utils", MWVUtils(activity))
        ScriptableObject.putProperty(scope, "pkg", pkg)

        // print
        ScriptableObject.putProperty(scope, "print", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                android.util.Log.d("MWVScript", args.joinToString(" ") { RhinoContext.toString(it) })
                return RhinoContext.getUndefinedValue()
            }
        })

        // popup
        ScriptableObject.putProperty(scope, "popup", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val msg = RhinoContext.toString(args.getOrNull(0))
                mainHandler.post {
                    android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
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
                    android.app.AlertDialog.Builder(activity)
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
                    android.app.AlertDialog.Builder(activity)
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

        // select
        ScriptableObject.putProperty(scope, "select", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val items = (args.getOrNull(0) as? NativeArray)
                    ?.map { RhinoContext.toString(it) }?.toTypedArray() ?: return -1
                var selected = -1
                val latch = java.util.concurrent.CountDownLatch(1)
                mainHandler.post {
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

        // bTask
        ScriptableObject.putProperty(scope, "bTask", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val bgFn = args.getOrNull(0) as? org.mozilla.javascript.Function ?: return RhinoContext.getUndefinedValue()
                val afterFn = args.getOrNull(1) as? org.mozilla.javascript.Function
                Thread {
                    try { bgFn.call(cx, scope, scope, emptyArray()) }
                    catch (e: Exception) { android.util.Log.e("MWVScript", "bTask bg: ${e.message}") }
                    afterFn?.let { fn ->
                        mainHandler.post {
                            try { fn.call(cx, scope, scope, emptyArray()) }
                            catch (e: Exception) { android.util.Log.e("MWVScript", "bTask after: ${e.message}") }
                        }
                    }
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

        // jsArray
        ScriptableObject.putProperty(scope, "jsArray", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val arr = args.getOrNull(0)
                return if (arr is Array<*>) cx.newArray(scope, arr) else cx.newArray(scope, emptyArray())
            }
        })
    }

    fun evaluateRhino(script: String): String {
        val cx = rhinoContext ?: return "Error: Rhinoエンジン未起動"
        val scope = rhinoScope ?: return "Error: Rhinoスコープ未初期化"
        return try {
            val result = cx.evaluateString(scope, script, "<mwv>", 1, null)
            RhinoContext.toString(result)
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun callScriptFunction(name: String, vararg args: Any?) {
        val cx = rhinoContext ?: return
        val scope = rhinoScope ?: return
        try {
            val fn = scope.get(name, scope)
            if (fn is org.mozilla.javascript.Function) fn.call(cx, scope, scope, args)
        } catch (e: Exception) {
            android.util.Log.e("MWVScript", "callScriptFunction($name): ${e.message}")
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
        try { rhinoContext?.let { RhinoContext.exit() } } catch (e: Exception) { }
        rhinoContext = null
        rhinoScope = null
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
