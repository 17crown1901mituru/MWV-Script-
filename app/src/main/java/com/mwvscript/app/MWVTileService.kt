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
        if (isRunning) {
            // 実行中 → 停止
            stopScript()
        } else {
            // 停止中 → 実行
            runScript()
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
