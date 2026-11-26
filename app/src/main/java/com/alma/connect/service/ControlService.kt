package com.alma.connect.service

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
        val dm = resources.displayMetrics; val w = dm.widthPixels; val h = dm.heightPixels
        val acc = RemoteAccessibility.inst
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        when (j.optString("a")) {
            "tap" -> acc?.gesture(j.f("x") * w, j.f("y") * h, 0f, 0f, 50, false)
            "swipe" -> acc?.gesture(j.f("x1") * w, j.f("y1") * h, j.f("x2") * w, j.f("y2") * h, 300, true)
            "back" -> acc?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> acc?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> acc?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
            "lock" -> if (Build.VERSION.SDK_INT >= 28)
                acc?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            "text" -> acc?.typeText(j.optString("s"))
            "volup" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "voldown" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }
    }

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
        val dm = resources.displayMetrics
        val w = 480; val h = dm.heightPixels * w / dm.widthPixels
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
                Bitmap.createBitmap(bmp, 0, 0, w, h).compress(Bitmap.CompressFormat.JPEG, 45, out)
                server?.sendFrame(out.toByteArray())
            } finally { img.close() }
        }, Handler(bg.looper))
        Hub.sharing.value = true
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
