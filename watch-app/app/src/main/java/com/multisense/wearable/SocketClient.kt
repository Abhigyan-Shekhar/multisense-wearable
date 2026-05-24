package com.multisense.wearable

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SocketClient"

/**
 * Manages a persistent OkHttp WebSocket connection to the FastAPI backend.
 *
 * Behaviour:
 *  - Connects immediately on [connect].
 *  - On failure or close, waits [RECONNECT_DELAY_MS] then retries.
 *  - [send] is a no-op when the socket is not OPEN (frames are dropped,
 *    not buffered — the sensor service should handle back-pressure).
 *  - Call [disconnect] when the foreground service is stopping; this
 *    cancels the reconnect loop and performs a clean WS close.
 *
 * @param onStatusChange Callback invoked on the IO thread with one of:
 *   "connecting" | "connected" | "disconnected"
 */
class SocketClient(private val onStatusChange: (String) -> Unit) {

    companion object {
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val CONNECT_TIMEOUT_S = 5L
        private const val PING_INTERVAL_S = 20L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var ws: WebSocket? = null
    @Volatile private var serverUrl: String = ""

    private val connected = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private var reconnectJob: Job? = null

    /** Whether the WebSocket is currently open and ready to send. */
    val isConnected: Boolean get() = connected.get()

    /** Begin connecting to [url]. Automatically reconnects on failure. */
    fun connect(url: String) {
        serverUrl = url
        openSocket()
    }

    private fun openSocket() {
        if (destroyed.get()) return
        onStatusChange("connecting")
        val request = Request.Builder().url(serverUrl).build()
        ws = client.newWebSocket(request, Listener())
    }

    /** Send a JSON string. Returns true if the frame was enqueued. */
    fun send(json: String): Boolean = ws?.send(json) ?: false

    /** Cleanly shut down – stops reconnect loop and closes the socket. */
    fun disconnect() {
        destroyed.set(true)
        reconnectJob?.cancel()
        ws?.close(1000, "Service stopping")
        ws = null
        connected.set(false)
        scope.cancel()
    }

    private fun scheduleReconnect() {
        if (destroyed.get()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            Log.i(TAG, "Reconnecting to $serverUrl …")
            openSocket()
        }
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "Connected to $serverUrl")
            connected.set(true)
            onStatusChange("connected")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WS failure: ${t.message}")
            connected.set(false)
            onStatusChange("disconnected")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WS closed: $code $reason")
            connected.set(false)
            if (!destroyed.get()) {
                onStatusChange("disconnected")
                scheduleReconnect()
            }
        }
    }
}
