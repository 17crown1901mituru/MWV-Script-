package com.mwvscript.app.daemon

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class MiniDaemonService : Service() {

    private var socketThread: DaemonSocketThread? = null

    override fun onCreate() {
        super.onCreate()
        MiniDaemonServiceLocator.notifyAvailable(this)
        Log.d("MiniDaemon", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ここでは何もしない
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDaemon()
        Log.d("MiniDaemon", "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun startDaemon(port: Int = 5555) {
        if (socketThread != null) return

        socketThread = DaemonSocketThread(port) { bytes ->
            // 受信データを rjs に渡す（後で RjsBridge を接続）
            onClientData(bytes)
        }
        socketThread?.start()

        Log.d("MiniDaemon", "Daemon started on port $port")
    }

    fun stopDaemon() {
        socketThread?.shutdown()
        socketThread = null
        Log.d("MiniDaemon", "Daemon stopped")
    }

    fun sendToClient(data: ByteArray) {
        socketThread?.send(data)
    }

    // rjs 側に渡すためのフック（後で RjsBridge で上書き）
    var onClientData: (ByteArray) -> Unit = {}

}