package com.alma.connect.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Updater {
    const val REPO = "tyrantping/almeer-silent-forge-rfob"
    data class Info(val code: Long, val name: String, val url: String)

    fun check(ctx: Context): Info? = runCatching {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 8000; c.readTimeout = 8000
        val j = JSONObject(c.inputStream.bufferedReader().readText())
        val code = j.getString("tag_name").removePrefix("build-").toLong()
        val cur = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
        val assets = j.getJSONArray("assets")
        var url = ""
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
        }
        if (code > cur && url.isNotEmpty()) Info(code, "0.5.$code".let { j.optString("name", it) }, url) else null
    }.getOrNull()

    fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File? = runCatching {
        val f = File(ctx.cacheDir, "update.apk")
        val c = URL(url).openConnection() as HttpURLConnection
        val total = c.contentLength
        c.inputStream.use { i ->
            f.outputStream().use { o ->
                val buf = ByteArray(32768); var got = 0L; var n: Int
                while (i.read(buf).also { n = it } >= 0) {
                    o.write(buf, 0, n); got += n
                    if (total > 0) onProgress((got * 100 / total).toInt())
                }
            }
        }
        f
    }.getOrNull()

    fun install(ctx: Context, f: File) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val u = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
