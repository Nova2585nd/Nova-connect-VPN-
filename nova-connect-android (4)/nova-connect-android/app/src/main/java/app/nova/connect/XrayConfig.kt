package app.nova.connect

import org.json.JSONArray
import org.json.JSONObject

object XrayConfig {
    // حالت‌های سازگاری وقتی کانفیگ پیش‌فرض عبور نمی‌دهد
    val VARIANTS = listOf("nofp", "h1", "ipv4", "insecure")
    fun applyVariant(s: JSONObject, v: String?): JSONObject {
        if (v == null) return s
        val o = JSONObject(s.toString())
        when (v) { "nofp" -> o.put("fp", "none"); "h1" -> o.put("alpn", "http/1.1"); "ipv4" -> o.put("ipv4", true); "insecure" -> o.put("allowInsecure", true) }
        return o
    }
    private fun list(s: String?): JSONArray? {
        if (s.isNullOrEmpty()) return null
        val a = JSONArray(); s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { a.put(it) }; return a
    }

    private fun stream(s: JSONObject): JSONObject {
        var net = s.optString("network", "tcp").ifEmpty { "tcp" }
        if (net == "h2") net = "http"; if (net == "splithttp") net = "xhttp"
        val tls = s.optString("tls")
        val sec = if (tls == "reality") "reality" else if (tls == "tls") "tls" else "none"
        val o = JSONObject(); o.put("network", net); o.put("security", sec)
        val path = s.optString("path").ifEmpty { "/" }; val host = s.optString("host")
        when (net) {
            "ws" -> o.put("wsSettings", JSONObject().put("path", path).put("headers", JSONObject().apply { if (host.isNotEmpty()) put("Host", host) }))
            "grpc" -> o.put("grpcSettings", JSONObject().put("serviceName", s.optString("serviceName")).put("multiMode", s.optString("mode") == "multi"))
            "httpupgrade" -> o.put("httpupgradeSettings", JSONObject().put("path", path).put("host", host))
            "xhttp" -> o.put("xhttpSettings", JSONObject().put("path", path).put("host", host).put("mode", s.optString("mode").ifEmpty { "auto" }))
            "http" -> o.put("httpSettings", JSONObject().put("path", path).apply { list(host)?.let { put("host", it) } })
            "tcp" -> if (s.optString("headerType") == "http") {
                val hdr = JSONObject().put("type", "http").put("request", JSONObject().put("path", JSONArray().put(path))
                    .put("headers", JSONObject().put("Host", list(host) ?: JSONArray())))
                o.put("tcpSettings", JSONObject().put("header", hdr))
            }
        }
        val fp = s.optString("fp").ifEmpty { "chrome" }
        if (s.optBoolean("ipv4", false)) o.put("sockopt", JSONObject().put("domainStrategy", "UseIPv4"))
        if (sec == "tls") o.put("tlsSettings", JSONObject().put("serverName", s.optString("sni").ifEmpty { s.optString("address") })
            .put("allowInsecure", s.optBoolean("allowInsecure", false)).apply { if (fp != "none") put("fingerprint", fp); list(s.optString("alpn"))?.let { put("alpn", it) } })
        if (sec == "reality") o.put("realitySettings", JSONObject().put("serverName", s.optString("sni")).apply { if (fp != "none") put("fingerprint", fp) }
            .put("publicKey", s.optString("pbk")).put("shortId", s.optString("sid")).put("spiderX", s.optString("spx")))
        return o
    }

    private fun outbound(s: JSONObject): JSONObject {
        val addr = s.optString("address"); val port = s.optInt("port")
        val o = JSONObject().put("streamSettings", stream(s))
        when (s.optString("protocol")) {
            "vless" -> o.put("protocol", "vless").put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", addr).put("port", port)
                .put("users", JSONArray().put(JSONObject().put("id", s.optString("id")).put("encryption", "none").apply { if (s.optString("flow").isNotEmpty()) put("flow", s.optString("flow")) })))))
            "vmess" -> o.put("protocol", "vmess").put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", addr).put("port", port)
                .put("users", JSONArray().put(JSONObject().put("id", s.optString("id")).put("alterId", 0).put("security", s.optString("security", "auto")))))))
            "trojan" -> o.put("protocol", "trojan").put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().put("address", addr).put("port", port).put("password", s.optString("password")))))
            "shadowsocks" -> o.put("protocol", "shadowsocks").put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().put("address", addr).put("port", port)
                .put("method", s.optString("method")).put("password", s.optString("password")))))
            else -> throw UserError("پروتکل پشتیبانی نمی‌شود.")
        }
        return o.put("tag", "proxy")
    }

    fun build(s: JSONObject, httpPort: Int, socksPort: Int, apiPort: Int): String {
        val inb = JSONArray()
        inb.put(JSONObject().put("tag", "http").put("listen", "127.0.0.1").put("port", httpPort).put("protocol", "http").put("settings", JSONObject()))
        if (socksPort > 0) inb.put(JSONObject().put("tag", "socks").put("listen", "127.0.0.1").put("port", socksPort).put("protocol", "socks")
            .put("settings", JSONObject().put("udp", true)))
        val cfg = JSONObject().put("log", JSONObject().put("loglevel", "warning")).put("inbounds", inb)
            .put("outbounds", JSONArray().put(outbound(s)).put(JSONObject().put("protocol", "freedom").put("tag", "direct")))
        if (apiPort > 0) {
            cfg.put("stats", JSONObject()).put("api", JSONObject().put("tag", "api").put("services", JSONArray().put("StatsService")))
            cfg.put("policy", JSONObject().put("system", JSONObject().put("statsOutboundUplink", true).put("statsOutboundDownlink", true)))
            inb.put(JSONObject().put("tag", "api").put("listen", "127.0.0.1").put("port", apiPort).put("protocol", "dokodemo-door").put("settings", JSONObject().put("address", "127.0.0.1")))
            cfg.put("routing", JSONObject().put("rules", JSONArray().put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put("api")).put("outboundTag", "api"))))
        }
        return cfg.toString()
    }
}
