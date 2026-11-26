package com.alma.connect.network

import kotlinx.coroutines.flow.MutableStateFlow
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URI
import java.security.SecureRandom
import java.util.Collections

data class Pending(val name: String, val answer: (Boolean) -> Unit)
data class Found(val name: String, val host: String)

object Hub {
    const val PORT = 8765
    const val TYPE = "_almaconnect._tcp."
    val code = MutableStateFlow("")
    val request = MutableStateFlow<Pending?>(null)
    val connected = MutableStateFlow(false)
    val sharing = MutableStateFlow(false)
    val found = MutableStateFlow<List<Found>>(emptyList())
}

fun newCode() = (SecureRandom().nextInt(900000) + 100000).toString()

class AlmaServer(
    private val onCode: (String) -> Unit,
    private val onAsk: (String, (Boolean) -> Unit) -> Unit,
    private val onCmd: (JSONObject) -> Unit,
) : WebSocketServer(InetSocketAddress(Hub.PORT)) {
    @Volatile var code = newCode()
    private val trusted = Collections.synchronizedSet(mutableSetOf<WebSocket>())

    private fun rotate() { code = newCode(); onCode(code) }

    override fun onStart() { onCode(code) }
    override fun onOpen(c: WebSocket, h: ClientHandshake) {}
    override fun onClose(c: WebSocket, co: Int, r: String?, rm: Boolean) {
        trusted.remove(c); if (trusted.isEmpty()) Hub.connected.value = false
    }
    override fun onError(c: WebSocket?, e: Exception) {}
    override fun onMessage(c: WebSocket, m: String) {
        val j = runCatching { JSONObject(m) }.getOrNull() ?: return
        if (j.optString("t") == "pair") {
            if (j.optString("code") != code) { rotate(); c.send("""{"t":"deny"}"""); c.close(); return }
            rotate()
            onAsk(j.optString("name", "Controller")) { ok ->
                if (ok) { trusted.add(c); c.send("""{"t":"ok"}""") } else { c.send("""{"t":"deny"}"""); c.close() }
            }
        } else if (c in trusted) onCmd(j)
    }
    fun hasClients() = trusted.isNotEmpty()
    fun sendFrame(b: ByteArray) { synchronized(trusted) { trusted.toList() }.forEach { runCatching { it.send(b) } } }
    fun revokeAll() { synchronized(trusted) { trusted.toList() }.forEach { it.close() }; trusted.clear() }
}

class AlmaClient(uri: URI, private val code: String, private val name: String, private val onState: (String) -> Unit, private val onFrame: (ByteArray) -> Unit) :
    WebSocketClient(uri) {
    override fun onOpen(h: ServerHandshake) {
        send(JSONObject().put("t", "pair").put("code", code).put("name", name).toString())
    }
    override fun onMessage(m: String) { onState(JSONObject(m).optString("t")) }
    override fun onMessage(b: java.nio.ByteBuffer) { val a = ByteArray(b.remaining()); b.get(a); onFrame(a) }
    override fun onClose(c: Int, r: String?, rm: Boolean) { onState("closed") }
    override fun onError(e: Exception) { onState("error") }
    fun cmd(vararg p: Pair<String, Any>) {
        if (isOpen) send(JSONObject(mapOf<String, Any>("t" to "cmd") + p).toString())
    }
}
