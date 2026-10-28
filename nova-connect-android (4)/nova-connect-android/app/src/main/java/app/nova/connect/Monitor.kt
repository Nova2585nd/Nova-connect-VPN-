package app.nova.connect

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class Probe(val ms: Int?, val err: String)
class TestRes(val ms: Int?, val reason: String?, val detail: String?, val tcp: Boolean)

object Monitor {
    // مثل v2rayN از HTTPS استفاده می‌کنیم؛ بسیاری از سرورها پورت ۸۰ را مسدود می‌کنند
    val TEST_URLS = listOf("https://www.google.com/generate_204", "https://cp.cloudflare.com/generate_204", "http://www.gstatic.com/generate_204")

    private fun readUntil(inp: InputStream, endLine: Boolean): String {
        val sb = StringBuilder()
        while (sb.length < 4096) {
            val b = inp.read(); if (b < 0) break
            sb.append(b.toChar())
            if (endLine && b == '\n'.code) break
            if (!endLine && sb.endsWith("\r\n\r\n")) break
        }
        return sb.toString()
    }

    private fun probeHttps(port: Int, url: String, timeout: Int): Probe {
        val u = URL(url); val host = u.host; val t0 = System.currentTimeMillis()
        val s = Socket()
        return try {
            s.connect(InetSocketAddress("127.0.0.1", port), timeout); s.soTimeout = timeout
            s.getOutputStream().apply { write("CONNECT $host:443 HTTP/1.1\r\nHost: $host:443\r\n\r\n".toByteArray()); flush() }
            val head = readUntil(s.getInputStream(), false)
            if (!(head.startsWith("HTTP/1.1 200") || head.startsWith("HTTP/1.0 200"))) return Probe(null, "proxy " + head.take(14).trim())
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(s, host, 443, true) as SSLSocket
            ssl.soTimeout = timeout; ssl.startHandshake()
            ssl.outputStream.apply { write("GET ${u.path} HTTP/1.1\r\nHost: $host\r\nUser-Agent: NovaConnect\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
            val code = readUntil(ssl.inputStream, true).split(" ").getOrNull(1)
            if (code == "204" || code == "200") Probe((System.currentTimeMillis() - t0).toInt(), "") else Probe(null, "http $code")
        } catch (e: SocketTimeoutException) { Probe(null, "timeout")
        } catch (e: Exception) { Probe(null, e.javaClass.simpleName)
        } finally { try { s.close() } catch (e: Exception) { } }
    }

    // تأخیر یک درخواست روی اتصال باز و ازقبل‌برقرارشده؛ همان چیزی که کاربر عملاً در هر درخواست حس می‌کند
    fun probeWarm(port: Int, url: String, timeout: Int): Probe {
        if (!url.startsWith("https")) return Probe(null, "n/a")
        val u = URL(url); val host = u.host; val s = Socket()
        return try {
            s.connect(InetSocketAddress("127.0.0.1", port), timeout); s.soTimeout = timeout
            s.getOutputStream().apply { write("CONNECT $host:443 HTTP/1.1\r\nHost: $host:443\r\n\r\n".toByteArray()); flush() }
            val head = readUntil(s.getInputStream(), false)
            if (!(head.startsWith("HTTP/1.1 200") || head.startsWith("HTTP/1.0 200"))) return Probe(null, "proxy")
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(s, host, 443, true) as SSLSocket
            ssl.soTimeout = timeout; ssl.startHandshake()
            val req = "GET ${u.path} HTTP/1.1\r\nHost: $host\r\nUser-Agent: NovaConnect\r\nConnection: keep-alive\r\n\r\n".toByteArray()
            ssl.outputStream.apply { write(req); flush() }
            val c1 = readUntil(ssl.inputStream, false).split(" ").getOrNull(1)
            if (c1 != "204") return Probe(null, "http $c1")
            val t0 = System.currentTimeMillis()
            ssl.outputStream.apply { write(req); flush() }
            val c2 = readUntil(ssl.inputStream, false).split(" ").getOrNull(1)
            if (c2 == "204") Probe((System.currentTimeMillis() - t0).toInt(), "") else Probe(null, "http $c2")
        } catch (e: SocketTimeoutException) { Probe(null, "timeout")
        } catch (e: Exception) { Probe(null, e.javaClass.simpleName)
        } finally { try { s.close() } catch (e: Exception) { } }
    }

    private fun probeHttp(port: Int, url: String, timeout: Int): Probe {
        val t0 = System.currentTimeMillis()
        return try {
            val c = URL(url).openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))) as HttpURLConnection
            c.connectTimeout = timeout; c.readTimeout = timeout; c.instanceFollowRedirects = false; c.setRequestProperty("Connection", "close")
            val code = c.responseCode; c.disconnect()
            if (code == 204 || code == 200) Probe((System.currentTimeMillis() - t0).toInt(), "") else Probe(null, "http $code")
        } catch (e: SocketTimeoutException) { Probe(null, "timeout") } catch (e: Exception) { Probe(null, e.javaClass.simpleName) }
    }

    fun probe(port: Int, url: String, timeout: Int): Probe = if (url.startsWith("https")) probeHttps(port, url, timeout) else probeHttp(port, url, timeout)

    fun probeAny(port: Int, timeout: Int = 6000): Probe {
        var last = Probe(null, "none")
        for (u in TEST_URLS) { val r = probe(port, u, timeout); if (r.ms != null) return r; last = r }
        return last
    }

    private fun tcpOk(host: String, port: Int): Boolean = try { Socket().use { it.connect(InetSocketAddress(host, port), 4000); true } } catch (e: Exception) { false }

    fun once(ctx: Context, srv: JSONObject): TestRes {
        val p = XrayProc(ctx)
        return try {
            p.start(srv, false, false)
            val a = probeAny(p.httpPort)
            if (a.ms != null) {
                val b = if (a.ms < 4000) probeWarm(p.httpPort, TEST_URLS[0], 6000) else Probe(null, "")
                TestRes(if (b.ms != null) minOf(a.ms, b.ms) else a.ms, null, null, true)
            } else TestRes(null, "noresp", a.err, false)
        } catch (e: Exception) { TestRes(null, "core", e.message, false) } finally { p.stop() }
    }

    fun testServer(ctx: Context, s: JSONObject, variant: String?): TestRes {
        val r = once(ctx, XrayConfig.applyVariant(s, variant))
        return if (r.reason == "noresp") TestRes(null, "noresp", r.detail, tcpOk(s.optString("address"), s.optInt("port"))) else r
    }

    suspend fun testAll(ctx: Context, servers: List<JSONObject>, variant: String?, onResult: (JSONObject, TestRes) -> Unit) {
        val sem = Semaphore(3)
        coroutineScope {
            servers.map { s -> async(Dispatchers.IO) { sem.withPermit { onResult(s, testServer(ctx, s, variant)) } } }.awaitAll()
        }
    }

    // وقتی هیچ سروری عبور نمی‌دهد ولی TCP باز است، حالت سازگاری مناسب را پیدا می‌کند
    suspend fun discover(ctx: Context, servers: List<JSONObject>, onStep: (Int, Int, String) -> Unit): String? = withContext(Dispatchers.IO) {
        val cands = ArrayList<JSONObject>()
        for (s in servers) { if (cands.size >= 2) break; if (tcpOk(s.optString("address"), s.optInt("port"))) cands.add(s) }
        if (cands.isEmpty()) return@withContext null
        val vs = XrayConfig.VARIANTS
        for ((i, v) in vs.withIndex()) {
            onStep(i, vs.size, v)
            val rs = coroutineScope { cands.map { s -> async { once(ctx, XrayConfig.applyVariant(s, v)) } }.awaitAll() }
            if (rs.any { it.reason == "core" }) return@withContext null
            onStep(i + 1, vs.size, v)
            if (rs.any { it.ms != null }) return@withContext v
        }
        null
    }
}
