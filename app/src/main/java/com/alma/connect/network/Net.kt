package com.alma.connect.network

import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class Pending(val name: String, val answer: (Boolean) -> Unit)
data class Found(val name: String, val host: String)

object Hub {
    const val PORT = 8765
    const val TYPE = "_almaconnect._tcp."
    val code = MutableStateFlow("")
    val request = MutableStateFlow<Pending?>(null)
    val connected = MutableStateFlow(false)
    val sharing = MutableStateFlow(false)
    val a11y = MutableStateFlow(false)
    val found = MutableStateFlow<List<Found>>(emptyList())
    val logs = MutableStateFlow<List<String>>(emptyList())
    fun log(s: String) {
        logs.value = (logs.value + "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}  $s").takeLast(100)
    }
}

fun newCode() = (SecureRandom().nextInt(900000) + 100000).toString()

// ECDH key exchange; the session key is also bound to the pairing code. All traffic is AES-256-GCM.
object Crypto {
    private val rnd = SecureRandom()
    fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    fun derive(priv: PrivateKey, otherPub: ByteArray, code: String): SecretKeySpec {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(otherPub))
        val ka = KeyAgreement.getInstance("ECDH"); ka.init(priv); ka.doPhase(pub, true)
        val md = MessageDigest.getInstance("SHA-256"); md.update(ka.generateSecret()); md.update(code.toByteArray())
        return SecretKeySpec(md.digest(), "AES")
    }
    fun enc(k: SecretKeySpec, d: ByteArray): ByteArray {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv))
        return iv + c.doFinal(d)
    }
    fun dec(k: SecretKeySpec, d: ByteArray): ByteArray? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, d, 0, 12))
        c.doFinal(d, 12, d.size - 12)
    }.getOrNull()
}

private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

// Binary frames: first byte 0 = JSON message, 1 = JPEG screen frame (both encrypted).
class AlmaServer(
    private val onCode: (String) -> Unit,
    private val onAsk: (String, (Boolean) -> Unit) -> Unit,
    private val onCmd: (JSONObject) -> Unit,
) : WebSocketServer(InetSocketAddress(Hub.PORT)) {
    @Volatile var code = newCode()
    private val trusted = Collections.synchronizedSet(mutableSetOf<WebSocket>())
    private val keys = ConcurrentHashMap<WebSocket, SecretKeySpec>()

    private fun rotate() { code = newCode(); onCode(code) }
    private fun forTrusted(f: (WebSocket) -> Unit) = synchronized(trusted) { trusted.toList() }.forEach { runCatching { f(it) } }
    private fun sendTo(c: WebSocket, type: Int, d: ByteArray) { keys[c]?.let { c.send(Crypto.enc(it, byteArrayOf(type.toByte()) + d)) } }

    override fun onStart() { onCode(code) }
    override fun onOpen(c: WebSocket, h: ClientHandshake) {}
    override fun onClose(c: WebSocket, co: Int, r: String?, rm: Boolean) {
        keys.remove(c)
        if (trusted.remove(c)) Hub.log("Controller disconnected")
        if (trusted.isEmpty()) Hub.connected.value = false
    }
    override fun onError(c: WebSocket?, e: Exception) {}

    override fun onMessage(c: WebSocket, m: String) {
        val j = runCatching { JSONObject(m) }.getOrNull() ?: return
        if (j.optString("t") != "hello") return
        val kp = Crypto.keyPair()
        keys[c] = Crypto.derive(kp.private, unb64(j.getString("pk")), code)
        c.send(JSONObject().put("t", "hello").put("pk", b64(kp.public.encoded)).toString())
    }

    override fun onMessage(c: WebSocket, b: ByteBuffer) {
        val k = keys[c] ?: return
        val raw = ByteArray(b.remaining()); b.get(raw)
        val j = Crypto.dec(k, raw)?.takeIf { it.size > 1 }
            ?.let { runCatching { JSONObject(String(it, 1, it.size - 1)) }.getOrNull() }
        if (j == null) {
            if (c !in trusted) { Hub.log("Wrong code, code changed"); rotate(); c.send("""{"t":"deny"}"""); c.close() }
            return
        }
        if (j.optString("t") == "pair") {
            rotate()
            val n = j.optString("name", "Controller")
            Hub.log("Pair request from $n")
            onAsk(n) { ok ->
                if (ok) { trusted.add(c); Hub.log("Allowed $n"); sendTo(c, 0, """{"t":"ok"}""".toByteArray()) }
                else { Hub.log("Denied $n"); c.send("""{"t":"deny"}"""); c.close() }
            }
        } else if (c in trusted) onCmd(j)
    }

    fun hasClients() = trusted.isNotEmpty()
    fun sendFrame(b: ByteArray) = forTrusted { sendTo(it, 1, b) }
    fun tell(s: String) = forTrusted { sendTo(it, 0, s.toByteArray()) }
    fun revokeAll() { synchronized(trusted) { trusted.toList() }.forEach { it.close() }; trusted.clear() }
}

class AlmaClient(uri: URI, private val code: String, private val name: String,
                 private val onState: (String) -> Unit, private val onFrame: (ByteArray) -> Unit) : WebSocketClient(uri) {
    private val kp = Crypto.keyPair()
    @Volatile private var key: SecretKeySpec? = null
    private var denied = false

    private fun sendJson(s: String) { key?.let { send(Crypto.enc(it, byteArrayOf(0) + s.toByteArray())) } }

    override fun onOpen(h: ServerHandshake) {
        send(JSONObject().put("t", "hello").put("pk", b64(kp.public.encoded)).toString())
    }
    override fun onMessage(m: String) {
        val j = JSONObject(m)
        when (j.optString("t")) {
            "hello" -> {
                key = Crypto.derive(kp.private, unb64(j.getString("pk")), code)
                sendJson(JSONObject().put("t", "pair").put("code", code).put("name", name).toString())
            }
            "deny" -> { denied = true; Hub.log("Denied or wrong code"); onState("deny") }
        }
    }
    override fun onMessage(b: ByteBuffer) {
        val k = key ?: return
        val raw = ByteArray(b.remaining()); b.get(raw)
        val d = Crypto.dec(k, raw) ?: return
        if (d.size < 2) return
        if (d[0].toInt() == 1) onFrame(d.copyOfRange(1, d.size))
        else {
            val t = JSONObject(String(d, 1, d.size - 1)).optString("t")
            if (t == "ok") Hub.log("Connected")
            onState(t)
        }
    }
    override fun onClose(c: Int, r: String?, rm: Boolean) { if (!denied) { Hub.log("Connection closed"); onState("closed") } }
    override fun onError(e: Exception) { onState("error") }
    fun cmd(vararg p: Pair<String, Any>) {
        if (isOpen) sendJson(JSONObject(mapOf<String, Any>("t" to "cmd") + p).toString())
    }
}
