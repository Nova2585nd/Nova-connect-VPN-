package app.nova.connect

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

// هسته Xray به‌صورت فایل اجرایی (libxray.so در nativeLibraryDir) اجرا می‌شود
class XrayProc(private val ctx: Context) {
    var httpPort = 0; var socksPort = 0; var apiPort = 0
    @Volatile var exited = false
    @Volatile var onExit: (() -> Unit)? = null
    private var proc: Process? = null
    private val bin get() = File(ctx.applicationInfo.nativeLibraryDir, "libxray.so")

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    private fun canConnect(port: Int): Boolean = try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300); true } } catch (e: Exception) { false }

    fun start(server: JSONObject, socks: Boolean, stats: Boolean) {
        if (!bin.exists()) throw UserError("هسته Xray در برنامه پیدا نشد.")
        httpPort = freePort(); if (socks) socksPort = freePort(); if (stats) apiPort = freePort()
        val cfg = File(ctx.cacheDir, "x-" + System.nanoTime() + ".json")
        cfg.writeText(XrayConfig.build(server, httpPort, socksPort, apiPort))
        try {
            val pb = ProcessBuilder(bin.path, "run", "-c", cfg.path).redirectErrorStream(true)
            pb.environment()["XRAY_LOCATION_ASSET"] = ctx.filesDir.path
            val p = pb.start(); proc = p
            Thread {
                var tail = ""
                try { p.inputStream.bufferedReader().forEachLine { tail = (tail + it + "\n").takeLast(600) } } catch (e: Exception) { }
                exited = true
                if (tail.isNotBlank()) Core.log("xray: $tail")
                onExit?.invoke()
            }.start()
            for (i in 0 until 150) { // تا ۱۵ ثانیه؛ اجرای اول ممکن است کند باشد
                if (exited) throw UserError("هسته Xray اجرا نشد.")
                if (canConnect(httpPort)) return
                Thread.sleep(100)
            }
            throw UserError("هسته Xray اجرا نشد.")
        } catch (e: Exception) { stop(); throw e } finally { cfg.delete() }
    }

    // مجموع بایت‌های ارسال و دریافت [up, down]
    fun traffic(): LongArray? {
        if (apiPort == 0) return null
        return try {
            val p = ProcessBuilder(bin.path, "api", "statsquery", "--server=127.0.0.1:$apiPort", "-pattern", "outbound>>>proxy>>>traffic").redirectErrorStream(true).start()
            if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
            val arr = JSONObject(p.inputStream.bufferedReader().readText()).optJSONArray("stat") ?: return longArrayOf(0, 0)
            var up = 0L; var down = 0L
            for (i in 0 until arr.length()) {
                val x = arr.getJSONObject(i); val v = x.optString("value").toLongOrNull() ?: 0L
                if (x.optString("name").endsWith("uplink")) up = v else if (x.optString("name").endsWith("downlink")) down = v
            }
            longArrayOf(up, down)
        } catch (e: Exception) { null }
    }

    fun stop() {
        onExit = null
        try { proc?.destroy() } catch (e: Exception) { }
        val p = proc; proc = null
        if (p != null) Thread { try { if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly() } catch (e: Exception) { } }.start()
    }
}
