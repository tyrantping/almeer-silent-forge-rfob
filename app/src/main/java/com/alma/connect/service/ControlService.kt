package com.alma.connect.service

import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import java.io.ByteArrayOutputStream
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.*
import androidx.core.app.ServiceCompat
import com.alma.connect.network.*
import com.alma.connect.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject

class ControlService : Service() {
    private var server: AlmaServer? = null
    private var nsd: NsdManager? = null
    private var reg: NsdManager.RegistrationListener? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (i?.action == "revoke") { server?.revokeAll(); Hub.connected.value = false; return START_STICKY }
        if (server == null) boot()
        if (i?.action == "projection") {
            val d = i.getParcelableExtra<Intent>("data")
            if (d != null) {
                ServiceCompat.startForeground(this, 1, notif!!,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                startCapture(i.getIntExtra("code", 0), d)
            }
        }
        return START_STICKY
    }

    private fun boot() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("alma", "Alma Connect", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "alma").setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Alma Connect")
            .setContentText("This phone can be controlled. Tap to open and revoke access.")
            .setContentIntent(open).setOngoing(true).build()
        notif = n
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        val sv = AlmaServer({ Hub.code.value = it },
            { name, answer -> Hub.request.value = Pending(name) { ok -> if (ok) Hub.connected.value = true; answer(ok) } },
            ::handle)
        sv.isReuseAddr = true
        sv.start(); server = sv

        val info = NsdServiceInfo().apply { serviceName = "Alma-${Build.MODEL}"; serviceType = Hub.TYPE; port = Hub.PORT }
        reg = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) {}
            override fun onServiceRegistered(s: NsdServiceInfo) {}
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
        }
        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)
    }

    private fun JSONObject.f(k: String) = optDouble(k).toFloat()

    private fun handle(j: JSONObject) = main.post {
        val dm = realMetrics(); val w = dm.widthPixels; val h = dm.heightPixels
        val acc = RemoteAccessibility.inst
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        if (acc == null && j.optString("k") != "m" && j.optString("a") in setOf("tap", "long", "swipe", "back", "home", "recents", "lock", "notif", "quick", "text", "path", "touch"))
            server?.tell("""{"t":"noa11y"}""")
        when (j.optString("a")) {
            "tap" -> tap(acc, j.f("x"), j.f("y"), w, h)
            "path" -> path(acc, j, w, h)
            "swipe" -> acc?.gesture(j.f("x1") * w, j.f("y1") * h, j.f("x2") * w, j.f("y2") * h, 300, true)
            "back" -> acc?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> acc?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> acc?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
            "lock" -> if (Build.VERSION.SDK_INT >= 28)
                acc?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            "touch" -> touch(acc, j.optString("k"), j.f("x"), j.f("y"), w, h)
            "quality" -> when (j.optInt("v")) { 0 -> { capW = 360; jpegQ = 35 }; 2 -> { capW = 720; jpegQ = 60 }; else -> { capW = 480; jpegQ = 45 } }
            "long" -> acc?.gesture(j.f("x") * w, j.f("y") * h, 0f, 0f, 800, false)
            "notif" -> acc?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "quick" -> acc?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            "wake" -> wake()
            "text" -> acc?.typeText(j.optString("s"))
            "volup" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "voldown" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }
    }

    @Suppress("DEPRECATION")
    private fun realMetrics(): android.util.DisplayMetrics {
        val dm = android.util.DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun navMode(): Int {
        val mi = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
        return if (mi > 0) resources.getInteger(mi) else 2
    }

    // Touching the captured 3-button bar acts like the real buttons.
    private fun tap(acc: RemoteAccessibility?, x: Float, y: Float, w: Int, h: Int) {
        val nb = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val navH = if (nb > 0) resources.getDimensionPixelSize(nb) else 0
        if (acc != null && navMode() == 0 && navH > 0 && y * h > h - navH) {
            val sam = Build.MANUFACTURER.equals("samsung", true)
            val left = if (sam) AccessibilityService.GLOBAL_ACTION_RECENTS else AccessibilityService.GLOBAL_ACTION_BACK
            val right = if (sam) AccessibilityService.GLOBAL_ACTION_BACK else AccessibilityService.GLOBAL_ACTION_RECENTS
            acc.performGlobalAction(when { x < 0.33f -> left; x < 0.66f -> AccessibilityService.GLOBAL_ACTION_HOME; else -> right })
        } else acc?.gesture(x * w, y * h, 0f, 0f, 50, false)
    }

    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var moved = false
    private var edge = false
    private val edgePts = mutableListOf<Float>()

    private fun touch(acc: RemoteAccessibility?, k: String, x: Float, y: Float, w: Int, h: Int) {
        val slop = 12 * resources.displayMetrics.density
        fun far() = Math.hypot(((x - downX) * w).toDouble(), ((y - downY) * h).toDouble()) >= slop
        when (k) {
            "d" -> {
                downX = x; downY = y; downT = SystemClock.uptimeMillis(); moved = false
                edge = navMode() == 2 && (y > 0.95f || y < 0.05f || x < 0.05f || x > 0.95f)
                edgePts.clear(); edgePts.add(x); edgePts.add(y)
                acc?.liveStart(x * w, y * h)
            }
            "m" -> {
                if (edge) { edgePts.add(x); edgePts.add(y); return }
                if (!moved && !far()) return
                moved = true
                acc?.livePush(x * w, y * h, false)
            }
            "u" -> {
                val held = SystemClock.uptimeMillis() - downT
                when {
                    edge && far() -> { edgePts.add(x); edgePts.add(y); path(acc, JSONObject().put("p", JSONArray(edgePts)).put("ms", held), w, h) }
                    !far() && !moved -> if (held < 400) tap(acc, x, y, w, h) else acc?.gesture(x * w, y * h, 0f, 0f, held.coerceAtMost(2000), false)
                    else -> acc?.livePush(x * w, y * h, true)
                }
            }
        }
    }

    // Replays the finger path with its real speed, so scrolls and flicks feel natural.
    private fun path(acc: RemoteAccessibility?, j: JSONObject, w: Int, h: Int) {
        val a = j.optJSONArray("p") ?: return
        if (a.length() < 4) return
        val xs = FloatArray(a.length() / 2) { a.getDouble(it * 2).toFloat() }
        val ys = FloatArray(a.length() / 2) { a.getDouble(it * 2 + 1).toFloat() }
        val ms = j.optLong("ms", 200).coerceIn(50, 2000)
        val sx = xs.first(); val sy = ys.first(); val ex = xs.last(); val ey = ys.last()
        if (acc != null && navMode() == 2) {
            if (sy > 0.95f && sy - ey > 0.12f) {
                acc.performGlobalAction(if (ms > 600) AccessibilityService.GLOBAL_ACTION_RECENTS else AccessibilityService.GLOBAL_ACTION_HOME); return
            }
            if ((sx < 0.05f && ex - sx > 0.12f) || (sx > 0.95f && sx - ex > 0.12f)) {
                acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); return
            }
            if (sy < 0.05f && ey - sy > 0.12f) {
                acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); return
            }
        }
        acc?.stroke(FloatArray(xs.size) { xs[it] * w }, FloatArray(ys.size) { ys[it] * h }, ms)
    }

    @Suppress("DEPRECATION")
    private fun wake() {
        (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "alma:wake").acquire(3000)
    }

    @Volatile private var capW = 480
    @Volatile private var jpegQ = 45
    private var notif: Notification? = null
    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var ir: ImageReader? = null
    private val bg = HandlerThread("alma-cap").also { it.start() }

    private fun startCapture(code: Int, data: Intent) {
        stopCapture()
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        mp.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopCapture() } }, main)
        proj = mp
        val dm = realMetrics()
        val w = 720; val h = dm.heightPixels * w / dm.widthPixels
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        ir = reader
        vd = mp.createVirtualDisplay("alma", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, main)
        var last = 0L
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.uptimeMillis()
                if (now - last < 120 || server?.hasClients() != true) return@setOnImageAvailableListener
                last = now
                val p = img.planes[0]
                val bw = p.rowStride / p.pixelStride
                val bmp = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(p.buffer)
                val out = ByteArrayOutputStream()
                val full = Bitmap.createBitmap(bmp, 0, 0, w, h)
                (if (capW < w) Bitmap.createScaledBitmap(full, capW, h * capW / w, true) else full)
                    .compress(Bitmap.CompressFormat.JPEG, jpegQ, out)
                server?.sendFrame(out.toByteArray())
            } finally { img.close() }
        }, Handler(bg.looper))
        Hub.sharing.value = true; Hub.log("Screen sharing started")
    }

    private fun stopCapture() {
        val p = proj; proj = null
        runCatching { vd?.release(); ir?.close(); p?.stop() }
        vd = null; ir = null; Hub.sharing.value = false
    }

    override fun onDestroy() {
        stopCapture(); bg.quitSafely()
        runCatching { server?.revokeAll(); server?.stop() }
        runCatching { nsd?.unregisterService(reg) }
        server = null; Hub.code.value = ""; Hub.connected.value = false
        super.onDestroy()
    }
}
