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
import rikka.shizuku.Shizuku
import org.mozilla.javascript.*
import org.mozilla.javascript.Context as RhinoContext

class HubService : Service() {
    private lateinit var daemonBridge: RjsDaemonBridge
    private lateinit var sessionState: SessionStateImpl

    companion object {
        const val TAG          = "HubService"
        const val CHANNEL_ID   = "mwv_hub"
        const val NOTIF_ID     = 1001
        const val ACTION_EXECUTE = "com.mwvscript.app.EXECUTE"
        const val ACTION_BOOT    = "com.mwvscript.app.INITIAL_BOOT"
        const val ACTION_RELOAD  = "com.mwvscript.app.RELOAD"
        const val EXTRA_SCRIPT   = "script"
        const val EXTRA_PATH     = "path"

        var instance: HubService? = null
        var rhinoScope: Scriptable? = null
        var isReady = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var rhinoContext: RhinoContext? = null

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
        // Xperia電力管理対策：onCreate直後にstartForeground
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
            ACTION_BOOT -> Log.d(TAG, "BOOT intent受信")
            ACTION_RELOAD -> Thread { reloadInit() }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        isReady  = false
        unregisterReceiver(executeReceiver)
        try { RhinoContext.exit() } catch (_: Exception) {}
        rhinoContext = null
        rhinoScope   = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =========================================================
    // Rhinoエンジン初期化
    // =========================================================

    private fun initRhino() {
        try {
            val helper = RhinoAndroidHelper(this)
            val cx     = helper.enterContext()
            cx.optimizationLevel = -1
            val scope  = ImporterTopLevel(cx)

            rhinoContext = cx
            rhinoScope   = scope

            // パッケージエイリアス
            cx.evaluateString(scope, """
                var android = Packages.android;
                var java    = Packages.java;
                var javax   = Packages.javax;
                var org     = Packages.org;
                var dalvik  = Packages.dalvik;
            """.trimIndent(), "<init>", 1, null)

            // 各サービスのブリッジ注入（起動済みなら即注入）
            injectBuiltins(cx, scope)
            OverlayService.instance?.injectOverlayBridge()
            MWVAccessibilityService.instance?.injectAccessibilityBridge()
            MWVNotificationListener.instance?.injectNotifyBridge()
            MWVTileService.instance?.injectTileBridge()
            // ★ mini‑daemon 用 SessionState
            sessionState = SessionStateImpl()
            // ★ mini‑daemon ブリッジ初期化
           daemonBridge = RjsDaemonBridge(
           context = this,
           hub = this,
           session = sessionState
           )

          // ★ MiniDaemonService を起動
          val intent = Intent(this, MiniDaemonService::class.java)
          startForegroundService(intent)

          // ★ Service インスタンスをブリッジに渡す
          MiniDaemonServiceLocator.onAvailable { svc ->
          daemonBridge.attachService(svc)
          }

         // ★ JS に DaemonController / ShellExecutor / SessionState を登録
         val api = daemonBridge.exportToJs()
         for ((name, obj) in api) {
         ScriptableObject.putProperty(scope, name, Context.javaToJS(obj, scope))
         }

            isReady = true
            updateNotification("MWV Script 実行中")
            Log.d(TAG, "Rhinoエンジン初期化完了")

            runInitScript()

        } catch (e: Exception) {
            Log.e(TAG, "Rhino初期化失敗: ${e.message}")
        }
    }

    fun reloadInit() {
        Log.d(TAG, "reloadInit: 同期+再実行")
        val srcDir = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "MWV-Script"
        )
        if (srcDir.exists()) {
            try { syncDirectory(srcDir, getExternalFilesDir(null)!!) }
            catch (e: Exception) { Log.e(TAG, "sync失敗: ${e.message}") }
        }
        runInitScript()
    }

    private fun runInitScript() {
        val destFile = java.io.File(getExternalFilesDir(null), "init.rjs")
        val srcFile  = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS),
            "MWV-Script/init.rjs"
        )

        // Download/MWV-Script/ 以下を files/ に丸ごと同期
        val mwvScriptDir = srcFile.parentFile
        if (mwvScriptDir != null && mwvScriptDir.exists()) {
            try {
                syncDirectory(mwvScriptDir, getExternalFilesDir(null)!!)
                Log.d(TAG, "MWV-Script同期完了")
            } catch (e: Exception) {
                Log.e(TAG, "MWV-Script同期失敗: ${e.message}")
            }
        }

        if (!destFile.exists()) {
            Log.d(TAG, "init.rjs が見つかりません: ${destFile.absolutePath}")
            return
        }
        try {
            evaluateOnRhinoThread(destFile.readText(), "init.rjs")
            Log.d(TAG, "init.rjs 実行完了")
        } catch (e: Exception) {
            Log.e(TAG, "init.rjs エラー: ${e.message}")
        }
    }

    // =========================================================
    // ディレクトリ同期
    // =========================================================

    private fun syncDirectory(src: java.io.File, dest: java.io.File) {
        dest.mkdirs()
        src.listFiles()?.forEach { srcFile ->
            val destFile = java.io.File(dest, srcFile.name)
            if (srcFile.isDirectory) {
                syncDirectory(srcFile, destFile)
            } else {
                // 存在しないか、サイズが違う場合のみコピー
                if (!destFile.exists() || destFile.length() != srcFile.length()) {
                    srcFile.copyTo(destFile, overwrite = true)
                    Log.d(TAG, "コピー: ${srcFile.name}")
                }
            }
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
            OverlayService.instance?.appendOutput("[$sourceName] エラー: ${e.message}")
        }
    }

    // =========================================================
    // 標準ブリッジ注入
    // =========================================================

    private fun injectBuiltins(cx: RhinoContext, scope: Scriptable) {

        // ctx = HubService（Packages.android.content.ContextとしてJSから使用可能）
        ScriptableObject.putProperty(scope, "ctx", this)

        // actCtx = MainActivityのContext（AlertDialog等に使用）
        MainActivity.instance?.let {
            ScriptableObject.putProperty(scope, "actCtx", it)
        }

        // pkg namespace
        val pkg = cx.newObject(scope) as ScriptableObject
        ScriptableObject.putProperty(scope, "pkg", pkg)

        // ---- print ----
        ScriptableObject.putProperty(scope, "print", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val msg = args.joinToString(" ") { RhinoContext.toString(it) }
                Log.d(TAG, msg)
                mainHandler.post {
                    MainActivity.instance?.appendOutput(msg)
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
                        .setPositiveButton("OK")     { _, _ -> input = et.text.toString(); latch.countDown() }
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
                val latch  = java.util.concurrent.CountDownLatch(1)
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
                        try     { fn.call(cx2, scope, scope, emptyArray()) }
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
                            try     { fn.call(cx2, scope, scope, emptyArray()) }
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
                    try     { fn.call(cx2, scope, scope, emptyArray()) }
                    catch (e: Exception) { Log.e(TAG, "bThread: ${e.message}") }
                    finally { RhinoContext.exit() }
                }.start()
                return RhinoContext.getUndefinedValue()
            }
        })

        // ---- bTask (doInBackground → onPostExecute) ----
        ScriptableObject.putProperty(scope, "bTask", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val bg   = args.getOrNull(0) as? org.mozilla.javascript.Function ?: return RhinoContext.getUndefinedValue()
                val post = args.getOrNull(1) as? org.mozilla.javascript.Function
                Thread {
                    val cx2 = RhinoContext.enter()
                    cx2.optimizationLevel = -1
                    try     { bg.call(cx2, scope, scope, emptyArray()) }
                    catch (e: Exception) { Log.e(TAG, "bTask bg: ${e.message}") }
                    finally { RhinoContext.exit() }
                    if (post != null) {
                        mainHandler.post {
                            val cx3 = RhinoContext.enter()
                            cx3.optimizationLevel = -1
                            try     { post.call(cx3, scope, scope, emptyArray()) }
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
                mainHandler.post {
                    try     { fn.call(cx, scope, scope, emptyArray()) }
                    catch (e: Exception) { Log.e(TAG, "runOnUIThread: ${e.message}") }
                }
                return RhinoContext.getUndefinedValue()
            }
        })

        // ---- load(path) ----
        // MWV-Script/ 直下を基準ディレクトリとする
        val baseDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        ).let { java.io.File(it, "MWV-Script") }

        ScriptableObject.putProperty(scope, "load", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val path = RhinoContext.toString(args.getOrNull(0))
                val file = if (java.io.File(path).isAbsolute) java.io.File(path)
                           else java.io.File(baseDir, path)
                if (!file.exists()) {
                    Log.e(TAG, "load: ファイルが見つかりません: ${file.absolutePath}")
                    OverlayService.instance?.appendOutput("load: 見つかりません: ${file.absolutePath}")
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
                    OverlayService.instance?.appendOutput("load エラー [${file.name}]: ${e.message}")
                    RhinoContext.getUndefinedValue()
                }
            }
        })

        // ---- crypto オブジェクト（ProtectStar Extended AES 512bit/24rounds） ----
        val cxCrypto = RhinoContext.enter()
        cxCrypto.optimizationLevel = -1
        val crypto = cxCrypto.newObject(scope) as ScriptableObject
        RhinoContext.exit()

        // crypto.encrypt(password, plaintext, keySize?) → Base64文字列
        ScriptableObject.putProperty(crypto, "encrypt", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val password  = RhinoContext.toString(args.getOrNull(0) ?: "")
                val plaintext = RhinoContext.toString(args.getOrNull(1) ?: "")
                val keySize   = (args.getOrNull(2) as? Number)?.toInt()?.let {
                    when { it <= 16 -> 16; it <= 32 -> 32; else -> 64 }
                } ?: 32
                return try { CryptoService.encrypt(password, plaintext, keySize) }
                catch (e: Exception) { Log.e(TAG, "crypto.encrypt: ${e.message}"); "" }
            }
        })

        // crypto.decrypt(password, base64, keySize?) → plaintext
        ScriptableObject.putProperty(crypto, "decrypt", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val password = RhinoContext.toString(args.getOrNull(0) ?: "")
                val base64   = RhinoContext.toString(args.getOrNull(1) ?: "")
                val keySize  = (args.getOrNull(2) as? Number)?.toInt()?.let {
                    when { it <= 16 -> 16; it <= 32 -> 32; else -> 64 }
                } ?: 32
                return try { CryptoService.decrypt(password, base64, keySize) }
                catch (e: Exception) { Log.e(TAG, "crypto.decrypt: ${e.message}"); "" }
            }
        })

        ScriptableObject.putProperty(scope, "crypto", crypto)

        // ---- shred オブジェクト（ASDA 4パス安全消去） ----
        val cxShred = RhinoContext.enter()
        cxShred.optimizationLevel = -1
        val shred = cxShred.newObject(scope) as ScriptableObject
        RhinoContext.exit()

        // shred.file(path) → boolean（同期）
        ScriptableObject.putProperty(shred, "file", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val path = RhinoContext.toString(args.getOrNull(0) ?: "")
                return try { CryptoService.shredFile(path) }
                catch (e: Exception) { Log.e(TAG, "shred.file: ${e.message}"); false }
            }
        })

        // shred.fileAsync(path, callback?) → 非同期
        ScriptableObject.putProperty(shred, "fileAsync", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val path     = RhinoContext.toString(args.getOrNull(0) ?: "")
                val callback = args.getOrNull(1) as? org.mozilla.javascript.Function
                Thread {
                    val result = try { CryptoService.shredFile(path) } catch (e: Exception) { false }
                    if (callback != null) {
                        val cx2 = RhinoContext.enter()
                        cx2.optimizationLevel = -1
                        try     { callback.call(cx2, scope, scope, arrayOf(result)) }
                        catch (e: Exception) { Log.e(TAG, "shred.fileAsync cb: ${e.message}") }
                        finally { RhinoContext.exit() }
                    }
                }.start()
                return RhinoContext.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(scope, "shred", shred)


        // ---- shizuku オブジェクト ----
        // Shizuku UserService経由でADB権限(uid=2000)コマンドを実行するブリッジ
        // 使い方:
        //   shizuku.exec("cmd", function(out){ print(out); })
        //   shizuku.isRunning()        → Boolean
        //   shizuku.isGranted()        → Boolean
        //   shizuku.requestPermission()
        val cxShizuku = RhinoContext.enter()
        cxShizuku.optimizationLevel = -1
        val shizukuObj = cxShizuku.newObject(scope) as ScriptableObject
        RhinoContext.exit()

        // UserService接続管理
        var shizukuUserService: IShizukuUserService? = null
        val shizukuConn = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, binder: IBinder?) {
                shizukuUserService = IShizukuUserService.Stub.asInterface(binder)
                Log.d(TAG, "ShizukuUserService 接続完了")
            }
            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                shizukuUserService = null
                Log.d(TAG, "ShizukuUserService 切断")
            }
        }

        // UserServiceの引数定義
        val userServiceArgs = Shizuku.UserServiceArgs(
            android.content.ComponentName(this, ShizukuUserService::class.java)
        ).daemon(false).processNameSuffix("shizuku_service").debuggable(false).version(1)

        // shizuku.isRunning()
        ScriptableObject.putProperty(shizukuObj, "isRunning", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                return try { Shizuku.pingBinder() } catch (e: Exception) { false }
            }
        })

        // shizuku.isGranted()
        ScriptableObject.putProperty(shizukuObj, "isGranted", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                return try {
                    Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
                } catch (e: Exception) { false }
            }
        })

        // shizuku.requestPermission()
        ScriptableObject.putProperty(shizukuObj, "requestPermission", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                try { Shizuku.requestPermission(1001) }
                catch (e: Exception) { Log.e(TAG, "shizuku.requestPermission: ${e.message}") }
                return RhinoContext.getUndefinedValue()
            }
        })

        // shizuku.bindService() — UserServiceに接続
        ScriptableObject.putProperty(shizukuObj, "bindService", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                try { Shizuku.bindUserService(userServiceArgs, shizukuConn) }
                catch (e: Exception) { Log.e(TAG, "shizuku.bindService: ${e.message}") }
                return RhinoContext.getUndefinedValue()
            }
        })

        // shizuku.exec(cmd, callback?) — ADB権限でコマンドを実行
        ScriptableObject.putProperty(shizukuObj, "exec", object : BaseFunction() {
            override fun call(cx: RhinoContext, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val cmd      = RhinoContext.toString(args.getOrNull(0) ?: "")
                val callback = args.getOrNull(1) as? org.mozilla.javascript.Function
                Thread {
                    var output = ""
                    try {
                        if (!Shizuku.pingBinder()) {
                            output = "ERROR: Shizuku is not running"
                        } else if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            output = "ERROR: Shizuku permission not granted"
                        } else {
                            // UserService未接続なら接続してから実行
                            if (shizukuUserService == null) {
                                Shizuku.bindUserService(userServiceArgs, shizukuConn)
                                // 接続待ち（最大5秒）
                                var waited = 0
                                while (shizukuUserService == null && waited < 5000) {
                                    Thread.sleep(200)
                                    waited += 200
                                }
                            }
                            val svc = shizukuUserService
                            output = if (svc != null) {
                                svc.exec(cmd)
                            } else {
                                "ERROR: ShizukuUserService not connected"
                            }
                        }
                    } catch (e: Exception) {
                        output = "ERROR: ${e.message}"
                        Log.e(TAG, "shizuku.exec: ${e.message}")
                    }
                    if (callback != null) {
                        val result = output
                        val cx2 = RhinoContext.enter()
                        cx2.optimizationLevel = -1
                        try     { callback.call(cx2, scope, scope, arrayOf(result)) }
                        catch (e: Exception) { Log.e(TAG, "shizuku.exec callback: ${e.message}") }
                        finally { RhinoContext.exit() }
                    }
                }.start()
                return RhinoContext.getUndefinedValue()
            }
        })

        ScriptableObject.putProperty(scope, "shizuku", shizukuObj)

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
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
