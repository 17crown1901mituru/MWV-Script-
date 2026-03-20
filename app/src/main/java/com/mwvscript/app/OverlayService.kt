package com.mwvscript.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import org.mozilla.javascript.ScriptableObject

class OverlayService : Service() {

    companion object {
        var instance: OverlayService? = null
        const val CHANNEL_ID         = "mwv_hub"
        const val NOTIF_ID           = 1002
        const val ACTION_TOGGLE      = "com.mwvscript.app.OVERLAY_TOGGLE"
        const val ACTION_NOTIF_RUN   = "com.mwvscript.app.NOTIF_RUN"
        const val EXTRA_NOTIF_SCRIPT = "notif_script"

        var lastOutput: String = "待機中"

        fun toggle(context: Context) {
            context.startForegroundService(
                Intent(context, OverlayService::class.java).apply { action = ACTION_TOGGLE }
            )
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // ======================================================
    // ライフサイクル
    // ======================================================

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        injectOverlayBridge()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE    -> { /* フローティングターミナル削除済み */ }
            ACTION_NOTIF_RUN -> {
                val script = intent.getStringExtra(EXTRA_NOTIF_SCRIPT) ?: return START_STICKY
                HubService.instance?.executeAsync(script)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ======================================================
    // 出力転送（HubServiceから呼ばれる）
    // フローティングターミナルは廃止、MainActivityに転送するのみ
    // ======================================================

    fun appendOutput(text: String) {
        MainActivity.instance?.appendOutput(text)
    }

    fun setInput(text: String) {
        MainActivity.instance?.setInput(text)
    }

    // ======================================================
    // Rhinoブリッジ注入
    // ======================================================

    internal fun injectOverlayBridge() {
        val scope   = HubService.rhinoScope ?: return
        val service = this

        // ---- overlay オブジェクト ----
        val cx0 = org.mozilla.javascript.Context.enter()
        cx0.optimizationLevel = -1
        val overlay = cx0.newObject(scope) as org.mozilla.javascript.NativeObject
        org.mozilla.javascript.Context.exit()

        // overlay.show()/hide() はフローティングターミナル削除により何もしないスタブ
        ScriptableObject.putProperty(overlay, "show", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })
        ScriptableObject.putProperty(overlay, "hide", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })
        ScriptableObject.putProperty(overlay, "print", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                service.appendOutput(args.joinToString(" ") { org.mozilla.javascript.Context.toString(it) })
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })
        ScriptableObject.putProperty(scope, "overlay", overlay)

        // ---- web オブジェクト（WebViewService経由） ----
        val cx1 = org.mozilla.javascript.Context.enter()
        cx1.optimizationLevel = -1
        val web = cx1.newObject(scope) as org.mozilla.javascript.NativeObject
        org.mozilla.javascript.Context.exit()

        // web.open(url, sessionId?, type?)
        // type: "WEBVIEW"(default) / "TERMINAL" / "LAUNCHER"
        ScriptableObject.putProperty(web, "open", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val url       = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: "about:blank")
                val sessionId = if (args.size > 1) org.mozilla.javascript.Context.toString(args[1])
                                else WebViewService.DEFAULT_SESSION
                val typeStr   = if (args.size > 2) org.mozilla.javascript.Context.toString(args[2]).uppercase()
                                else "WEBVIEW"
                val tabType   = runCatching { WebViewService.TabType.valueOf(typeStr) }
                                .getOrDefault(WebViewService.TabType.WEBVIEW)
                val wvs = WebViewService.instance
                if (wvs != null) {
                    wvs.openTab(url, sessionId, tabType)
                } else {
                    service.startForegroundService(
                        Intent(service, WebViewService::class.java).apply {
                            action = WebViewService.ACTION_OPEN
                            putExtra(WebViewService.EXTRA_URL, url)
                            putExtra(WebViewService.EXTRA_SESSION, sessionId)
                            putExtra(WebViewService.EXTRA_TYPE, typeStr)
                        }
                    )
                }
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // web.openTerminal(sessionId?, label?)
        ScriptableObject.putProperty(web, "openTerminal", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val sessionId = if (args.isNotEmpty()) org.mozilla.javascript.Context.toString(args[0])
                                else "term_${System.currentTimeMillis()}"
                val label     = if (args.size > 1) org.mozilla.javascript.Context.toString(args[1]) else "Term"
                WebViewService.instance?.openTab(sessionId = sessionId, type = WebViewService.TabType.TERMINAL, label = label)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // web.openLauncher(sessionId?, label?)
        ScriptableObject.putProperty(web, "openLauncher", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val sessionId = if (args.isNotEmpty()) org.mozilla.javascript.Context.toString(args[0])
                                else "apps_${System.currentTimeMillis()}"
                val label     = if (args.size > 1) org.mozilla.javascript.Context.toString(args[1]) else "Apps"
                WebViewService.instance?.openTab(sessionId = sessionId, type = WebViewService.TabType.LAUNCHER, label = label)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // web.eval(js, sessionId?, callback?)
        ScriptableObject.putProperty(web, "eval", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val js        = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: "")
                val sessionId = if (args.size > 1) org.mozilla.javascript.Context.toString(args[1])
                                else WebViewService.DEFAULT_SESSION
                val callback  = args.getOrNull(2) as? org.mozilla.javascript.Function
                WebViewService.instance?.evalJs(sessionId, js) { result ->
                    if (callback != null) {
                        try {
                            val cx2 = org.mozilla.javascript.Context.enter()
                            cx2.optimizationLevel = -1
                            callback.call(cx2, scope, scope, arrayOf(result))
                            org.mozilla.javascript.Context.exit()
                        } catch (e: Exception) {
                            android.util.Log.e("MWVScript", "web.eval callback: ${e.message}")
                        }
                    }
                }
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // web.evalOn(sessionId, js, callback?)
        ScriptableObject.putProperty(web, "evalOn", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val sessionId = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: WebViewService.DEFAULT_SESSION)
                val js        = org.mozilla.javascript.Context.toString(args.getOrNull(1) ?: "")
                val callback  = args.getOrNull(2) as? org.mozilla.javascript.Function
                WebViewService.instance?.evalJs(sessionId, js) { result ->
                    if (callback != null) {
                        try {
                            val cx2 = org.mozilla.javascript.Context.enter()
                            cx2.optimizationLevel = -1
                            callback.call(cx2, scope, scope, arrayOf(result))
                            org.mozilla.javascript.Context.exit()
                        } catch (e: Exception) {
                            android.util.Log.e("MWVScript", "web.evalOn callback: ${e.message}")
                        }
                    }
                }
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // web.close(sessionId?)
        ScriptableObject.putProperty(web, "close", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val sessionId = if (args.isNotEmpty()) org.mozilla.javascript.Context.toString(args[0])
                                else WebViewService.DEFAULT_SESSION
                WebViewService.instance?.closeTab(sessionId)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // web.url(sessionId?)
        ScriptableObject.putProperty(web, "url", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val sessionId = if (args.isNotEmpty()) org.mozilla.javascript.Context.toString(args[0])
                                else WebViewService.DEFAULT_SESSION
                return WebViewService.instance?.getCurrentUrl(sessionId) ?: ""
            }
        })

        // web.cookies(sessionId?)
        ScriptableObject.putProperty(web, "cookies", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val sessionId = if (args.isNotEmpty()) org.mozilla.javascript.Context.toString(args[0])
                                else WebViewService.DEFAULT_SESSION
                return WebViewService.instance?.getCookies(sessionId) ?: ""
            }
        })

        // web.sessions()
        ScriptableObject.putProperty(web, "sessions", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val ids = WebViewService.instance?.getSessionIds() ?: emptyArray()
                val cx2 = org.mozilla.javascript.Context.enter()
                cx2.optimizationLevel = -1
                val arr = cx2.newArray(scope, ids)
                org.mozilla.javascript.Context.exit()
                return arr
            }
        })

        // web.set(key, value) / web.get(key)
        ScriptableObject.putProperty(web, "set", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val key   = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: "")
                WebViewService.sharedVars[key] = args.getOrNull(1)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })
        ScriptableObject.putProperty(web, "get", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val key = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: "")
                return WebViewService.sharedVars[key] ?: org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // web.hide() → オーバーレイ全体を最小化
        ScriptableObject.putProperty(web, "hide", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                WebViewService.instance?.hideOverlay()
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // web.show() → オーバーレイを再表示
        ScriptableObject.putProperty(web, "show", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                WebViewService.instance?.showOverlay()
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // web.tabs() → タブ情報一覧（ドロワーから使う）
        ScriptableObject.putProperty(web, "tabs", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val act = MainActivity.instance ?: return org.mozilla.javascript.Context.getUndefinedValue()
                val list = act.getTabInfoList()
                val cx2 = org.mozilla.javascript.Context.enter()
                cx2.optimizationLevel = -1
                val arr = cx2.newArray(scope, list.size)
                list.forEachIndexed { i, map ->
                    val obj = cx2.newObject(scope) as org.mozilla.javascript.NativeObject
                    map.forEach { (k, v) -> org.mozilla.javascript.ScriptableObject.putProperty(obj, k, v) }
                    org.mozilla.javascript.ScriptableObject.putProperty(arr, i, obj)
                }
                org.mozilla.javascript.Context.exit()
                return arr
            }
        })

        // web.activate(sessionId) → タブをアクティブ化
        ScriptableObject.putProperty(web, "activate", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val sessionId = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: WebViewService.DEFAULT_SESSION)
                mainHandler.post { MainActivity.instance?.openTab(sessionId, MainActivity.TabType.TERMINAL, "") }
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // web.setKeepAlive(sessionId, bool) → タブのアクティブキープ設定
        ScriptableObject.putProperty(web, "setKeepAlive", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val sessionId = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: WebViewService.DEFAULT_SESSION)
                val keep      = args.getOrNull(1)?.let {
                    org.mozilla.javascript.Context.toString(it) != "false" && it != false
                } ?: true
                WebViewService.instance?.setKeepAlive(sessionId, keep)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(scope, "web", web)

        // ---- terminal オブジェクト ----
        val cx2 = org.mozilla.javascript.Context.enter()
        cx2.optimizationLevel = -1
        val terminal = cx2.newObject(scope) as org.mozilla.javascript.NativeObject
        org.mozilla.javascript.Context.exit()

        ScriptableObject.putProperty(terminal, "setInput", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val text = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: "")
                mainHandler.post { MainActivity.instance?.setInput(text) }
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })
        ScriptableObject.putProperty(terminal, "run", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                HubService.instance?.executeAsync(
                    org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: ""))
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })
        ScriptableObject.putProperty(scope, "terminal", terminal)

        // ---- reload() ---- Download/MWV-Script/を同期してinit.rjsを再実行
        ScriptableObject.putProperty(scope, "reload", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                Thread { HubService.instance?.reloadInit() }.start()
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })


        // ---- shell オブジェクト ----
        val cxShell = org.mozilla.javascript.Context.enter()
        cxShell.optimizationLevel = -1
        val shell = cxShell.newObject(scope) as org.mozilla.javascript.NativeObject
        org.mozilla.javascript.Context.exit()

        ScriptableObject.putProperty(shell, "exec", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val cmd      = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: "")
                val callback = args.getOrNull(1) as? org.mozilla.javascript.Function
                Thread {
                    try {
                        val proc   = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                        val out    = proc.inputStream.bufferedReader().readText()
                        val err    = proc.errorStream.bufferedReader().readText()
                        proc.waitFor()
                        val result = if (err.isNotEmpty()) err else out
                        if (callback != null) {
                            val cx2 = org.mozilla.javascript.Context.enter()
                            cx2.optimizationLevel = -1
                            callback.call(cx2, scope, scope, arrayOf(result))
                            org.mozilla.javascript.Context.exit()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MWVScript", "shell.exec: ${e.message}")
                    }
                }.start()
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(shell, "termux", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val cmd = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: "")
                try {
                    service.startService(Intent().apply {
                        setClassName("com.termux", "com.termux.app.RunCommandService")
                        action = "com.termux.RUN_COMMAND"
                        putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
                        putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                    })
                } catch (e: Exception) {
                    android.util.Log.e("MWVScript", "shell.termux: ${e.message}")
                }
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(shell, "tap", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val x = (args.getOrNull(0) as? Number)?.toFloat() ?: 0f
                val y = (args.getOrNull(1) as? Number)?.toFloat() ?: 0f
                MWVAccessibilityService.instance?.tap(x, y)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(shell, "swipe", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val x1       = (args.getOrNull(0) as? Number)?.toFloat() ?: 0f
                val y1       = (args.getOrNull(1) as? Number)?.toFloat() ?: 0f
                val x2       = (args.getOrNull(2) as? Number)?.toFloat() ?: 0f
                val y2       = (args.getOrNull(3) as? Number)?.toFloat() ?: 0f
                val duration = (args.getOrNull(4) as? Number)?.toLong() ?: 300L
                MWVAccessibilityService.instance?.swipe(x1, y1, x2, y2, duration)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(scope, "shell", shell)

        // ---- alarm オブジェクト ----
        val cxAlarm = org.mozilla.javascript.Context.enter()
        cxAlarm.optimizationLevel = -1
        val alarm = cxAlarm.newObject(scope) as org.mozilla.javascript.NativeObject
        org.mozilla.javascript.Context.exit()
        val alarmManager = service.getSystemService(android.app.AlarmManager::class.java)

        ScriptableObject.putProperty(alarm, "set", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val delayMs = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                val script  = org.mozilla.javascript.Context.toString(args.getOrNull(1) ?: "")
                val pi = android.app.PendingIntent.getService(
                    service, script.hashCode(),
                    Intent(service, HubService::class.java).apply {
                        action = HubService.ACTION_EXECUTE
                        putExtra(HubService.EXTRA_SCRIPT, script)
                    },
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + delayMs, pi)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(alarm, "cancel", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val script = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: "")
                val pi = android.app.PendingIntent.getService(
                    service, script.hashCode(),
                    Intent(service, HubService::class.java).apply {
                        action = HubService.ACTION_EXECUTE
                        putExtra(HubService.EXTRA_SCRIPT, script)
                    },
                    android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                if (pi != null) alarmManager.cancel(pi)
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(scope, "alarm", alarm)

        // ---- screen オブジェクト ----
        val cxScreen = org.mozilla.javascript.Context.enter()
        cxScreen.optimizationLevel = -1
        val screen = cxScreen.newObject(scope) as org.mozilla.javascript.NativeObject
        org.mozilla.javascript.Context.exit()

        ScriptableObject.putProperty(screen, "capture", object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any?>): Any? {
                val callback = args.getOrNull(0) as? org.mozilla.javascript.Function
                MediaProjectionActivity.pendingCallback = { intentData ->
                    if (intentData != null && callback != null) {
                        try {
                            val cx2 = org.mozilla.javascript.Context.enter()
                            cx2.optimizationLevel = -1
                            callback.call(cx2, scope, scope, arrayOf(intentData.toString()))
                            org.mozilla.javascript.Context.exit()
                        } catch (e: Exception) {
                            android.util.Log.e("MWVScript", "screen.capture callback: ${e.message}")
                        }
                    }
                }
                service.startActivity(Intent(service, MediaProjectionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(scope, "screen", screen)

        android.util.Log.d("MWVScript", "overlayブリッジ注入完了")
    }

    // ======================================================
    // 通知
    // ======================================================

    private fun buildNotification(): Notification {
        val mainPi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val drawerPi = PendingIntent.getService(
            this, 2,
            Intent(this, OverlayService::class.java).apply {
                action = ACTION_NOTIF_RUN
                putExtra(EXTRA_NOTIF_SCRIPT, "if(typeof drawer!=='undefined') drawer.toggle();")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MWV Script")
            .setContentText(lastOutput)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(mainPi)
            .addAction(android.R.drawable.ic_menu_manage, "ドロワー", drawerPi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }
}
