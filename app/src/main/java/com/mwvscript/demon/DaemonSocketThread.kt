package your.package.name.daemon

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class DaemonSocketThread(
    private val port: Int,
    private val onReceive: (ByteArray) -> Unit
) : Thread("DaemonSocketThread") {

    private val running = AtomicBoolean(true)
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var output: OutputStream? = null

    override fun run() {
        try {
            serverSocket = ServerSocket(port)
            Log.d("MiniDaemon", "Listening on port $port")

            clientSocket = serverSocket!!.accept()
            Log.d("MiniDaemon", "Client connected")

            output = clientSocket!!.getOutputStream()
            val input: InputStream = clientSocket!!.getInputStream()

            val buffer = ByteArray(4096)

            while (running.get()) {
                val len = input.read(buffer)
                if (len == -1) break

                val data = buffer.copyOf(len)
                onReceive(data)
            }

        } catch (e: Exception) {
            Log.e("MiniDaemon", "Socket error: ${e.message}")
        } finally {
            shutdown()
        }
    }

    fun send(data: ByteArray) {
        try {
            output?.write(data)
            output?.flush()
        } catch (e: Exception) {
            Log.e("MiniDaemon", "Send error: ${e.message}")
        }
    }

    fun shutdown() {
        running.set(false)
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        output = null
        Log.d("MiniDaemon", "Socket closed")
    }
}
