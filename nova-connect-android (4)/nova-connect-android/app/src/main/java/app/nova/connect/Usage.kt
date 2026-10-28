package app.nova.connect

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

// شمارش مصرف این نصب (تجمعی) و گزارش به بک‌اند
object Usage {
    const val GB = 1024.0 * 1024.0 * 1024.0
    private const val MB = 1024L * 1024L
    private lateinit var p: SharedPreferences
    private val lock = Any()
    private val mutex = Mutex()
    @Volatile var cum = 0L; @Volatile var reported = 0L; @Volatile private var dirty = false
    @Volatile var failures = 0; @Volatile var lastOkAt = 0L; @Volatile private var nextTry = 0L
    lateinit var deviceId: String

    fun init(ctx: Context) {
        p = ctx.getSharedPreferences("usage", 0); cum = p.getLong("cum", 0); reported = p.getLong("rep", 0)
        var d = p.getString("dev", null)
        if (d == null) { d = UUID.randomUUID().toString(); p.edit().putString("dev", d).commit() }
        deviceId = d
    }
    fun add(delta: Long) { if (delta > 0) synchronized(lock) { cum += delta; dirty = true } }
    fun persist() { synchronized(lock) { if (dirty) { p.edit().putLong("cum", cum).putLong("rep", reported).commit(); dirty = false } } }
    // مصرف گزارش‌نشده‌ی حساب قبلی به حساب جدید منتقل نشود
    fun discardPending() { synchronized(lock) { reported = cum; dirty = true }; persist() }
    fun unreported(): Long = maxOf(0L, cum - reported)
    // مانده‌ی تخمینی محلی؛ بدون پاسخ سرور null است (در UI خط تیره)
    fun estimateRemaining(): Double? { val u = Core.user ?: return null; return maxOf(0.0, u.total - u.used - unreported() / GB) }
    val unsynced: Boolean get() = unreported() > 0 && failures > 0

    suspend fun report(force: Boolean = false): Boolean {
        if (Core.code == 0 || Core.codeInvalid) return false
        val now = System.currentTimeMillis(); if (!force && now < nextTry) return false
        return mutex.withLock {
            val snap = cum; if (snap <= reported) return@withLock true
            try {
                val r = Api.report(Core.code, deviceId, snap)
                synchronized(lock) { if (snap > reported) { reported = snap; dirty = true } }
                persist(); failures = 0; lastOkAt = now; Core.applyUser(r); true
            } catch (e: ApiError) {
                failures++; nextTry = now + minOf(30_000L shl minOf(failures, 5), 600_000L); Core.log("report failed: ${e.message}")
                if (e.code == 404) { Core.onAccountDeleted() }
                Core.changed(); false
            }
        }
    }

    suspend fun loop() {
        var lastReport = 0L; var lastPersist = 0L; var first = true
        while (true) {
            delay(2000); val now = System.currentTimeMillis()
            if (now - lastPersist >= 10_000) { persist(); lastPersist = now }
            if (Core.code == 0 || Core.codeInvalid) continue
            val connected = Core.conn.status == "connected"; val un = unreported(); val est = estimateRemaining()
            val interval = if (est != null && est < 1.0) 30_000L else 300_000L
            val due = un > 0 && ((connected && now - lastReport >= interval) || un >= 100 * MB || first || (!connected && now - lastReport >= 60_000L))
            if (due) { report(); lastReport = now; first = false }
            val e2 = estimateRemaining()
            if (connected && e2 != null && e2 <= 0.0) { report(true); NovaVpnService.instance?.quotaOut() }
        }
    }
}
