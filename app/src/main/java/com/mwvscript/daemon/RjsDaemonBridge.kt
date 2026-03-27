package com.mwvscript.app

import android.content.Context
import android.content.Intent
import your.package.name.HubService

class RjsDaemonBridge(
    private val context: Context,
    private val hub: HubService,
    private val session: SessionState
) {

    private var service: MiniDaemonService? = null

    fun attachService(svc: MiniDaemonService) {
        service = svc

        // rjs → Kotlin のコールバック
        service?.onClientData = { bytes ->
            hub.callJsFunction("onDaemonClientData", bytes)
        }
    }

    // rjs に公開する API をまとめて返す
    fun exportToJs(): Map<String, Any> {
        return mapOf(
            "DaemonController" to object {
                fun start(port: Int) {
                    ensureService()
                    service?.startDaemon(port)
                    session.set("daemon.running", true)
                }

                fun stop() {
                    service?.stopDaemon()
                    session.set("daemon.running", false)
                }

                fun sendToClient(bytes: ByteArray) {
                    service?.sendToClient(bytes)
                }
            },

            "ShellExecutor" to object {
                fun exec(cmd: String): Map<String, Any> {
                    val result = hub.shellExec(cmd) // 既存の shell.exec を利用
                    return mapOf(
                        "stdout" to result.stdout,
                        "stderr" to result.stderr,
                        "exitCode" to result.exitCode
                    )
                }
            },

            "SessionState" to object {
                fun set(key: String, value: Any?) = session.set(key, value)
                fun get(key: String): Any? = session.get(key)
            }
        )
    }

    private fun ensureService() {
        if (service == null) {
            val intent = Intent(context, MiniDaemonService::class.java)
            context.startForegroundService(intent)
        }
    }
}