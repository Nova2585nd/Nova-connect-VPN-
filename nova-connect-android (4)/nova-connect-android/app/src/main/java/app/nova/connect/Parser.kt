package app.nova.connect

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

class UserError(msg: String) : Exception(msg)

object Parser {
    private fun truthy(v: String?): Boolean = v != null && Regex("^(1|true|yes|on)$", RegexOption.IGNORE_CASE).matches(v.trim())
    class Result(val servers: List<JSONObject>, val unsupported: Int)

    fun b64(s: String): String {
        var t = s.trim().replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return String(Base64.getDecoder().decode(t), Charsets.UTF_8)
    }

    private fun dec(s: String): String = try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { s }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun fin(o: JSONObject, name: String?): JSONObject? {
        val port = o.optInt("port", 0)
        if (o.optString("address").isEmpty() || port < 1 || port > 65535) return null
        val ok = when (o.optString("protocol")) {
            "shadowsocks" -> o.optString("method").isNotEmpty() && o.optString("password").isNotEmpty()
            "trojan" -> o.optString("password").isNotEmpty()
            else -> o.optString("id").isNotEmpty()
        }
        if (!ok) return null
        val key = listOf("protocol", "address", "port", "id", "password", "network", "path", "serviceName", "tls", "pbk")
            .joinToString("|") { o.opt(it)?.toString() ?: "" }
        o.put("uid", sha1(key).take(16))
        val n = name?.trim() ?: ""
        o.put("name", if (n.isNotEmpty()) n else o.optString("address") + ":" + port)
        return o
    }

    private fun put(o: JSONObject, k: String, v: String?) { if (!v.isNullOrEmpty()) o.put(k, v) }

    private fun hostPort(hp: String): Pair<String, Int> {
        val i = hp.lastIndexOf(':')
        val h = if (i >= 0) hp.substring(0, i) else hp
        val p = if (i >= 0) hp.substring(i + 1).toIntOrNull() ?: 0 else 0
        return Pair(h.trim('[', ']'), p)
    }

    fun parseLink(raw: String): JSONObject? {
        val link = raw.trim()
        val proto = link.substringBefore("://").lowercase()
        try {
            if (proto == "vmess") {
                val j = JSONObject(b64(link.substring(8)))
                val o = JSONObject()
                o.put("protocol", "vmess"); o.put("address", j.optString("add")); o.put("port", j.optString("port").toIntOrNull() ?: 0)
                put(o, "id", j.optString("id")); o.put("security", j.optString("scy", "auto").ifEmpty { "auto" })
                o.put("network", j.optString("net", "tcp").ifEmpty { "tcp" }); put(o, "path", j.optString("path")); put(o, "host", j.optString("host"))
                o.put("tls", if (j.optString("tls") == "tls") "tls" else "none")
                put(o, "sni", j.optString("sni").ifEmpty { j.optString("host") }); put(o, "fp", j.optString("fp")); put(o, "alpn", j.optString("alpn"))
                put(o, "headerType", j.optString("type")); put(o, "serviceName", j.optString("path"))
                o.put("allowInsecure", truthy(j.optString("allowInsecure")) || truthy(j.optString("insecure")) || (j.has("verify_cert") && !j.optBoolean("verify_cert", true)))
                return fin(o, j.optString("ps"))
            }
            if (proto == "ss") {
                var rest = link.substring(5); var name = ""
                val h = rest.indexOf('#')
                if (h >= 0) { name = dec(rest.substring(h + 1)); rest = rest.substring(0, h) }
                rest = rest.substringBefore('?').trimEnd('/')
                val cred: String; val hp: String
                if (rest.contains('@')) {
                    val i = rest.lastIndexOf('@'); val c = rest.substring(0, i); hp = rest.substring(i + 1)
                    cred = if (c.contains(':')) dec(c) else b64(c)
                } else {
                    val d = b64(rest); val i = d.lastIndexOf('@'); cred = d.substring(0, i); hp = d.substring(i + 1)
                }
                val ci = cred.indexOf(':'); val (host, port) = hostPort(hp)
                val o = JSONObject()
                o.put("protocol", "shadowsocks"); o.put("method", cred.substring(0, ci)); o.put("password", cred.substring(ci + 1))
                o.put("address", host); o.put("port", port); o.put("network", "tcp"); o.put("tls", "none")
                return fin(o, name)
            }
            if (proto == "vless" || proto == "trojan") {
                val body = link.substringAfter("://")
                val name = dec(body.substringAfter('#', ""))
                val main = body.substringBefore('#')
                val auth = main.substringBefore('?').trimEnd('/')
                val user = dec(auth.substringBeforeLast('@'))
                val (host, port) = hostPort(auth.substringAfterLast('@'))
                val q = HashMap<String, String>()
                for (kv in main.substringAfter('?', "").split('&')) {
                    if (kv.isEmpty()) continue
                    q[kv.substringBefore('=').lowercase()] = dec(kv.substringAfter('=', ""))
                }
                val o = JSONObject()
                o.put("protocol", proto); o.put("address", host); o.put("port", port)
                if (proto == "vless") o.put("id", user) else o.put("password", user)
                o.put("network", q["type"] ?: "tcp")
                o.put("tls", q["security"] ?: if (proto == "trojan") "tls" else "none")
                put(o, "sni", q["sni"] ?: q["peer"] ?: q["host"]); put(o, "host", q["host"]); put(o, "path", q["path"]); put(o, "fp", q["fp"] ?: q["fingerprint"])
                put(o, "alpn", q["alpn"]); put(o, "flow", q["flow"]); put(o, "pbk", q["pbk"]); put(o, "sid", q["sid"]); put(o, "spx", q["spx"])
                put(o, "serviceName", q["servicename"]); put(o, "mode", q["mode"]); put(o, "headerType", q["headertype"])
                o.put("allowInsecure", truthy(q["allowinsecure"]) || truthy(q["insecure"]) || truthy(q["skip-cert-verify"]))
                return fin(o, name)
            }
        } catch (e: Exception) { }
        return null
    }

    fun parseContent(text0: String): Result {
        var text = text0.trim()
        if (!text.contains("://")) { try { text = b64(text) } catch (e: Exception) { } }
        val seen = LinkedHashMap<String, JSONObject>(); var unsupported = 0
        for (line in text.split('\n')) {
            val l = line.trim(); if (l.isEmpty()) continue
            val s = parseLink(l)
            if (s != null) seen[s.getString("uid")] = s else unsupported++
        }
        return Result(seen.values.toList(), unsupported)
    }

    // باید خارج از ترد اصلی صدا زده شود
    fun fetch(url: String): Result {
        val u = try { URL(url.trim()) } catch (e: Exception) { throw UserError("لینک اشتراک معتبر نیست.") }
        if (u.protocol != "http" && u.protocol != "https") throw UserError("لینک اشتراک معتبر نیست.")
        val text: String
        try {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 20000; c.readTimeout = 20000; c.setRequestProperty("User-Agent", "NovaConnect/1.0")
            val code = c.responseCode
            if (code !in 200..299) throw UserError("دریافت اشتراک ناموفق بود (کد $code).")
            text = c.inputStream.use { String(it.readBytes().take(5_000_000).toByteArray(), Charsets.UTF_8) }
            c.disconnect()
        } catch (e: UserError) { throw e
        } catch (e: java.net.SocketTimeoutException) { throw UserError("زمان دریافت اشتراک تمام شد.")
        } catch (e: Exception) { throw UserError("دریافت اشتراک ناموفق بود. اینترنت را بررسی کنید.") }
        if (text.isBlank()) throw UserError("اشتراک خالی است.")
        val r = parseContent(text)
        if (r.servers.isEmpty()) throw UserError("اشتراک هیچ سرور قابل‌استفاده‌ای ندارد.")
        return r
    }
}
