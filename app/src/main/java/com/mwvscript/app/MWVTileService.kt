package com.mwvscript.app

import android.content.SharedPreferences
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

class MWVTileService : TileService() {

    companion object {
        var instance: MWVTileService? = null
        const val PREFS_NAME  = "mwv_tile"
        const val KEY_SCRIPT  = "tile_script"
        const val KEY_LABEL   = "tile_label"
        const val KEY_RUNNING = "tile_running"

        // タイルのON/OFF状態
        var isRunning = false

        // 実行中スクリプトのハンドル（setIntervalなど停止用）
        var stopHandle: org.mozilla.javascript.Function? = null
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences(PREFS_NAME, 0)
        HubService.rhinoScope?.let { injectTileBridge() }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    // タイルをタップしたとき
    override fun onClick() {
        super.onClick()

        // ステータスバーを閉じる
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val sbm = getSystemService(android.app.StatusBarManager::class.java)
                sbm?.collapsePanels()
            } else {
                @Suppress("DEPRECATION")
                val intent = android.content.Intent("android.intent.action.CLOSE_SYSTEM_DIALOGS")
                sendBroadcast(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("MWVTile", "collapse: ${e.message}")
        }

        // ドロワー連動：isRunningをdrawer.isOpen()と同期
        val drawerOpen = try {
            val scope = HubService.rhinoScope
            val drawerObj = scope?.get("drawer", scope)
            if (drawerObj is org.mozilla.javascript.Scriptable) {
                val isOpenFn = drawerObj.get("isOpen", drawerObj)
                if (isOpenFn is org.mozilla.javascript.Function) {
                    val cx2 = org.mozilla.javascript.Context.enter()
                    cx2.optimizationLevel = -1
                    val result = isOpenFn.call(cx2, scope, drawerObj, emptyArray())
                    org.mozilla.javascript.Context.exit()
                    result == true || result?.toString() == "true"
                } else false
            } else false
        } catch (e: Exception) { false }

        isRunning = drawerOpen

        if (isRunning) {
            stopScript()
        } else {
            runScript()
            isRunning = true
        }
        updateTile()
    }

    private fun runScript() {
        val script = prefs.getString(KEY_SCRIPT, null) ?: return
        isRunning = true
        HubService.instance?.executeAsync(script)
    }

    private fun stopScript() {
        isRunning = false
        stopHandle?.let { fn ->
            val scope = HubService.rhinoScope ?: return
            try {
                val cx = org.mozilla.javascript.Context.enter()
                cx.optimizationLevel = -1
                fn.call(cx, scope, scope, emptyArray())
                org.mozilla.javascript.Context.exit()
            } catch (e: Exception) {
                android.util.Log.e("MWVTile", "stop: ${e.message}")
            }
        }
        stopHandle = null
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val label = prefs.getString(KEY_LABEL, "MWV Script") ?: "MWV Script"
        tile.label = label
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    // ======================================================
    // Rhinoブリッジ注入
    // ======================================================
    fun injectTileBridge() {
        val scope = HubService.rhinoScope ?: return
        val service = this

        val cx = org.mozilla.javascript.Context.enter()
        cx.optimizationLevel = -1
        val tile = cx.newObject(scope) as NativeObject
        org.mozilla.javascript.Context.exit()

        // tile.set(script, label?) → タイルにスクリプトを登録
        ScriptableObject.putProperty(tile, "set", object : BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                val script = org.mozilla.javascript.Context.toString(args.getOrNull(0) ?: "")
                val label  = if (args.size > 1) org.mozilla.javascript.Context.toString(args[1]) else "MWV Script"
                service.prefs.edit()
                    .putString(KEY_SCRIPT, script)
                    .putString(KEY_LABEL, label)
                    .commit()
                service.updateTile()
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // tile.setStop(fn) → タイルOFF時に呼ぶ停止関数を登録
        ScriptableObject.putProperty(tile, "setStop", object : BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                stopHandle = args.getOrNull(0) as? org.mozilla.javascript.Function
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // tile.start() → rjsから手動でON
        ScriptableObject.putProperty(tile, "start", object : BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                service.runScript()
                service.updateTile()
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // tile.stop() → rjsから手動でOFF
        ScriptableObject.putProperty(tile, "stop", object : BaseFunction() {
            override fun call(cx: org.mozilla.javascript.Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? {
                service.stopScript()
                service.updateTile()
                return org.mozilla.javascript.Context.getUndefinedValue()
            }
        })

        // tile.isRunning → 実行状態確認
        ScriptableObject.putProperty(tile, "isRunning", isRunning)

        ScriptableObject.putProperty(scope, "tile", tile)
        android.util.Log.d("MWVTile", "tileブリッジ注入完了")
    }
}
