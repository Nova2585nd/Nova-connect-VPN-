package app.nova.connect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class NovaVpnService : VpnService() {
    companion object {
        const val A_CONNECT = "app.nova.connect.CONNECT"; const val A_DISCONNECT = "app.nova.connect.DISCONNECT"; const val EXTRA_UID = "uid"
        @Volatile var instance: NovaVpnService? = null
    }

    // همه تغییر وضعیت‌ها روی یک ترد انجام می‌شود؛ کارهای سنگین در IO
    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private var gen = 0
    private var proc: XrayProc? = null
    private var tun: ParcelFileDescriptor? = null
    private var tunRunning = false
    private var watch: Job? = null
    private var recovering = false
    private var lastCounter: LongArray? = null

    override fun onCreate() { super.onCreate(); instance = this }
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            A_CONNECT -> { notify("در حال اتصال…", true); val uid = i.getStringExtra(EXTRA_UID); scope.launch { connect(uid) } }
            A_DISCONNECT -> scope.launch { disconnect(); stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onRevoke() { scope.launch { disconnect(); stopSelf() } }
    override fun onDestroy() { instance = null; runBlocking { try { disconnect() } catch (e: Exception) { } }; super.onDestroy() }

    // وقتی حجم تمام شد (از Usage فراخوانی می‌شود)
    // وقتی حساب حذف شد یا باید فوراً قطع کرد
    fun stopNow() { scope.launch { disconnect(""); stopSelf() } }
    fun quotaOut() { scope.launch { disconnect("حجم اشتراک شما تمام شد. از بخش «اشتراک» می‌توانید حجم بخرید."); stopSelf() } }

    private fun notify(text: String, first: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("nova", "Nova Connect", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, NovaVpnService::class.java).setAction(A_DISCONNECT), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "nova").setSmallIcon(R.drawable.ic_stat).setContentTitle("Nova Connect").setContentText(text)
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "قطع اتصال", stop).build()).build()
        if (first) startForeground(1, n) else nm.notify(1, n)
    }

    private fun errText(e: Exception) = if (e is UserError) (e.message ?: "") else "اتصال برقرار نشد."
    private fun step(st: String, uid: String?, n: Int, msg: String) = Core.updateConn(Conn(st, uid, msg, n))

    private suspend fun connect(uid: String?) {
        val g = ++gen; teardown()
        val s = Core.server(uid) ?: return
        recovering = false; step("connecting", uid, 1, "راه‌اندازی هسته Xray…")
        try { up(s, g) } catch (e: Exception) {
            if (g != gen) return
            Core.log("connect failed: ${e.message}"); teardown()
            Core.updateConn(Conn("error", uid, errText(e))); notify("خطا: " + errText(e)); return
        }
        if (g != gen) { teardown(); return }
        ok(s, g)
    }

    private fun ok(s: JSONObject, g: Int) {
        val uid = s.optString("uid"); Core.lastGood = uid; Core.save()
        Core.updateConn(Conn("connected", uid, "", 4, System.currentTimeMillis())); notify("متصل به " + s.optString("name")); startWatch(g)
        Core.scope.launch { Usage.report(true) }
    }

    // اجرای Xray، آزمون عبور ترافیک و سپس ساخت تونل؛ فقط در صورت موفقیت «متصل» اعلام می‌شود
    private suspend fun up(s: JSONObject, g: Int) {
        val p = XrayProc(this); val cur = Core.conn.currentId
        withContext(Dispatchers.IO) { p.start(XrayConfig.applyVariant(s, Core.variant), true, true) }
        if (g != gen) { p.stop(); throw UserError("لغو شد.") }
        proc = p; lastCounter = longArrayOf(0, 0)
        p.onExit = { scope.launch { if (proc === p && g == gen) lost(g) } }
        step("connecting", cur, 2, "آزمون عبور ترافیک از سرور…")
        val pr = withContext(Dispatchers.IO) { Monitor.probeAny(p.httpPort, 9000) }
        if (pr.ms == null) { Core.log("probe failed: ${pr.err}"); throw UserError("هسته اجرا شد ولی ترافیک از این سرور عبور نکرد (${pr.err}).") }
        if (g != gen) throw UserError("لغو شد.")
        step("connecting", cur, 3, "ساخت تونل VPN…")
        withContext(Dispatchers.IO) { openTun(p.socksPort) }
    }

    private fun openTun(socksPort: Int) {
        val b = Builder().setSession("Nova Connect").setMtu(1500)
            .addAddress("26.26.26.1", 30).addRoute("0.0.0.0", 0)
            .addAddress("fdfe:dcba:9876::1", 126).addRoute("::", 0)
            .addDnsServer("1.1.1.1").addDnsServer("8.8.8.8")
        b.addDisallowedApplication(packageName) // ترافیک خود Xray و درخواست‌های بک‌اند از تونل عبور نمی‌کنند
        val fd = b.establish() ?: throw UserError("دسترسی VPN داده نشد.")
        tun = fd
        val cfg = File(filesDir, "tun.yml")
        cfg.writeText("tunnel:\n  mtu: 1500\n  ipv4: 26.26.26.1\n  ipv6: 'fdfe:dcba:9876::1'\nsocks5:\n  port: $socksPort\n  address: 127.0.0.1\n  udp: 'udp'\nmisc:\n  log-level: warn\n")
        try { TunBind.start(cfg.path, fd.fd); tunRunning = true }
        catch (e: Throwable) { Core.log("tun start failed: $e"); throw UserError("راه‌اندازی تونل ناموفق بود.") }
    }

    // مصرف از شمارنده‌های هسته: با هر ری‌استارت Xray شمارنده صفر می‌شود، پس اختلاف‌ها روی عدد تجمعی جمع می‌شوند
    private fun countUsage(t: LongArray?) {
        if (t == null) return
        val l = lastCounter ?: longArrayOf(0, 0)
        val dUp = if (t[0] >= l[0]) t[0] - l[0] else t[0]; val dDown = if (t[1] >= l[1]) t[1] - l[1] else t[1]
        Usage.add(dUp + dDown); lastCounter = t
    }

    private fun startWatch(g: Int) {
        watch?.cancel()
        watch = scope.launch {
            var fails = 0; var lastT = 0L; var last: LongArray? = null; var tick = 0
            while (isActive && g == gen) {
                delay(2000); tick++
                val p = proc ?: break
                val t = withContext(Dispatchers.IO) { p.traffic() }; val now = System.currentTimeMillis()
                if (g != gen) return@launch
                countUsage(t)
                val prev = last
                if (t != null && prev != null && now > lastT) {
                    val c = Core.conn
                    Core.updateConn(Conn(c.status, c.currentId, c.message, c.step, c.since, maxOf(0.0, (t[0] - prev[0]) * 1000.0 / (now - lastT)), maxOf(0.0, (t[1] - prev[1]) * 1000.0 / (now - lastT))))
                }
                if (t != null) { last = t; lastT = now }
                if (tick % 4 == 0) { // بررسی سلامت هر ۸ ثانیه
                    val ms = withContext(Dispatchers.IO) { Monitor.probeAny(p.httpPort, 6000) }.ms
                    if (g != gen) return@launch
                    fails = if (ms == null) fails + 1 else 0
                    if (fails >= 2) { lost(g); return@launch }
                }
            }
        }
    }

    private fun stopTun() {
        if (tunRunning) { try { TunBind.stop() } catch (e: Throwable) { }; tunRunning = false }
        try { tun?.close() } catch (e: Exception) { }; tun = null
    }
    private suspend fun teardown() {
        watch?.cancel(); watch = null
        val p = proc; if (p != null) { countUsage(withContext(Dispatchers.IO) { p.traffic() }); p.stop() } // آخرین مقدار قبل از توقف هسته
        proc = null; lastCounter = null; stopTun(); Usage.persist()
    }

    private fun lost(g: Int) {
        if (recovering || g != gen) return
        recovering = true
        scope.launch { try { recover(g) } catch (e: Exception) { Core.log("recover crashed: ${e.message}") } finally { recovering = false } }
    }

    private suspend fun recover(g: Int) {
        val cur = Core.conn.currentId; val retries = Core.retries
        teardown(); Core.log("connection lost")
        for (n in 1..retries) {
            step("reconnecting", cur, 0, "اتصال قطع شده؛ تلاش مجدد $n از $retries"); notify("در حال اتصال مجدد…")
            delay(minOf(1500L * (1 shl (n - 1)), 12000L)); if (g != gen) return
            val s = Core.server(cur) ?: break
            try { up(s, g); if (g != gen) { teardown(); return }; ok(s, g); return } catch (e: Exception) { teardown() }
        }
        Core.stats[cur ?: ""]?.let { it.fails += 3; it.badUntil = System.currentTimeMillis() + 5 * 60000 }
        var wait = 5000L
        while (g == gen) {
            step("switching", cur, 0, "اتصال مجدد ناموفق بود؛ در حال بررسی سرورهای جایگزین"); notify("در حال تغییر سرور…")
            for (s in Core.rank().take(5)) {
                if (g != gen) return
                try { up(s, g); if (g != gen) { teardown(); return }; ok(s, g); return }
                catch (e: Exception) { teardown(); Core.stats[s.optString("uid")]?.let { it.fails += 1; it.badUntil = System.currentTimeMillis() + 120000 } }
            }
            Core.updateConn(Conn("error", cur, "هیچ سرور مناسبی پیدا نشد؛ دوباره تلاش می‌کنیم.")); notify("هیچ سرور مناسبی پیدا نشد")
            delay(wait); wait = minOf(wait * 2, 60000L); if (g != gen) return
            Core.stats.values.forEach { it.badUntil = 0 }
            withContext(Dispatchers.IO) { Core.runTests(false) }
        }
    }

    private suspend fun disconnect(msg: String = "") {
        gen++; recovering = false; teardown()
        Core.updateConn(Conn("idle", null, msg))
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { }
        Core.scope.launch { Usage.report(true) } // گزارش نهایی هنگام قطع
    }
}
