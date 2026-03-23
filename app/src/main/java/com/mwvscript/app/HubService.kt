package com.mwvscript.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

class HubService : Service() {

    companion object {
        @JvmStatic
        var instance: HubService? = null
            private set

        // 他のサービス（Accessibility, Tile等）から参照されるグローバルスコープ
        @JvmStatic
        var rhinoScope: Scriptable? = null

        // インテント用定数
        const val ACTION_BOOT = "com.mwvscript.app.ACTION_BOOT"
        const val ACTION_EXECUTE = "com.mwvscript.app.ACTION_EXECUTE"
        const val EXTRA_SCRIPT = "extra_script"

        // スクリプトのロードと実行（MainActivity等から呼ばれる）
        @JvmStatic
        fun loadAndExecute(scriptContent: String) {
            val context = Context.enter()
            try {
                context.optimizationLevel = -1
                val scope = rhinoScope ?: return
                context.evaluateString(scope, scriptContent, "remote_script", 1, null)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                Context.exit()
            }
        }

        // init.rjs の再読み込み
        @JvmStatic
        fun reloadInit() {
            // ここに init.rjs を再度読み込むロジックを実装
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // ShizukuBridgeをバインド (Contextとしてthisを渡す)
        ShizukuBridge.bind(this)

        // Rhino エンジンの初期化
        val rhinoContext = Context.enter()
        try {
            rhinoContext.optimizationLevel = -1
            rhinoScope = rhinoContext.initStandardObjects()
            // ここに shizuku オブジェクトなどの初期登録ロジックを追加可能
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            Context.exit()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
