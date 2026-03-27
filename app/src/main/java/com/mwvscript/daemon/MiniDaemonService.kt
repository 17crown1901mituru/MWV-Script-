package com.mwvscript.app.daemon

import android.app.Service
import android.content.Intent
import android.os.IBinder

class MiniDaemonService : Service() {

    var onClientData: ((ByteArray) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    fun startDaemon(port: Int) {
        // ソケット開始処理
    }

    fun stopDaemon() {
        // ソケット停止処理
    }

    fun sendToClient(bytes: ByteArray) {
        // クライアントへ送信
    }
}