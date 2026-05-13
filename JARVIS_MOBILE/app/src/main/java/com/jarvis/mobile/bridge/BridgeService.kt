package com.jarvis.mobile.bridge

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.jarvis.mobile.agent.CommandResult
import com.jarvis.mobile.memory.MemoryEngine
import com.jarvis.mobile.service.JarvisService
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

// ── PC Bridge WebSocket Client ────────────────────────────────────────────────
object PcBridge {
    private const val TAG     = "PcBridge"
    private var client: WebSocketClient? = null
    private var pcHost = ""
    private var pcPort = 8765

    fun connect(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        val config = MemoryEngine.getConfig(ctx)
        pcHost = config.optString("pc_host", "")
        pcPort = config.optInt("pc_port", 8765)
        if (pcHost.isBlank()) {
            onSpeak("PC host not configured. Please set it in settings.")
            return CommandResult.failure("no pc host")
        }
        return connectTo(pcHost, pcPort, onSpeak)
    }

    fun connectTo(host: String, port: Int, onSpeak: (String) -> Unit): CommandResult {
        return try {
            pcHost = host
            pcPort = port
            val uri = URI("ws://$host:$port")
            client = object : WebSocketClient(uri) {
                override fun onOpen(hs: ServerHandshake?) {
                    Log.i(TAG, "Connected to PC JARVIS")
                    onSpeak("Connected to PC.")
                    send(JSONObject().apply {
                        put("type", "mobile_hello")
                        put("device", android.os.Build.MODEL)
                    }.toString())
                }
                override fun onMessage(msg: String?) {
                    msg ?: return
                    try {
                        val data = JSONObject(msg)
                        val type = data.optString("type")
                        when (type) {
                            "speak"       -> JarvisService.speak(data.optString("text"))
                            "notification"-> Log.i(TAG, "PC notify: ${data.optString("body")}")
                            "tool_result" -> Log.i(TAG, "PC tool: ${data.optString("result")}")
                        }
                    } catch (e: Exception) { Log.e(TAG, "onMessage: ${e.message}") }
                }
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.i(TAG, "PC disconnected: $reason")
                }
                override fun onError(ex: Exception?) {
                    Log.e(TAG, "Bridge error: ${ex?.message}")
                }
            }
            client!!.connect()
            CommandResult.success("connecting to $host:$port")
        } catch (e: Exception) {
            Log.e(TAG, "connect: ${e.message}")
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun sendCommand(ctx: Context, command: String, onSpeak: (String) -> Unit): CommandResult {
        return try {
            if (client?.isOpen != true) {
                connect(ctx, onSpeak)
                Thread.sleep(1500)
            }
            client?.send(JSONObject().apply {
                put("type", "command")
                put("text", command)
            }.toString())
            onSpeak("Sent to PC: $command")
            CommandResult.success("sent")
        } catch (e: Exception) {
            onSpeak("PC not connected.")
            CommandResult.failure(e.message ?: "error")
        }
    }

    fun sendClipboard(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        val cm   = ctx.getSystemService(android.content.ClipboardManager::class.java)
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        return sendCommand(ctx, "set clipboard $text", onSpeak)
    }

    fun getStatus(ctx: Context, onSpeak: (String) -> Unit): CommandResult {
        return sendCommand(ctx, "get system stats", onSpeak)
    }

    fun isConnected() = client?.isOpen == true

    fun disconnect() {
        try { client?.close() } catch (e: Exception) {}
    }
}

// ── Bridge Background Service ──────────────────────────────────────────────────
class BridgeService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            // Auto-reconnect loop
            while (true) {
                if (!PcBridge.isConnected()) {
                    Log.d("BridgeService", "Attempting auto-reconnect...")
                }
                delay(30_000)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        PcBridge.disconnect()
    }
}
