package app.nova.connect

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class Stat(var status: String, var latency: Int?, var at: Long, var fails: Int = 0, var badUntil: Long = 0, var reason: String? = null, var tcp: Boolean = false)
// step: ۰ بیکار، ۱ راه‌اندازی هسته، ۲ آزمون عبور ترافیک، ۳ ساخت تونل، ۴ متصل
class Conn(val status: String = "idle", val currentId: String? = null, val message: String = "", val step: Int = 0,
           val since: Long = 0, val up: Double = -1.0, val down: Double = -1.0)
class PStep(val id: String, val label: String, var st: String = "pending", var detail: String = "")
class Progress(val run: Long, val steps: List<PStep>) {
    var active = true; var done = false; var pct = 2; var ok = false; var name = ""; var core = false
    val startedAt = System.currentTimeMillis()
}

// وضعیت مشترک برنامه. هر تغییر با changed() به رابط کاربری اعلام می‌شود
object Core {
    lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    val tick = mutableIntStateOf(0)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val testingFlag = AtomicBoolean(false)

    @Volatile var servers: List<JSONObject> = emptyList()
    val stats = ConcurrentHashMap<String, Stat>()
    val numbers = ConcurrentHashMap<String, Int>()
    @Volatile var nextNumber = 1
    @Volatile var variant: String? = null
    @Volatile var lastGood: String? = null
    @Volatile var subError: String? = null
    @Volatile var subUpdated = 0L
    @Volatile var lastCheck = 0L
    @Volatile var discovering = false
    @Volatile var conn = Conn()
    @Volatile var progress: Progress? = null
    @Volatile var dismissedRun = 0L
    @Volatile var code = 0
    @Volatile var codeInvalid = false
    @Volatile var user: UserInfo? = null
    @Volatile var userSyncedAt = 0L
    @Volatile var announcements: List<Ann> = emptyList()
    @Volatile var lastSeenAnn = 0
    @Volatile var priceUnit: Int? = null
    @Volatile var update: UpdateInfo? = null
    @Volatile var dismissedUpdate = 0
    @Volatile var exhaustDismissed = false
    @Volatile var welcomeNotice: String? = null
    val speedHist = ArrayList<Double>() // تاریخچه‌ی سرعت دانلود برای نمودار
    @Volatile var visible = 0
    val testing get() = testingFlag.get()
    val coreIssue: Boolean get() = stats.values.isNotEmpty() && stats.values.none { it.status == "ok" } && stats.values.any { it.reason == "core" }

    // تنظیمات: مقدار در state هم نگه داشته می‌شود تا رابط بلافاصله تازه شود
    private val pingS = mutableIntStateOf(45); private val retriesS = mutableIntStateOf(3); private val autoS = mutableStateOf(false)
    var pingInterval: Int get() = pingS.intValue; set(v) { pingS.intValue = v; prefs.edit().putInt("pingInterval", v).apply() }
    var retries: Int get() = retriesS.intValue; set(v) { retriesS.intValue = v; prefs.edit().putInt("retries", v).apply() }
    var autoConnect: Boolean get() = autoS.value; set(v) { autoS.value = v; prefs.edit().putBoolean("autoConnect", v).apply() }
    // دعوت
    @Volatile var askInvite = false
    @Volatile var inviteBonus: Double? = null
    val inviteDone: Boolean get() = prefs.getBoolean("inviteDone", false)
    val versionCode: Int get() = try { app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode.toInt() } catch (e: Exception) { 0 }
    val versionName: String get() = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
    val VLABEL = mapOf("nofp" to "بدون اثرانگشت TLS", "h1" to "ALPN ساده", "ipv4" to "فقط IPv4", "insecure" to "بدون اعتبارسنجی گواهی")

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext; prefs = app.getSharedPreferences("nova", 0); Usage.init(app); load()
        pingS.intValue = prefs.getInt("pingInterval", 45); retriesS.intValue = prefs.getInt("retries", 3); autoS.value = prefs.getBoolean("autoConnect", false); askInvite = prefs.getBoolean("askInvite", false)
        scope.launch { Usage.loop() }
        scope.launch { // سنجش دوره‌ای پینگ فقط وقتی برنامه دیده می‌شود یا متصل هستیم
            while (true) {
                delay(5000)
                if ((visible > 0 || conn.status == "connected") && !testing && servers.isNotEmpty() && progress?.active != true &&
                    System.currentTimeMillis() - lastCheck >= pingInterval * 1000L) runTests(false)
            }
        }
        scope.launch { // آپدیت و اطلاعیه‌ها: کم‌تکرار برای سبک ماندن بار روی هاست
            var lastAnn = 0L; var lastVer = 0L
            while (true) {
                delay(5000); val now = System.currentTimeMillis()
                if (visible > 0 && now - lastAnn >= 15 * 60_000L) { lastAnn = now; refreshAnnouncements() }
                if (now - lastVer >= 6 * 3_600_000L) { lastVer = now; checkUpdate() }
            }
        }
    }

    fun changed() { tick.intValue = tick.intValue + 1 }
    fun updateConn(c: Conn) {
        conn = c
        synchronized(speedHist) { if (c.status == "connected") { if (c.down >= 0) { speedHist.add(c.down); if (speedHist.size > 40) speedHist.removeAt(0) } } else speedHist.clear() }
        changed()
    }

    // ---- ذخیره‌سازی ----
    private fun load() {
        try {
            val j = JSONObject(prefs.getString("data", "{}")!!)
            code = j.optInt("code"); variant = j.optString("variant").ifEmpty { null }; lastGood = j.optString("lastGood").ifEmpty { null }
            subUpdated = j.optLong("subUpdated"); subError = j.optString("subError").ifEmpty { null }; nextNumber = j.optInt("nextNumber", 1)
            lastSeenAnn = j.optInt("lastSeenAnn"); priceUnit = if (j.has("price")) j.optInt("price") else null; dismissedUpdate = j.optInt("dismissedUpdate")
            j.optJSONObject("user")?.let { user = UserInfo(it.optDouble("t"), it.optDouble("u"), it.optDouble("r"), it.optBoolean("x")) }
            val a = j.optJSONArray("servers") ?: JSONArray(); servers = (0 until a.length()).map { a.getJSONObject(it) }
            j.optJSONObject("numbers")?.let { n -> for (k in n.keys()) numbers[k] = n.getInt(k) }
            j.optJSONObject("stats")?.let { st -> for (k in st.keys()) { val o = st.getJSONObject(k); stats[k] = Stat(o.optString("s"), if (o.has("l")) o.optInt("l") else null, o.optLong("at"), o.optInt("f"), 0, o.optString("r").ifEmpty { null }, o.optBoolean("tcp")) } }
        } catch (e: Exception) { log("load failed: ${e.message}") }
    }

    @Synchronized fun save() {
        val j = JSONObject().put("code", code).put("variant", variant ?: "").put("lastGood", lastGood ?: "").put("subUpdated", subUpdated).put("subError", subError ?: "")
            .put("nextNumber", nextNumber).put("lastSeenAnn", lastSeenAnn).put("dismissedUpdate", dismissedUpdate)
        priceUnit?.let { j.put("price", it) }
        user?.let { j.put("user", JSONObject().put("t", it.total).put("u", it.used).put("r", it.remaining).put("x", it.exhausted)) }
        j.put("servers", JSONArray(servers)); j.put("numbers", JSONObject(numbers as Map<*, *>))
        val st = JSONObject()
        for ((k, v) in stats) st.put(k, JSONObject().put("s", v.status).apply { if (v.latency != null) put("l", v.latency) }.put("at", v.at).put("f", v.fails).put("r", v.reason ?: "").put("tcp", v.tcp))
        prefs.edit().putString("data", j.put("stats", st).toString()).apply()
    }

    // ---- لاگ (لینک و شناسه‌ها حذف می‌شوند؛ کد اشتراک هرگز نوشته نمی‌شود) ----
    fun log(m: String) {
        try {
            val f = File(app.filesDir, "app.log"); if (f.length() > 1_000_000) f.renameTo(File(app.filesDir, "app.log.old"))
            val clean = m.replace(Regex("[a-zA-Z][a-zA-Z0-9+.-]*://\\S+"), "<link>").replace(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27}"), "<id>")
            f.appendText("${java.util.Date()} $clean\n")
        } catch (e: Exception) { }
    }
    fun logTail(): String = try { File(app.filesDir, "app.log").readLines().takeLast(150).joinToString("\n") } catch (e: Exception) { "" }

    // ---- انتخاب سرور ----
    fun server(uid: String?): JSONObject? = servers.firstOrNull { it.optString("uid") == uid }
    fun numOf(uid: String): Int = numbers[uid] ?: 0
    private fun usable(s: Stat?) = s != null && s.status == "ok" && s.latency != null && s.badUntil <= System.currentTimeMillis()
    fun rank(): List<JSONObject> = servers.filter { usable(stats[it.optString("uid")]) }.sortedBy { val s = stats[it.optString("uid")]!!; s.latency!! + s.fails * 400 }
    fun recommendedId(): String? = rank().firstOrNull()?.optString("uid")
    // پیش‌فرض: آخرین سرور موفق؛ دکمه اتصال هرگز بی‌اثر نباشد
    fun defaultId(): String? {
        val last = server(lastGood); val rec = recommendedId()
        if (last != null) { val st = stats[lastGood]; val dead = st != null && st.status == "fail" && st.fails >= 3; if (!dead || rec == null) return lastGood }
        return rec ?: (servers.firstOrNull { stats[it.optString("uid")]?.tcp == true } ?: servers.firstOrNull())?.optString("uid")
    }

    // ---- کاربر / بک‌اند ----
    fun applyUser(u: UserInfo) { user = u; userSyncedAt = System.currentTimeMillis(); codeInvalid = false; save(); changed() }
    suspend fun refreshUser(): Boolean = withContext(Dispatchers.IO) {
        if (code == 0) return@withContext false
        try { applyUser(Api.user(code)); true } catch (e: ApiError) { if (e.code == 404) onAccountDeleted(); false }
    }
    suspend fun refreshAnnouncements() = withContext(Dispatchers.IO) { try { announcements = Api.announcements(); changed() } catch (e: Exception) { } }
    suspend fun loadPrice() = withContext(Dispatchers.IO) { try { priceUnit = Api.priceUnit(); save(); changed() } catch (e: Exception) { } }
    suspend fun checkUpdate() = withContext(Dispatchers.IO) { try { update = Api.checkVersion(versionCode); changed() } catch (e: Exception) { } }
    val unreadAnnouncements: Int get() = announcements.count { it.id > lastSeenAnn }
    fun markAnnouncementsSeen() { lastSeenAnn = announcements.maxOfOrNull { it.id } ?: lastSeenAnn; save(); changed() }
    // اگر کد اشتراک از پنل حذف شده باشد: اتصال قطع می‌شود و کاربر به صفحه‌ی ورود اولیه می‌رود
    fun onAccountDeleted() {
        NovaVpnService.instance?.stopNow()
        welcomeNotice = "این حساب دیگر وجود ندارد. لطفاً دوباره وارد شوید."
        logout()
    }
    fun logout() { Usage.discardPending(); code = 0; user = null; codeInvalid = false; servers = emptyList(); stats.clear(); lastGood = null; variant = null; progress = null; save(); changed() }

    // قبل از اتصال: اگر حجم تمام شده، وضعیت از سرور دوباره خوانده می‌شود (شاید نوا حجم اضافه کرده)
    suspend fun connectGuard(): String? {
        if (code == 0) return "ابتدا وارد شوید."
        if (codeInvalid) return "کد اشتراک درست نیست. از تنظیمات خارج شوید و کد را دوباره وارد کنید."
        var est = Usage.estimateRemaining()
        if (user?.exhausted == true || (est != null && est <= 0.0)) { Usage.report(true); refreshUser(); est = Usage.estimateRemaining() }
        if (user?.exhausted == true || (est != null && est <= 0.0)) { exhaustDismissed = false; changed(); return "حجم اشتراک شما تمام شده است. از بخش «اشتراک» می‌توانید حجم بخرید." }
        return null
    }

    // ---- پیشرفت (صفحه آماده‌سازی) ----
    private fun pStart() { progress = Progress(System.currentTimeMillis(), listOf(PStep("code", "تأیید کد اشتراک"), PStep("fetch", "دریافت لیست کانفیگ‌ها"), PStep("test", "سنجش واقعی سرورها"), PStep("compat", "سازگارسازی با سرور"), PStep("pick", "انتخاب بهترین سرور"))); changed() }
    private fun pStep(id: String, st: String, detail: String? = null, pct: Int? = null) {
        val p = progress ?: return; p.steps.firstOrNull { it.id == id }?.let { it.st = st; if (detail != null) it.detail = detail }
        if (pct != null) p.pct = maxOf(p.pct, pct); changed()
    }
    private fun pEnd(ok: Boolean, name: String, core: Boolean) { val p = progress ?: return; p.active = false; p.done = true; p.pct = 100; p.ok = ok; p.name = name; p.core = core; changed() }

    private fun errMsg(e: Exception): String = if (e is ApiError) (if (e.code == 404) "کد اشتراک درست نیست." else (e.message ?: "خطا")) else "پاسخ سرور نامعتبر بود."

    // ورود با شناسه‌ی موجود: تأیید کد ← دریافت کانفیگ‌ها ← سنجش ← انتخاب. خروجی: متن خطا یا null
    suspend fun setupWithCode(c: Int): String? {
        pStart(); pStep("code", "active", "در حال بررسی کد…", 3)
        val u = try { withContext(Dispatchers.IO) { Api.user(c) } } catch (e: Exception) { progress = null; changed(); return errMsg(e) }
        return finishSetup(c, u, "کد تأیید شد")
    }

    // ورود بدون شناسه: حساب را سرور می‌سازد. اگر همین نصب قبلاً حساب ساخته باشد، همان دوباره استفاده می‌شود (حجم رایگان تکراری نمی‌گیرد)
    suspend fun setupAuto(): String? {
        pStart(); pStep("code", "active", "در حال ساخت حساب شما…", 3)
        try {
            val saved = prefs.getInt("autoCode", 0); var c = 0; var u: UserInfo? = null
            if (saved != 0) { try { u = withContext(Dispatchers.IO) { Api.user(saved) }; c = saved } catch (e: ApiError) { if (e.code != 404) throw e } }
            if (u == null) { val r = withContext(Dispatchers.IO) { Api.register() }; c = r.code; u = r.user; prefs.edit().putInt("autoCode", c).putBoolean("askInvite", !inviteDone).apply(); askInvite = !inviteDone }
            return finishSetup(c, u!!, "حساب آماده شد")
        } catch (e: Exception) { progress = null; changed(); return errMsg(e) }
    }

    private suspend fun finishSetup(c: Int, u: UserInfo, msg: String): String? {
        code = c; codeInvalid = false; applyUser(u); pStep("code", "done", msg, 8); variant = null
        val err = syncSubscriptions(true)
        if (err != null) { code = 0; user = null; progress = null; save(); changed(); return err }
        runTests(true); return null
    }

    // ---- دعوت دوستان ----
    suspend fun loadInviteBonus() = withContext(Dispatchers.IO) { try { inviteBonus = Api.inviteBonus(); changed() } catch (e: Exception) { } }
    fun dismissInvite() { askInvite = false; prefs.edit().putBoolean("askInvite", false).apply(); changed() }
    // ثبت کد دعوت‌کننده. خروجی: متن خطا یا null. هر نصب فقط یک بار می‌تواند کد دعوت ثبت کند
    suspend fun redeemInvite(inviter: Int): String? {
        if (inviter == code) return "نمی‌توانید کد خودتان را وارد کنید."
        if (inviteDone) return "قبلاً کد دعوت ثبت کرده‌اید."
        val err: String? = withContext(Dispatchers.IO) {
            try { Api.user(inviter); Api.invite(inviter); prefs.edit().putBoolean("inviteDone", true).apply(); null }
            catch (e: Exception) { if (e is ApiError && e.code == 404) "کد دعوت درست نیست." else if (e is ApiError) (e.message ?: "خطا") else "ثبت کد ناموفق بود." }
        }
        if (err == null) dismissInvite()
        return err
    }

    // در هر بار اجرای برنامه (نه فقط اولین بار) لیست لینک‌ها دوباره از سرور گرفته می‌شود
    @Volatile private var launchSynced = false
    suspend fun syncOnLaunch() {
        if (code == 0 || launchSynced || progress?.active == true) return
        launchSynced = true
        syncSubscriptions(false)
        if (servers.isNotEmpty()) runTests(false)
    }

    private fun applyServers(list: List<JSONObject>) {
        val ids = list.map { it.getString("uid") }.toSet()
        for (u in ids) numbers.getOrPut(u) { nextNumber++ } // شماره‌ی هر کانفیگ ثابت می‌ماند
        stats.keys.filter { it !in ids }.forEach { stats.remove(it) }
        servers = list
    }

    // کانفیگ‌ها از همه‌ی لینک‌های اشتراک سرور (GET /subscriptions/) گرفته و ادغام می‌شوند
    suspend fun syncSubscriptions(setup: Boolean): String? = withContext(Dispatchers.IO) {
        if (setup) pStep("fetch", "active", "در حال دریافت لینک‌ها از سرور…", 10)
        try {
            val links = Api.subscriptions()
            if (links.isEmpty()) throw UserError("هیچ لینک اشتراکی روی سرور ثبت نشده است.")
            val merged = LinkedHashMap<String, JSONObject>(); var failed = 0
            for ((i, l) in links.withIndex()) {
                if (setup) pStep("fetch", "active", "لینک ${i + 1} از ${links.size}", 10 + 10 * i / links.size)
                try { for (s in Parser.fetch(l).servers) merged[s.getString("uid")] = s } catch (e: Exception) { failed++; log("sub link failed: ${e.message}") }
            }
            if (merged.isEmpty()) throw UserError("هیچ کانفیگی از لینک‌ها دریافت نشد.")
            applyServers(merged.values.toList()); subError = if (failed > 0) "$failed لینک دریافت نشد" else null; subUpdated = System.currentTimeMillis(); save(); changed()
            if (setup) pStep("fetch", "done", "${servers.size} کانفیگ", 20)
            null
        } catch (e: Exception) {
            val m = if (e is UserError || e is ApiError) (e.message ?: "") else "دریافت کانفیگ‌ها ناموفق بود."
            if (servers.isNotEmpty()) { subError = m; save(); changed() }
            m
        }
    }

    // ---- سنجش پینگ (با گزارش پیشرفت برای راه‌اندازی اول) ----
    suspend fun runTests(setup: Boolean) {
        if (servers.isEmpty() || !testingFlag.compareAndSet(false, true)) return
        val n = servers.size; var done = 0; var coreErr = false; var anyOk = false
        for (s in servers) { val u = s.optString("uid"); if (stats[u] == null) stats[u] = Stat("testing", null, 0) }
        if (setup) pStep("test", "active", "0 از $n", 22); changed()
        val handle = { s: JSONObject, r: TestRes, track: Boolean ->
            val u = s.optString("uid"); val old = stats[u]
            if (r.ms != null) { stats[u] = Stat("ok", r.ms, System.currentTimeMillis(), 0); anyOk = true }
            else { if (r.reason == "core") coreErr = true; log("test failed: ${r.reason} ${r.detail ?: ""}")
                stats[u] = Stat("fail", null, System.currentTimeMillis(), (old?.fails ?: 0) + 1, old?.badUntil ?: 0, r.reason, r.tcp) }
            if (track && setup) { done++; pStep("test", "active", "$done از $n", 22 + 33 * done / n) }
            changed()
        }
        try {
            Monitor.testAll(app, servers, variant) { s, r -> handle(s, r, true) }
            if (!anyOk && !coreErr) {
                discovering = true
                if (setup) { pStep("test", "done", "$n سرور بررسی شد؛ هیچ‌کدام ترافیک عبور ندادند", 55); pStep("compat", "active", "در حال امتحان حالت‌های سازگاری…", 57) }; changed()
                val v = Monitor.discover(app, servers) { i, total, vv -> if (setup) pStep("compat", "active", "مرحله ${minOf(i + 1, total)} از $total — ${VLABEL[vv]}", 55 + 35 * i / total) }
                discovering = false
                if (v != null) {
                    variant = v; log("compat variant: $v")
                    if (setup) { pStep("compat", "done", VLABEL[v], 90); pStep("pick", "active", "بررسی دوباره سرورها با حالت سازگار…", 92) }
                    Monitor.testAll(app, servers, v) { s, r -> handle(s, r, false) }
                } else if (setup) pStep("compat", "done", "حالت سازگار پیدا نشد", 90)
            } else if (setup) { pStep("test", "done", "$n سرور بررسی شد", 90); pStep("compat", "skipped", "لازم نبود", 90) }
        } catch (e: Exception) { log("tests crashed: ${e.message}") }
        discovering = false; testingFlag.set(false); lastCheck = System.currentTimeMillis(); save()
        if (setup) {
            val s = server(recommendedId()); pStep("pick", "done", s?.optString("name") ?: "هیچ سرور سالمی تأیید نشد", 99); pEnd(s != null, s?.optString("name") ?: "", coreErr)
        }
        changed()
    }

    // گزارش تشخیصی (بدون آدرس سرورها)
    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        val L = ArrayList<String>(); L.add("Nova Connect ${versionName} (${versionCode}) android ${android.os.Build.VERSION.SDK_INT} ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
        val bin = File(app.applicationInfo.nativeLibraryDir, "libxray.so"); L.add("xray file: " + if (bin.exists()) "OK ${bin.length()} bytes" else "MISSING")
        L.add("native dir: " + (File(app.applicationInfo.nativeLibraryDir).list()?.joinToString() ?: "?"))
        L.add("native dir: " + (File(app.applicationInfo.nativeLibraryDir).list()?.joinToString(", ") ?: "unreadable"))
        if (bin.exists()) try { val p = ProcessBuilder(bin.path, "version").redirectErrorStream(true).start(); p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS); L.add("xray: " + p.inputStream.bufferedReader().readLine()) } catch (e: Exception) { L.add("xray version FAIL ${e.message}") }
        val s = server(defaultId()) ?: return@withContext (L + "no servers").joinToString("\n")
        L.add("server: ${s.optString("protocol")} net=${s.optString("network")} security=${s.optString("tls")}")
        for (v in listOf<String?>(null) + XrayConfig.VARIANTS) {
            val r = Monitor.once(app, XrayConfig.applyVariant(s, v)); L.add("variant ${v ?: "default"} -> " + if (r.ms != null) "OK ${r.ms} ms" else "FAIL ${r.reason} ${r.detail ?: ""}")
        }
        L.joinToString("\n").replace(s.optString("address"), "<addr>")
    }
}
