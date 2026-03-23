package com.mwvscript.app

import android.app.*
import android.content.Intent
import android.os.*
import rikka.shizuku.Shizuku
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

class HubService : Service() {

    companion object {
        var instance: HubService? = null
        var isReady = false
    }

    private lateinit var globalScope: ScriptableObject

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // 【1箇所目の追加】サービス起動時にShizukuへの接続を開始する
        ShizukuBridge.bind()
        
        initRhino()
        isReady = true
    }

    private fun initRhino() {
        val ctx = Context.enter()
        ctx.optimizationLevel = -1
        try {
            // JavaScriptの実行環境（Scope）を作成
            globalScope = ctx.initStandardObjects()

            // 【2箇所目の追加】JS側で "shizuku" という名前で呼べるように登録する
            // これにより、JSから shizuku.runShell() が使えるようになります。
            ScriptableObject.putProperty(
                globalScope, 
                "shizuku", 
                Context.javaToJS(ShizukuBridge, globalScope)
            )

            // その他の初期化（パスの登録など）
            // loadInitScript() 
        } finally {
            Context.exit()
        }
    }

    // JSを実行するメソッド
    fun executeAsync(code: String) {
        Thread {
            val ctx = Context.enter()
            try {
                ctx.evaluateString(globalScope, code, "remote", 1, null)
            } catch (e: Exception) {
                // エラー処理
            } finally {
                Context.exit()
            }
        }.start()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
