package com.mwvscript.app.daemon

import android.content.Context
import android.content.Intent
import com.mwvscript.app.HubService

class RjsDaemonBridge(
    private val context: Context,
    private val hub: HubService
) {

    private var service: MiniDaemonService? = null

    fun attachService(svc: MiniDaemonService) {
        service = svc

        // rjs → Kotlin のコールバック
        service?.onClientData = { bytes ->
            hub.callJsFunction("onDaemonClientData", bytes)
        }
    }

    fun exportToJs(): Map<String, Any> {
        return mapOf(
            "DaemonController" to object {
                fun start(port: Int) {
                    ensureService()
                    service?.startDaemon(port)
                    hub.setSession("daemon.running", true)
                }

                fun stop() {
                    service?.stopDaemon()
                    hub.setSession("daemon.running", false)
                }

                fun sendToClient(bytes: ByteArray) {
                    service?.sendToClient(bytes)
                }
            },

            "ShellExecutor" to object {
                fun exec(cmd: String): Map<String, Any> {
                    val result = hub.shellExec(cmd)
                    return mapOf(
                        "stdout" to result.stdout,
                        "stderr" to result.stderr,
                        "exitCode" to result.exitCode
                    )
                }
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