package com.alma.connect.ui

import android.Manifest
import android.app.Activity
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.alma.connect.network.*
import com.alma.connect.service.ControlService
import java.net.URI

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33)
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun App() {
    var screen by remember { mutableStateOf("home") }
    when (screen) {
        "controlled" -> Controlled { screen = "home" }
        "controller" -> Controller { screen = "home" }
        else -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.CenterVertically)) {
            Text("Alma Connect", style = MaterialTheme.typography.headlineLarge)
            Text("Control your own phone over Wi-Fi or hotspot.")
            Button({ screen = "controller" }, Modifier.fillMaxWidth()) { Text("Control a device") }
            OutlinedButton({ screen = "controlled" }, Modifier.fillMaxWidth()) { Text("Make this device controllable") }
        }
    }
}

@Composable
fun Controlled(back: () -> Unit) {
    val ctx = LocalContext.current
    val code by Hub.code.collectAsState()
    val req by Hub.request.collectAsState()
    val on by Hub.connected.collectAsState()
    fun svc(a: String? = null) = Intent(ctx, ControlService::class.java).also { if (a != null) it.action = a }
    val sharing by Hub.sharing.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null)
            ctx.startForegroundService(svc("projection").putExtra("code", r.resultCode).putExtra("data", r.data))
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Pairing code")
        Text(code.ifEmpty { "Not running" }, style = MaterialTheme.typography.displayMedium)
        if (on) Text("A controller is connected.")
        if (sharing) Text("Screen sharing is ON.")
        Button({ ctx.startForegroundService(svc()) }, Modifier.fillMaxWidth()) { Text("Start") }
        Button({ launcher.launch(ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }, Modifier.fillMaxWidth()) { Text("Share screen") }
        OutlinedButton({ ctx.startService(svc("revoke")) }, Modifier.fillMaxWidth()) { Text("Disconnect and revoke all") }
        OutlinedButton({ ctx.stopService(svc()) }, Modifier.fillMaxWidth()) { Text("Stop") }
        OutlinedButton({ ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, Modifier.fillMaxWidth()) { Text("Enable Accessibility (needed for taps)") }
        TextButton(back) { Text("Back") }
    }
    req?.let { r ->
        fun done(ok: Boolean) { r.answer(ok); Hub.request.value = null }
        AlertDialog(onDismissRequest = { done(false) }, title = { Text("Allow control?") },
            text = { Text("${r.name} wants to control this phone.") },
            confirmButton = { TextButton({ done(true) }) { Text("Allow") } },
            dismissButton = { TextButton({ done(false) }) { Text("Deny") } })
    }
}

@Composable
fun Controller(back: () -> Unit) {
    val ctx = LocalContext.current
    val found by Hub.found.collectAsState()
    var host by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("idle") }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var typed by remember { mutableStateOf("") }
    val holder = remember { arrayOfNulls<AlmaClient>(1) }
    val disc = remember { Discovery(ctx) }
    DisposableEffect(Unit) { disc.start(); onDispose { disc.stop(); holder[0]?.close() } }
    fun send(vararg p: Pair<String, Any>) = holder[0]?.cmd(*p)

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state == "ok") {
            Text("Connected. Tap or drag on the screen.")
            Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(frame?.let { it.width.toFloat() / it.height } ?: 0.5f, true)
                .pointerInput(Unit) { detectTapGestures { send("a" to "tap", "x" to it.x / size.width, "y" to it.y / size.height) } }
                .pointerInput(Unit) {
                    var s = Offset.Zero; var e = Offset.Zero
                    detectDragGestures(onDragStart = { s = it; e = it },
                        onDragEnd = {
                            send("a" to "swipe", "x1" to s.x / size.width, "y1" to s.y / size.height,
                                "x2" to e.x / size.width, "y2" to e.y / size.height)
                        }) { ch, _ -> e = ch.position }
                }) { frame?.let { Image(it, null, Modifier.fillMaxSize()) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Back" to "back", "Home" to "home", "Recents" to "recents").forEach { (l, a) ->
                    Button({ send("a" to a) }, Modifier.weight(1f)) { Text(l, maxLines = 1) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Vol+" to "volup", "Vol-" to "voldown", "Lock" to "lock").forEach { (l, a) ->
                    OutlinedButton({ send("a" to a) }, Modifier.weight(1f)) { Text(l, maxLines = 1) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(typed, { typed = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Type on device") })
                Button({ send("a" to "text", "s" to typed); typed = "" }) { Text("Send") }
                OutlinedButton({ send("a" to "text", "s" to "\b") }) { Text("Del") }
            }
        } else {
            Text("Devices found", style = MaterialTheme.typography.titleMedium)
            found.forEach { d -> OutlinedButton({ host = d.host }, Modifier.fillMaxWidth()) { Text("${d.name} (${d.host})") } }
            OutlinedTextField(host, { host = it }, label = { Text("Device IP (e.g. 192.168.43.1 for hotspot)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(code, { code = it }, label = { Text("6-digit code") }, modifier = Modifier.fillMaxWidth())
            Button({
                state = "connecting"
                holder[0] = AlmaClient(URI("ws://$host:${Hub.PORT}"), code, Build.MODEL, { state = it },
                    { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.let { frame = it.asImageBitmap() } }).also { it.connect() }
            }, Modifier.fillMaxWidth()) { Text("Connect") }
            if (state != "idle") Text(when (state) {
                "connecting" -> "Waiting for the other phone to tap Allow..."
                "deny" -> "Denied or wrong code."
                else -> "Connection failed ($state)." })
        }
        TextButton(back) { Text("Back") }
    }
}
