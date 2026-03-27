package com.mwvscript.app.daemon

interface DaemonController {
    fun start(port: Int = 5555)
    fun stop()
    fun sendToClient(data: ByteArray)
}

interface ShellExecutor {
    fun exec(cmd: String): ExecResult
}

data class ExecResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int
)

