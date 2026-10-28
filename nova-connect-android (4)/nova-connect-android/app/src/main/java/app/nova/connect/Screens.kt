package app.nova.connect

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder

private val STATUS = mapOf("idle" to "متصل نیست", "connecting" to "در حال اتصال", "connected" to "متصل", "reconnecting" to "در حال اتصال مجدد", "switching" to "در حال تغییر سرور", "error" to "خطا در اتصال")
fun pingText(s: Stat?): String = when {
    s == null -> "نامشخص"; s.status == "testing" -> "در حال بررسی"
    s.status == "ok" && s.latency != null -> fa("${s.latency}") + " ms"; s.status == "fail" -> "ناموفق"; else -> "نامشخص"
}
fun gb(v: Double?): String = if (v == null) "—" else fa("%.2f".format(v))
fun toast(ctx: android.content.Context, m: String) { android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(ctx, m, Toast.LENGTH_LONG).show() } }

@Composable fun Card(modifier: Modifier = Modifier, borderColor: Color = C.line, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.glass).border(1.dp, borderColor, RoundedCornerShape(20.dp)).padding(14.dp).animateContentSize()) { rt(); content() }
}

// ---------------- خوش‌آمد: ورود خودکار یا ورود با شناسه ----------------
@Composable fun WelcomeScreen(error: String?, busy: Boolean, onAuto: () -> Unit, onCode: (String) -> Unit) {
    rt(); var text by remember { mutableStateOf("") }; var showCode by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val inf = rememberInfiniteTransition(label = "lg"); val fl by inf.animateFloat(-8f, 8f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "f")
        androidx.compose.foundation.Image(painterResource(R.drawable.ic_logo), null, Modifier.size(120.dp).offset(y = fl.dp))
        Spacer(Modifier.height(14.dp)); NText("Nova Connect", 30, C.acc3, true)
        Spacer(Modifier.height(6.dp)); NText("اتصال سریع و امن، با یک لمس.", 14, C.muted, align = TextAlign.Center)
        Spacer(Modifier.height(30.dp))
        GlassButton(if (busy && !showCode) "در حال ساخت حساب…" else "ورود", Modifier.fillMaxWidth(), true, !busy) { onAuto() }
        Spacer(Modifier.height(6.dp)); NText("یک حساب جدید با حجم اولیه برای شما ساخته می‌شود.", 11, C.muted, align = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        GlassButton(if (showCode) "بستن" else "از قبل شناسه کاربری دارم", Modifier.fillMaxWidth(), enabled = !busy) { showCode = !showCode }
        AnimatedVisibility(showCode) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(12.dp)); NText("شناسه ۶ رقمی خود را وارد کنید.", 13, C.muted)
                Spacer(Modifier.height(8.dp))
                BasicTextField(text, { v -> text = v.filter { it.isDigit() }.take(6) }, textStyle = TextStyle(color = C.text, fontSize = 26.sp, textAlign = TextAlign.Center, letterSpacing = 6.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, cursorBrush = SolidColor(C.acc),
                    modifier = Modifier.fillMaxWidth().glass(16.dp).padding(16.dp))
                Spacer(Modifier.height(10.dp))
                GlassButton(if (busy) "در حال بررسی…" else "ورود با شناسه", Modifier.fillMaxWidth(), true, !busy && text.length == 6) { onCode(text) }
            }
        }
        if (error != null) { Spacer(Modifier.height(12.dp)); NText(error, 13, C.bad, align = TextAlign.Center) }
    }
}

// ---------------- صفحه پیشرفت (آماده‌سازی) ----------------
@Composable fun ProgressScreen(onHide: () -> Unit, onDone: () -> Unit, onDiag: () -> Unit) {
    rt(); val p = Core.progress ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val tips = listOf("اولین اجرا کمی طول می‌کشد؛ هر سرور واقعاً با ترافیک آزمایش می‌شود، نه فقط پینگ.", "اگر سروری با تنظیمات معمولی کار نکند، برنامه خودش حالت‌های سازگار را امتحان می‌کند.", "این مرحله فقط یک بار انجام می‌شود و نتیجه ذخیره می‌شود.", "می‌توانید «ادامه در پس‌زمینه» را بزنید؛ کار متوقف نمی‌شود.")
    var tip by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(6000); tip = (tip + 1) % tips.size } }
    LaunchedEffect(p.done, p.ok) { if (p.done && p.ok) { delay(2600); onDone() } }
    val pct by animateFloatAsState(p.pct / 100f, tween(700), label = "pct")
    val inf = rememberInfiniteTransition(label = "po"); val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "ps")
    val sec = ((if (p.done) now else now) - p.startedAt) / 1000
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(20.dp))
        Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                if (!p.done) rotate(spin) { drawArc(Brush.sweepGradient(listOf(Color.Transparent, C.acc2)), 0f, 290f, false, style = Stroke(8f, cap = StrokeCap.Round)) }
                else drawCircle(C.acc.copy(alpha = 0.3f), size.minDimension / 2, style = Stroke(6f))
            }
            androidx.compose.foundation.Image(painterResource(R.drawable.ic_logo), null, Modifier.size(78.dp))
        }
        Spacer(Modifier.height(14.dp))
        NText(if (!p.done) "در حال آماده‌سازی…" else if (p.ok) "همه‌چیز آماده است ✓" else "سروری تأیید نشد", 20, C.text, true)
        Spacer(Modifier.height(6.dp))
        NText(if (!p.done) "برنامه سرورها را یکی‌یکی با ترافیک واقعی آزمایش می‌کند." else if (p.ok) "بهترین سرور: ${p.name}" else if (p.core) "هسته اجرا نشد؛ «تشخیص مشکل» را بزنید." else "هیچ سروری ترافیک عبور نداد؛ «تشخیص مشکل» علت را نشان می‌دهد.", 13, C.muted, align = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(14.dp)).background(C.line)) { Box(Modifier.fillMaxHeight().fillMaxWidth(pct).clip(RoundedCornerShape(14.dp)).background(Brush.horizontalGradient(listOf(C.acc, C.acc3)))) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { NText(fa("${p.pct}") + "٪", 18, C.text, true); NText("زمان سپری‌شده: " + fa("${sec / 60}:${"%02d".format(sec % 60)}"), 12, C.muted) }
        if (p.done && p.ok) { Spacer(Modifier.height(12.dp)); Card(borderColor = C.acc) { NText("شناسه کاربری شما", 11, C.muted); NText(fa("${Core.code}"), 26, C.acc3, true); NText("این شناسه را یادداشت کنید؛ برای ورود مجدد لازم است.", 11, C.muted) } }
        Spacer(Modifier.height(14.dp))
        Card { p.steps.forEach { s -> StepRow(s) } }
        Spacer(Modifier.height(10.dp)); NText(tips[tip], 12, C.muted, align = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!p.done) GlassButton("ادامه در پس‌زمینه") { onHide() }
            if (p.done && !p.ok) GlassButton("تشخیص مشکل") { onDiag() }
            if (p.done) GlassButton("ورود به برنامه", primary = true) { onDone() }
        }
    }
}

@Composable fun StepRow(s: PStep) {
    val inf = rememberInfiniteTransition(label = "st"); val r by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "sr")
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (s.st == "done") C.acc2 else Color.Transparent)
            .border(2.dp, if (s.st == "done" || s.st == "active") C.acc2 else C.line, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            if (s.st == "done") NText("✓", 13, C.dark, true)
            if (s.st == "active") Canvas(Modifier.size(14.dp)) { rotate(r) { drawArc(C.acc2, 0f, 250f, false, style = Stroke(3f, cap = StrokeCap.Round)) } }
        }
        Column(Modifier.weight(1f)) {
            NText(s.label, 14, if (s.st == "pending" || s.st == "skipped") C.muted else C.text, true)
            if (s.detail.isNotEmpty()) NText(fa(s.detail), 12, C.muted)
        }
    }
}

// ---------------- خانه ----------------
@Composable fun HomeScreen(onConnect: (String?) -> Unit, onAuto: (String?) -> Unit, onDisconnect: () -> Unit, goSub: () -> Unit, goServers: () -> Unit, onDiag: () -> Unit) {
    rt(); val c = Core.conn; val st = c.status
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val busy = st == "connecting" || st == "reconnecting" || st == "switching"
    val state = if (st == "connected") "connected" else if (busy) "busy" else if (st == "error") "error" else "idle"
    val cur = Core.server(c.currentId) ?: Core.server(Core.defaultId()); val rec = Core.server(Core.recommendedId())
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PowerButton(state) { if (st == "idle" || (st == "error" && c.currentId == null)) onAuto(null) else if (st == "error") onAuto(c.currentId) else onDisconnect() }
        Row(Modifier.clip(RoundedCornerShape(30.dp)).background(C.glass).border(1.dp, C.line, RoundedCornerShape(30.dp)).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(9.dp).clip(androidx.compose.foundation.shape.CircleShape).background(when (state) { "connected" -> C.acc2; "busy" -> C.warn; "error" -> C.bad; else -> C.muted }))
            NText(if (Core.testing && st == "idle") (if (Core.discovering) "در حال سازگارسازی با سرور" else "در حال بررسی سرورها") else STATUS[st] ?: "", 15, C.text, true)
        }
        val msg = c.message.ifEmpty { if (st == "idle") "برای اتصال روی دکمه بزنید" else "" }
        if (msg.isNotEmpty()) NText(fa(msg), 12, C.muted, align = TextAlign.Center)
        if (Core.coreIssue) Card(borderColor = C.bad) { NText("هسته Xray اجرا نمی‌شود", 14, C.bad, true); NText("«تشخیص مشکل» علت دقیق را نشان می‌دهد.", 12, C.muted); Spacer(Modifier.height(6.dp)); GlassButton("تشخیص مشکل") { onDiag() } }
        if (busy) Card(borderColor = C.acc) { // فرایند اتصال: کاربر می‌بیند برای چه چیزی منتظر است
            val labels = listOf("راه‌اندازی هسته Xray", "آزمون عبور ترافیک از سرور", "ساخت تونل VPN", "متصل شدن")
            labels.forEachIndexed { i, l -> StepRow(PStep("c$i", l, if (c.step > i + 1 || (c.step == 0 && false)) "done" else if (c.step == i + 1) "active" else "pending", "")) }
        }
        Card(borderColor = Color(0x8034D399)) { // بهترین سرور، بالاتر از سرور فعلی
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Tag("⚡ بهترین سرور", C.acc3, true)
                if (rec != null && !(st == "connected" && cur != null && rec.optString("uid") == cur.optString("uid"))) AttentionButton("اتصال به این") { onConnect(rec.optString("uid")) }
            }
            Spacer(Modifier.height(8.dp))
            NText(if (rec != null) "#${fa("${Core.numOf(rec.optString("uid"))}")}  ${rec.optString("name")}" else if (Core.testing) "در حال بررسی…" else "سرور سالمی تأیید نشد", 15, C.text, true, maxLines = 1)
            if (rec != null) NText(pingText(Core.stats[rec.optString("uid")]), 12, C.acc2, true)
        }
        Card {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Tag("سرور فعلی", C.acc2)
                NText(if (st == "connected") "متصل" else if (cur != null && cur.optString("uid") == Core.defaultId()) "آخرین سرور انتخابی" else "", 12, C.muted)
            }
            Spacer(Modifier.height(8.dp))
            NText(if (cur != null) "#${fa("${Core.numOf(cur.optString("uid"))}")}  ${cur.optString("name")}" else "—", 15, C.text, true, maxLines = 1)
            NText(if (cur != null) pingText(Core.stats[cur.optString("uid")]) else "—", 12, C.acc2, true)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val dur = if (st == "connected" && c.since > 0) { val s = (now - c.since) / 1000; fa("%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)) } else "—"
            fun sp(b: Double): String = if (st != "connected" || b < 0) "—" else if (b >= 1048576) fa("%.1f".format(b / 1048576)) + " MB/s" else fa("${Math.round(b / 1024)}") + " KB/s"
            for ((l, v) in listOf("مدت اتصال" to dur, "دانلود" to sp(c.down), "آپلود" to sp(c.up))) Column(Modifier.weight(1f).glass(16.dp).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) { NText(l, 11, C.muted); NText(v, 13, C.text, true) }
        }
        QuotaCard(goSub)
        SpeedChart()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable fun QuotaCard(onClick: () -> Unit) {
    rt(); val u = Core.user; val est = Usage.estimateRemaining()
    Card(Modifier.clickable { onClick() }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { NText("حجم باقی‌مانده", 11, C.muted); if (Usage.unsynced) NText("مصرف همگام نشده", 11, C.warn) }
        val rem0 = est ?: u?.remaining
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            NText("${gb(rem0)} گیگ", 24, if (est != null && est < 1.0) C.warn else C.acc3, true)
            if (u != null && u.total > 0 && rem0 != null) { val pc = pctOf(rem0, u.total); Tag(fa("$pc") + "٪ باقی‌مانده", if (pc < 15) C.warn else C.acc2) }
        }
        if (u != null && u.total > 0) {
            val frac by animateFloatAsState(((est ?: u.remaining) / u.total).toFloat().coerceIn(0f, 1f), tween(700), label = "q")
            Spacer(Modifier.height(6.dp)); Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)).background(C.line)) { Box(Modifier.fillMaxHeight().fillMaxWidth(frac).clip(RoundedCornerShape(8.dp)).background(Brush.horizontalGradient(listOf(C.acc, C.acc3)))) }
            Spacer(Modifier.height(4.dp)); NText("از ${gb(u.total)} گیگ کل", 11, C.muted)
        }
    }
}

// ---------------- سرورها ----------------
@Composable fun ServersScreen(onConnect: (String?) -> Unit) {
    rt(); val ctx = LocalContext.current
    val list = Core.servers.sortedBy { Core.stats[it.optString("uid")]?.let { s -> if (s.status == "ok") s.latency else null } ?: Int.MAX_VALUE }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(if (Core.testing) "در حال بررسی…" else "بررسی پینگ", enabled = !Core.testing) { Core.scope.launch { Core.runTests(false) } }
            GlassButton("به‌روزرسانی") { Core.scope.launch { val e = Core.syncSubscriptions(false); toast(ctx, e ?: "لیست به‌روزرسانی شد"); if (e == null) Core.runTests(false) } }
        }
        Spacer(Modifier.height(6.dp))
        val t = if (Core.lastCheck > 0) java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(Core.lastCheck)) else "هنوز بررسی نشده"
        NText(fa("${Core.servers.size} کانفیگ · آخرین بررسی: $t") + (Core.subError?.let { " · $it" } ?: ""), 11, C.muted)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(list, key = { it.optString("uid") }) { s ->
                val uid = s.optString("uid"); val stat = Core.stats[uid]; val live = Core.conn.status == "connected" && Core.conn.currentId == uid
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.glass).border(1.dp, if (live) C.acc else C.line, RoundedCornerShape(16.dp)).padding(12.dp).animateItemPlacement(),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x2610B981)), contentAlignment = Alignment.Center) { NText(fa("${Core.numOf(uid)}"), 13, C.acc3, true) }
                    Column(Modifier.weight(1f)) {
                        NText(s.optString("name"), 14, C.text, true, maxLines = 1)
                        val tags = listOfNotNull(if (live) "فعلی" else null, if (uid == Core.recommendedId()) "پیشنهادی" else null, s.optString("protocol"), if (stat?.status == "fail") (if (stat.reason == "core") "هسته اجرا نشد" else if (stat.tcp) "سرور باز است ولی ترافیک عبور نکرد" else "پاسخ نداد") else null)
                        NText(tags.joinToString(" · "), 11, C.muted, maxLines = 1)
                    }
                    SignalBars(stat); NText(pingText(stat), 11, if (stat?.status == "ok") C.acc2 else if (stat?.status == "fail") C.bad else C.muted)
                    GlassButton("اتصال") { onConnect(uid) }
                }
            }
        }
    }
}

// ---------------- اشتراک ----------------
@Composable fun SubscriptionScreen() {
    rt(); val ctx = LocalContext.current; val u = Core.user; val est = Usage.estimateRemaining()
    var gigs by remember { mutableStateOf("") }; val price = Core.priceUnit
    LaunchedEffect(Unit) { Core.loadPrice(); Core.refreshUser(); Core.loadInviteBonus() }
    val g = gigs.replace('،', '.').replace(',', '.').toDoubleOrNull() ?: 0.0; val total = if (price != null) Math.round(g * price) else null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val remaining = est ?: u?.remaining ?: 0.0; val frac by animateFloatAsState(if (u != null && u.total > 0) (remaining / u.total).toFloat().coerceIn(0f, 1f) else 0f, tween(900), label = "ring")
        Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                drawArc(C.line, 0f, 360f, false, style = Stroke(22f, cap = StrokeCap.Round))
                drawArc(Brush.sweepGradient(listOf(C.acc, C.acc3, C.acc)), -90f, 360f * frac, false, style = Stroke(22f, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { NText(gb(est ?: u?.remaining), 30, if (remaining < 1.0) C.warn else C.acc3, true); NText("گیگ باقی‌مانده", 12, C.muted); if (u != null && u.total > 0) NText(fa("${pctOf(remaining, u.total)}") + "٪ باقی‌مانده", 12, C.acc2, true) }
        }
        Card {
            for ((l, v) in listOf("کد اشتراک" to fa("${Core.code}"), "حجم کل" to "${gb(u?.total)} گیگ", "مصرف‌شده" to "${gb(u?.used)} گیگ", "قیمت هر گیگ" to if (price != null) fa("%,d".format(price)) + " تومان" else "—")) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) { NText(l, 13, C.muted); NText(v, 14, if (l == "کد اشتراک") C.acc3 else C.text, true) }
            }
            val sync = if (Core.userSyncedAt > 0) "آخرین همگام‌سازی: " + java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(Core.userSyncedAt)) else "هنوز همگام نشده"
            NText(fa(sync) + if (Usage.unsynced) " · مصرف همگام نشده" else "", 11, if (Usage.unsynced) C.warn else C.muted)
            Spacer(Modifier.height(8.dp)); GlassButton(if (Core.codeInvalid) "کد معتبر نیست؛ از تنظیمات خارج شوید" else "به‌روزرسانی وضعیت", Modifier.fillMaxWidth()) { Core.scope.launch { toast(ctx, if (Core.refreshUser()) "وضعیت به‌روز شد" else "دریافت وضعیت ناموفق بود") } }
        }
        Card(borderColor = Color(0x7310B981)) {
            NText("خرید حجم", 15, C.text, true); Spacer(Modifier.height(4.dp)); NText("مقدار گیگ مورد نظر را بنویسید تا قیمت را ببینید.", 12, C.muted); Spacer(Modifier.height(8.dp))
            BasicTextField(gigs, { v -> gigs = v.filter { it.isDigit() || it == '.' || it == ',' || it == '،' }.take(7) }, textStyle = TextStyle(color = C.text, fontSize = 22.sp, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, cursorBrush = SolidColor(C.acc),
                decorationBox = { inner -> Box(Modifier.fillMaxWidth().glass(14.dp).padding(14.dp), contentAlignment = Alignment.Center) { if (gigs.isEmpty()) NText("مثلاً ۱۰", 18, C.muted); inner() } })
            Spacer(Modifier.height(8.dp))
            AnimatedVisibility(g > 0 && total != null) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { NText("مبلغ قابل پرداخت", 13, C.muted); NText(fa("%,d".format(total ?: 0)) + " تومان", 16, C.acc3, true) } }
            if (price == null) NText("قیمت از سرور دریافت نشد؛ اینترنت را بررسی کنید.", 11, C.warn)
            Spacer(Modifier.height(8.dp))
            GlassButton("ارسال درخواست خرید در تلگرام", Modifier.fillMaxWidth(), true, g > 0 && total != null) {
                openTelegram(ctx, "سلام، می‌خواهم حجم بخرم.\nکد اشتراک: ${Core.code}\nمقدار: ${gigs} گیگ\nمبلغ: ${total} تومان")
            }
            Spacer(Modifier.height(10.dp)); SupportNote()
            Spacer(Modifier.height(4.dp)); NText("پرداخت داخل برنامه انجام نمی‌شود؛ پیام برای Nova ارسال می‌شود و بعد از تأیید، حجم به حسابتان اضافه می‌شود.", 11, C.muted)
        }
        InviteCard()
        Spacer(Modifier.height(8.dp))
    }
}

fun pctOf(rem: Double, total: Double): Int = (rem / total * 100).toInt().coerceIn(0, 100)
fun speedText(b: Double): String = if (b >= 1048576) fa("%.1f".format(b / 1048576)) + " MB/s" else fa("${Math.round(b / 1024)}") + " KB/s"

fun copyRaw(ctx: android.content.Context, label: String, text: String) {
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

// باز کردن تلگرام: اول مستقیم با خود برنامه (tg://) تا صفحه‌ی خطای مرورگر باز نشود؛ بعد لینک t.me. متن پیام همیشه کپی می‌شود
fun openTelegram(ctx: android.content.Context, msg: String) {
    copyRaw(ctx, "msg", msg)
    val enc = URLEncoder.encode(msg, "UTF-8").replace("+", "%20")
    for (u in listOf("tg://resolve?domain=Nova_2585&text=$enc", "https://t.me/Nova_2585?text=$enc")) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))); toast(ctx, "متن پیام کپی شد؛ اگر خودکار وارد نشد، آن را پیست کنید."); return } catch (e: Exception) { }
    }
    toast(ctx, "تلگرام باز نشد. متن پیام کپی شد؛ به آیدی @Nova_2585 مراجعه کنید.")
}

@Composable fun SupportNote() {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x26FBBF24)).border(1.dp, Color(0x80FBBF24), RoundedCornerShape(14.dp))
        .clickable { copyRaw(ctx, "id", "@Nova_2585"); toast(ctx, "آیدی کپی شد") }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NText("⚠️", 20, C.warn)
        Column { NText("اگر ارسال به تلگرام مشکل داشت", 12, C.warn, true); NText("به آیدی @Nova_2585 مراجعه کنید (برای کپی، لمس کنید).", 12, C.text) }
    }
}

// نمودار زنده‌ی سرعت دانلود (زیر باکس حجم باقی‌مانده)
@Composable fun SpeedChart() {
    rt(); val pts = synchronized(Core.speedHist) { Core.speedHist.toList() }
    Column(Modifier.fillMaxWidth().glass(20.dp).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { NText("سرعت دانلود", 11, C.muted); if (pts.isNotEmpty()) NText(speedText(pts.last()), 11, C.acc2, true) }
        Box(Modifier.fillMaxWidth().height(64.dp)) {
            if (pts.size < 2) NText("پس از اتصال، نمودار زنده نمایش داده می‌شود.", 11, C.muted, modifier = Modifier.align(Alignment.Center))
            else Canvas(Modifier.fillMaxSize()) {
                val mx = maxOf(pts.max(), 50.0 * 1024); val w = size.width; val h = size.height; val step = w / (pts.size - 1)
                val line = Path()
                pts.forEachIndexed { i, v -> val x = i * step; val y = h - 4f - (v / mx).toFloat() * (h - 8f); if (i == 0) line.moveTo(x, y) else line.lineTo(x, y) }
                val area = Path().apply { addPath(line); lineTo(w, h); lineTo(0f, h); close() }
                drawPath(area, Brush.verticalGradient(listOf(C.acc2.copy(alpha = 0.35f), Color.Transparent)))
                drawPath(line, C.acc2, style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

// جعبه‌ی بسته‌شدنی «اتمام حجم» با دکمه‌ی افزایش حجم
@Composable fun QuotaSheet(onClose: () -> Unit, onBuy: () -> Unit) {
    val none = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(indication = null, interactionSource = none) { onClose() }, contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(0.88f).clip(RoundedCornerShape(28.dp)).background(Brush.verticalGradient(listOf(Color(0xFF10211A), Color(0xFF0A1410))))
            .border(1.dp, Color(0x66FBBF24), RoundedCornerShape(28.dp)).clickable(indication = null, interactionSource = none) { }.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth()) { Spacer(Modifier.weight(1f)); Box(Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape).background(C.glass).clickable { onClose() }, contentAlignment = Alignment.Center) { NText("✕", 15, C.muted) } }
            NText("⏳", 40, C.warn); Spacer(Modifier.height(6.dp)); NText("حجم شما تمام شد", 20, C.text, true)
            Spacer(Modifier.height(6.dp)); NText("برای ادامه‌ی اتصال، حجم جدید تهیه کنید. فقط چند لحظه طول می‌کشد.", 13, C.muted, align = TextAlign.Center)
            Spacer(Modifier.height(18.dp)); GlassButton("افزایش حجم", Modifier.fillMaxWidth(), true) { onBuy() }
        }
    }
}

fun bonusText(): String = Core.inviteBonus?.let { if (it % 1.0 == 0.0) fa("${it.toInt()}") else fa("%.2f".format(it)) } ?: "مقداری"

fun copyText(ctx: android.content.Context, label: String, text: String) {
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text)); toast(ctx, "کد دعوت کپی شد")
}

fun shareInvite(ctx: android.content.Context) {
    Core.scope.launch {
        if (Core.update?.link.isNullOrEmpty()) Core.checkUpdate()
        val link = Core.update?.link ?: ""
        val text = "سلام! با Nova Connect به اینترنت سریع و امن وصل شو 🚀\n" + (if (link.isNotEmpty()) "دانلود آخرین نسخه: $link\n" else "") +
            "کد دعوت من: ${Core.code}\nبعد از ورود، کد دعوت را وارد کن."
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "دعوت از طریق")) }
            catch (e: Exception) { toast(ctx, "اشتراک‌گذاری ممکن نشد.") }
        }
    }
}

@Composable fun InviteCard() {
    rt(); val ctx = LocalContext.current
    Card(borderColor = Color(0x7334D399)) {
        NText("دعوت دوستان", 15, C.text, true); Spacer(Modifier.height(4.dp))
        NText("با هر نفری که با کد شما وارد برنامه شود و کد را ثبت کند، ${bonusText()} گیگ هدیه به حجم شما اضافه می‌شود.", 12, C.muted)
        Spacer(Modifier.height(10.dp))
        GlassButton("دعوت", Modifier.fillMaxWidth(), true) { shareInvite(ctx) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x1F10B981)).border(1.dp, Color(0x5534D399), RoundedCornerShape(14.dp)).clickable { copyText(ctx, "invite", "${Core.code}") }.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically) {
            NText("کد دعوت شما", 12, C.muted, modifier = Modifier.weight(1f)); NText(fa("${Core.code}"), 20, C.acc3, true); Spacer(Modifier.width(8.dp)); NText("کپی", 11, C.acc2, true)
        }
    }
}

// صفحه‌ی کشویی «کد دعوت داری؟» بعد از ساخت حساب جدید (اختیاری)
@Composable fun InviteSheet() {
    rt(); val ctx = LocalContext.current; var text by remember { mutableStateOf("") }; var err by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    val none = remember { MutableInteractionSource() }
    LaunchedEffect(Unit) { Core.loadInviteBonus() }
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(indication = null, interactionSource = none) { }, contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)).background(Brush.verticalGradient(listOf(Color(0xFF10211A), Color(0xFF0A1410))))
            .border(1.dp, Color(0x5510B981), RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)).clickable(indication = null, interactionSource = none) { }.padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(42.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.line))
            Spacer(Modifier.height(16.dp)); NText("کد دعوت داری؟", 20, C.acc3, true)
            Spacer(Modifier.height(6.dp)); NText("این مرحله اختیاری است. اگر کسی تو را دعوت کرده، کدش را وارد کن تا ${bonusText()} گیگ هدیه به او برسد.", 13, C.muted, align = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            BasicTextField(text, { v -> text = v.filter { it.isDigit() }.take(6); err = null }, textStyle = TextStyle(color = C.text, fontSize = 24.sp, textAlign = TextAlign.Center, letterSpacing = 6.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, cursorBrush = SolidColor(C.acc), modifier = Modifier.fillMaxWidth().glass(16.dp).padding(14.dp))
            if (err != null) { Spacer(Modifier.height(8.dp)); NText(err!!, 12, C.bad, align = TextAlign.Center) }
            Spacer(Modifier.height(14.dp))
            GlassButton(if (busy) "در حال ثبت…" else "ثبت کد دعوت", Modifier.fillMaxWidth(), true, !busy && text.length == 6) {
                busy = true; Core.scope.launch { val e = Core.redeemInvite(text.toInt()); busy = false; if (e != null) err = e else toast(ctx, "کد دعوت ثبت شد. ممنون!") }
            }
            Spacer(Modifier.height(8.dp)); GlassButton("کد دعوت ندارم", Modifier.fillMaxWidth(), enabled = !busy) { Core.dismissInvite() }
            Spacer(Modifier.height(10.dp))
        }
    }
}

// ---------------- تنظیمات ----------------
@Composable fun SettingsScreen(onShowText: (String, String) -> Unit, onCheckUpdate: () -> Unit, onLogout: () -> Unit) {
    rt(); var confirmLogout by remember { mutableStateOf(false) }
    @Composable fun Row2(label: String, content: @Composable () -> Unit) { Row(Modifier.fillMaxWidth().glass(16.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically) { rt(); NText(label, 14, C.text, modifier = Modifier.weight(1f)); content() } }
    @Composable fun Choices(opts: List<Int>, cur: Int, set: (Int) -> Unit) { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { opts.forEach { o -> Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (o == cur) C.acc else C.line).clickable { set(o); Core.changed() }.padding(horizontal = 12.dp, vertical = 6.dp)) { NText(fa("$o"), 13, if (o == cur) C.dark else C.text, o == cur) } } } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row2("اتصال خودکار هنگام باز کردن برنامه") { Toggle(Core.autoConnect) { Core.autoConnect = it; Core.changed() } }
        Row2("فاصله بررسی پینگ (ثانیه)") { Choices(listOf(30, 45, 60, 120), Core.pingInterval) { Core.pingInterval = it } }
        Row2("تعداد تلاش اتصال مجدد") { Choices(listOf(2, 3, 4, 5), Core.retries) { Core.retries = it } }
        if (Core.variant != null) Row2("حالت سازگاری فعال: ${Core.VLABEL[Core.variant] ?: ""}") { GlassButton("بازنشانی") { Core.variant = null; Core.save(); Core.scope.launch { Core.runTests(false) } } }
        GlassButton("بررسی نسخه جدید", Modifier.fillMaxWidth()) { onCheckUpdate() }
        GlassButton("تشخیص مشکل اتصال", Modifier.fillMaxWidth()) { Core.scope.launch { onShowText("تشخیص مشکل", "در حال بررسی… (تا حدود ۳۰ ثانیه)"); onShowText("تشخیص مشکل", Core.diagnose()) } }
        GlassButton("مشاهده لاگ‌ها", Modifier.fillMaxWidth()) { onShowText("لاگ‌ها", Core.logTail().ifEmpty { "لاگی ثبت نشده است." }) }
        GlassButton(if (confirmLogout) "مطمئنید؟ دوباره بزنید" else "خروج از حساب (تغییر کد اشتراک)", Modifier.fillMaxWidth()) {
            if (confirmLogout) onLogout() else confirmLogout = true
        }
        NText("Nova Connect · نسخه ${Core.versionName} (کد ${fa("${Core.versionCode}")})", 12, C.muted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------- اطلاعیه‌ها ----------------
@Composable fun AnnouncementsOverlay(onClose: () -> Unit) {
    rt(); LaunchedEffect(Unit) { Core.refreshAnnouncements(); Core.markAnnouncementsSeen() }
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClose() }, contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Color(0xFF0B1410)).border(1.dp, C.line, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { NText("اطلاعیه‌ها", 18, C.text, true); NText("✕", 18, C.muted, modifier = Modifier.clickable { onClose() }) }
            Spacer(Modifier.height(10.dp))
            if (Core.announcements.isEmpty()) NText("اطلاعیه‌ای وجود ندارد.", 13, C.muted)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(Core.announcements, key = { it.id }) { a -> Card { NText(a.text, 14, C.text); Spacer(Modifier.height(4.dp)); NText(fa(a.createdAt.take(16).replace('T', ' ')), 10, C.muted) } } }
        }
    }
}

// ---------------- صفحه‌ی کشویی آپدیت (اختیاری و اجباری با ظاهر دوستانه یکسان) ----------------
@Composable fun UpdateSheet(u: UpdateInfo, onLater: () -> Unit) {
    val ctx = LocalContext.current
    val inf = rememberInfiniteTransition(label = "us"); val glow by inf.animateFloat(0.25f, 0.8f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "g")
    val none = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(indication = null, interactionSource = none) { if (!u.mandatory) onLater() }, contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)).background(Brush.verticalGradient(listOf(Color(0xFF10211A), Color(0xFF0A1410))))
            .border(1.dp, Color(0x5510B981), RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)).clickable(indication = null, interactionSource = none) { }.padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(42.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.line))
            Spacer(Modifier.height(14.dp))
            Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) { drawCircle(Brush.radialGradient(listOf(C.acc.copy(alpha = glow * 0.55f), Color.Transparent)), size.minDimension / 2) }
                androidx.compose.foundation.Image(painterResource(R.drawable.ic_logo), null, Modifier.size(68.dp))
            }
            Spacer(Modifier.height(8.dp)); NText("نسخه تازه Nova Connect آماده است ✨", 18, C.acc3, true, align = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x2610B981)).padding(horizontal = 14.dp, vertical = 5.dp)) { NText("نسخه ${fa(u.name)}", 12, C.acc2, true) }
            Spacer(Modifier.height(12.dp))
            NText(if (u.mandatory) "برای اینکه همه‌چیز بی‌دردسر و روان کار کند، لطفاً یک‌بار برنامه را به‌روز کنید. فقط چند لحظه طول می‌کشد." else "با این نسخه برنامه سریع‌تر و بهتر شده است.", 13, C.muted, align = TextAlign.Center)
            if (u.changelog.isNotEmpty()) { Spacer(Modifier.height(12.dp)); Card { NText("تازه‌ها", 11, C.muted); Spacer(Modifier.height(2.dp)); NText(u.changelog, 13, C.text) } }
            Spacer(Modifier.height(18.dp))
            GlassButton("به‌روزرسانی حالا", Modifier.fillMaxWidth(), true) { try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.link))) } catch (e: Exception) { toast(ctx, "باز کردن لینک ممکن نیست.") } }
            if (!u.mandatory) { Spacer(Modifier.height(8.dp)); GlassButton("بعداً", Modifier.fillMaxWidth()) { onLater() } }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable fun TextDialog(title: String, body: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClose() }, contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(0.92f).heightIn(max = 560.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF0B1410)).border(1.dp, C.line, RoundedCornerShape(24.dp)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { }.padding(16.dp)) {
            NText(title, 17, C.text, true); Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { Text2(body) }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("کپی") { val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager; cm.setPrimaryClip(ClipData.newPlainText("nova", body)); toast(ctx, "کپی شد") }
                GlassButton("بستن", primary = true) { onClose() }
            }
        }
    }
}
@Composable private fun Text2(s: String) { androidx.compose.material3.Text(s, color = Color(0xFF9FEFD0), fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
