package app.nova.connect

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object C {
    val bg = Color(0xFF060B09); val glass = Color(0x14FFFFFF); val line = Color(0x1FFFFFFF); val text = Color(0xFFE8F5EF); val muted = Color(0xFF86A196)
    val acc = Color(0xFF10B981); val acc2 = Color(0xFF34D399); val acc3 = Color(0xFF6EE7B7); val bad = Color(0xFFF87171); val warn = Color(0xFFFBBF24); val dark = Color(0xFF04110B)
}

fun fa(s: String): String = s.map { if (it in '0'..'9') ('۰' + (it - '0')) else it }.joinToString("")
@Composable fun rt(): Int = Core.tick.intValue // هر composable که از Core می‌خواند باید این را بخواند تا با تغییر وضعیت بازسازی شود

@Composable fun NovaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = C.acc, background = C.bg, surface = C.bg, onSurface = C.text, onBackground = C.text), content = content)
}

fun Modifier.glass(r: Dp = 20.dp): Modifier = this.clip(RoundedCornerShape(r)).background(C.glass).border(1.dp, C.line, RoundedCornerShape(r))

@Composable fun NText(text: String, size: Int = 14, color: Color = C.text, bold: Boolean = false, modifier: Modifier = Modifier, align: TextAlign? = null, maxLines: Int = Int.MAX_VALUE) {
    Text(text = text, fontSize = size.sp, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, modifier = modifier, textAlign = align, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

// پس‌زمینه زنده: سه نور سبز که آرام حرکت می‌کنند
@Composable fun AnimatedBackground() {
    val inf = rememberInfiniteTransition(label = "bg")
    val a by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(21000, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    Canvas(Modifier.fillMaxSize().background(C.bg)) {
        val w = size.width; val h = size.height
        drawCircle(Brush.radialGradient(listOf(Color(0x5510B981), Color.Transparent), Offset(w * (0.9f - 0.3f * a), h * (0.05f + 0.15f * a)), w * 0.7f), w * 0.7f, Offset(w * (0.9f - 0.3f * a), h * (0.05f + 0.15f * a)))
        drawCircle(Brush.radialGradient(listOf(Color(0x440EA5A4), Color.Transparent), Offset(w * (0.1f + 0.3f * b), h * (0.95f - 0.2f * b)), w * 0.65f), w * 0.65f, Offset(w * (0.1f + 0.3f * b), h * (0.95f - 0.2f * b)))
        drawCircle(Brush.radialGradient(listOf(Color(0x226EE7B7), Color.Transparent), Offset(w * (0.2f + 0.5f * b), h * (0.45f + 0.1f * a)), w * 0.5f), w * 0.5f, Offset(w * (0.2f + 0.5f * b), h * (0.45f + 0.1f * a)))
    }
}

@Composable fun GlassButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = if (primary) Modifier.background(Brush.horizontalGradient(listOf(C.acc, C.acc2))) else Modifier.background(C.glass)
    Box(modifier.clip(RoundedCornerShape(14.dp)).then(bg).border(1.dp, if (primary) Color.Transparent else C.line, RoundedCornerShape(14.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
        NText(text, 14, if (!enabled) C.muted else if (primary) C.dark else C.text, primary)
    }
}

@Composable fun SignalBars(stat: Stat?) {
    val q = if (stat == null || stat.status != "ok" || stat.latency == null) 0 else if (stat.latency!! < 150) 4 else if (stat.latency!! < 350) 3 else if (stat.latency!! < 700) 2 else 1
    val col = if (q >= 3) C.acc2 else if (q > 0) C.warn else if (stat?.status == "fail") Color(0x55F87171) else C.line
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..4) Box(Modifier.width(4.dp).height((4 + i * 4).dp).clip(RoundedCornerShape(2.dp)).background(if (i <= q) col else C.line))
    }
}

@Composable fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val bg by animateColorAsState(if (on) C.acc else C.line, tween(250), label = "tg")
    val x by animateFloatAsState(if (on) 1f else 0f, tween(250), label = "tx")
    Box(Modifier.width(48.dp).height(28.dp).clip(RoundedCornerShape(14.dp)).background(bg).clickable { onChange(!on) }) {
        Box(Modifier.padding(3.dp).offset(x = (20f * x).dp).size(22.dp).clip(CircleShape).background(Color.White))
    }
}

// دکمه‌ی بزرگ اتصال با حلقه‌های زنده؛ state: idle / busy / connected / error
@Composable fun PowerButton(state: String, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "pw")
    val ripple by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "r")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "s")
    val breathe by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val fill by animateColorAsState(if (state == "connected") C.acc else Color(0x14FFFFFF), tween(500), label = "f")
    val iconColor by animateColorAsState(when (state) { "connected" -> C.dark; "busy" -> C.warn; "error" -> C.bad; else -> C.acc2 }, tween(400), label = "i")
    val ring = when (state) { "connected" -> Color.Transparent; "error" -> C.bad; "busy" -> C.warn; else -> C.acc }
    Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = size.minDimension / 2 * 0.66f
            when (state) {
                "connected" -> { for (k in 0..2) { val t = (ripple + k / 3f) % 1f; drawCircle(C.acc.copy(alpha = (1f - t) * 0.55f), base + t * base * 0.6f, center, style = Stroke(3f)) } }
                "busy" -> rotate(spin) { drawArc(Brush.sweepGradient(listOf(Color.Transparent, C.acc2)), 0f, 300f, false, Offset(center.x - base * 1.12f, center.y - base * 1.12f), Size(base * 2.24f, base * 2.24f), style = Stroke(10f, cap = StrokeCap.Round)) }
                else -> drawCircle(C.acc.copy(alpha = 0.08f + 0.12f * breathe), base * (1.12f + 0.1f * breathe), center)
            }
        }
        Box(Modifier.size(152.dp).clip(CircleShape).background(fill).border(2.dp, ring, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(58.dp)) {
                val s = size.minDimension; val w = s * 0.09f
                drawArc(iconColor, -60f, 300f, false, Offset(s * 0.12f, s * 0.2f), Size(s * 0.76f, s * 0.76f), style = Stroke(w, cap = StrokeCap.Round))
                drawLine(iconColor, Offset(s / 2, s * 0.03f), Offset(s / 2, s * 0.5f), strokeWidth = w, cap = StrokeCap.Round)
            }
        }
    }
}

// برچسب رنگی (تگ) برای «سرور فعلی» و «بهترین سرور»
@Composable fun Tag(text: String, color: Color, strong: Boolean = false) {
    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = if (strong) 0.28f else 0.16f)).border(1.dp, color.copy(alpha = 0.75f), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) { NText(text, 12, color, true) }
}

// دکمه‌ی سبز و توجه‌جلب‌کن: ضربان ملایم و حاشیه‌ی درخشان
@Composable fun AttentionButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "att")
    val sc by inf.animateFloat(1f, 1.07f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")
    val gl by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "g")
    Box(modifier.graphicsLayer { scaleX = sc; scaleY = sc }.clip(RoundedCornerShape(14.dp)).background(Brush.horizontalGradient(listOf(C.acc, C.acc2)))
        .border(2.dp, C.acc3.copy(alpha = gl), RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp), contentAlignment = Alignment.Center) { NText(text, 14, C.dark, true) }
}
