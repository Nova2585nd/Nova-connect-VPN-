package app.nova.connect

import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var pendingUid: String? = null
    private var autoDone = false
    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) pendingUid?.let { startSvc(it) } else toast(this, "برای اتصال باید مجوز VPN را تأیید کنید.")
        pendingUid = null
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        setContent { NovaTheme { CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { Root(this) } } }
    }

    override fun onStart() {
        super.onStart(); Core.visible++
        Core.scope.launch { Core.refreshUser(); Core.refreshAnnouncements(); Core.checkUpdate(); Core.syncOnLaunch() }
        if (!autoDone && Core.autoConnect && Core.servers.isNotEmpty() && Core.conn.status == "idle") { autoDone = true; requestConnect(null, true) }
    }
    override fun onStop() { super.onStop(); Core.visible-- }

    // auto=true (دکمه‌ی اصلی): اگر پینگ سرور فعلی ناموفق بود، بهترین سرور انتخاب می‌شود
    fun requestConnect(uid: String?, auto: Boolean = false) {
        Core.scope.launch {
            var target = uid ?: Core.defaultId()
            if (auto && (target == null || Core.stats[target]?.status == "fail")) { val rec = Core.recommendedId(); if (rec != null) target = rec }
            val t: String = target ?: run { toast(this@MainActivity, "سروری برای اتصال وجود ندارد."); return@launch }
            val err = Core.connectGuard(); if (err != null) { toast(this@MainActivity, err); return@launch }
            withContext(Dispatchers.Main) {
                val i = VpnService.prepare(this@MainActivity)
                if (i != null) { pendingUid = t; vpnLauncher.launch(i) } else startSvc(t)
            }
        }
    }
    private fun startSvc(uid: String) { startForegroundService(Intent(this, NovaVpnService::class.java).setAction(NovaVpnService.A_CONNECT).putExtra(NovaVpnService.EXTRA_UID, uid)) }
    fun disconnect() { startService(Intent(this, NovaVpnService::class.java).setAction(NovaVpnService.A_DISCONNECT)) }
}

private val TITLES = listOf("خانه", "سرورها", "اشتراک", "تنظیمات")

@Composable fun Root(act: MainActivity) {
    rt(); val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }; var showAnn by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var welcomeErr by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    val p = Core.progress; val showProgress = p != null && p.run != Core.dismissedRun && (p.active || p.done)
    val up = Core.update; val showUpdate = up != null && up.available && (up.mandatory || up.code != Core.dismissedUpdate)
    val showInvite = Core.askInvite && Core.code != 0 && !showProgress && !showUpdate
    val exhausted = Core.user?.exhausted == true || (Usage.estimateRemaining()?.let { it <= 0.0 } == true)
    val showExhaust = exhausted && !Core.exhaustDismissed && Core.code != 0 && !showProgress && !showUpdate && !showInvite
    LaunchedEffect(exhausted) { if (!exhausted) Core.exhaustDismissed = false }
    BackHandler(enabled = showUpdate && up?.mandatory == true || showExhaust || dialog != null || showAnn || tab != 0) {
        if (showUpdate && up?.mandatory == true) return@BackHandler
        if (showExhaust) { Core.exhaustDismissed = true; Core.changed(); return@BackHandler }
        if (dialog != null) dialog = null else if (showAnn) showAnn = false else tab = 0
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedBackground()
        when {
            showProgress -> ProgressScreen(onHide = { Core.dismissedRun = p!!.run; Core.changed() }, onDone = { Core.dismissedRun = p!!.run; Core.changed() }, onDiag = { Core.scope.launch { dialog = "تشخیص مشکل" to "در حال بررسی…"; dialog = "تشخیص مشکل" to Core.diagnose() } })
            Core.code == 0 -> WelcomeScreen(welcomeErr ?: Core.welcomeNotice, busy, onAuto = { busy = true; welcomeErr = null; Core.welcomeNotice = null; Core.scope.launch { val e = Core.setupAuto(); busy = false; welcomeErr = e } },
                onCode = { c -> busy = true; welcomeErr = null; Core.welcomeNotice = null; Core.scope.launch { val e = Core.setupWithCode(c.toInt()); busy = false; welcomeErr = e } })
            else -> Column(Modifier.fillMaxSize()) {
                TopBar(TITLES[tab], Core.unreadAnnouncements, Core.code) { showAnn = true }
                Box(Modifier.weight(1f)) {
                    AnimatedContent(targetState = tab, transitionSpec = { (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 24 }) togetherWith fadeOut(tween(140)) }, label = "tabs") { t ->
                        when (t) {
                            0 -> HomeScreen({ id -> act.requestConnect(id) }, { id -> act.requestConnect(id, true) }, { act.disconnect() }, { tab = 2 }, { tab = 1 }, { Core.scope.launch { dialog = "تشخیص مشکل" to "در حال بررسی…"; dialog = "تشخیص مشکل" to Core.diagnose() } })
                            1 -> ServersScreen { id -> act.requestConnect(id) }
                            2 -> SubscriptionScreen()
                            else -> SettingsScreen({ t2, b -> dialog = t2 to b }, {
                                Core.scope.launch { Core.dismissedUpdate = 0; Core.checkUpdate(); toast(ctx, if (Core.update?.available == true) "نسخه جدید موجود است" else "نسخه شما به‌روز است") }
                            }, { act.disconnect(); Core.logout(); tab = 0 })
                        }
                    }
                }
                BottomNav(tab) { tab = it }
            }
        }
        AnimatedVisibility(showAnn, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) { AnnouncementsOverlay { showAnn = false } }
        AnimatedVisibility(showInvite, enter = slideInVertically(tween(380)) { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) { InviteSheet() }
        AnimatedVisibility(showExhaust, enter = fadeIn(tween(250)) + scaleIn(initialScale = 0.9f), exit = fadeOut(tween(150))) {
            QuotaSheet(onClose = { Core.exhaustDismissed = true; Core.changed() }, onBuy = { Core.exhaustDismissed = true; tab = 2; Core.changed() })
        }
        dialog?.let { d -> TextDialog(d.first, d.second) { dialog = null } }
        AnimatedVisibility(showUpdate, enter = slideInVertically(tween(380)) { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            if (up != null) UpdateSheet(up) { Core.dismissedUpdate = up.code; Core.save(); Core.changed() }
        }
    }
}

@Composable fun TopBar(title: String, unread: Int, code: Int, onBell: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_logo), null, Modifier.size(38.dp))
        Spacer(Modifier.width(10.dp)); NText(title, 20, C.text, true, Modifier.weight(1f))
        Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x2610B981)).border(1.dp, Color(0x6634D399), RoundedCornerShape(20.dp)).padding(horizontal = 11.dp, vertical = 5.dp)) { NText("کد " + fa("$code"), 12, C.acc3, true) }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(42.dp).clip(CircleShape).background(C.glass).border(1.dp, C.line, CircleShape).clickable { onBell() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Notifications, null, tint = C.text)
            if (unread > 0) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(16.dp).clip(CircleShape).background(C.bad), contentAlignment = Alignment.Center) { NText(fa("$unread"), 9, Color.White, true) }
        }
    }
}

@Composable fun BottomNav(sel: Int, onSel: (Int) -> Unit) {
    val items: List<Pair<String, ImageVector>> = listOf("خانه" to Icons.Default.Home, "سرورها" to Icons.Default.List, "اشتراک" to Icons.Default.ShoppingCart, "تنظیمات" to Icons.Default.Settings)
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xCC0B1410)).border(1.dp, C.line, RoundedCornerShape(26.dp)).padding(6.dp)) {
        items.forEachIndexed { i, (label, icon) ->
            val on = i == sel
            val bg by animateColorAsState(if (on) Color(0x3310B981) else Color.Transparent, tween(250), label = "nb")
            val tint by animateColorAsState(if (on) C.acc2 else C.muted, tween(250), label = "nt")
            Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(bg).clickable { onSel(i) }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp)); NText(label, 11, tint, on)
            }
        }
    }
}
