package com.liquidbattery

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.appwidget.AppWidgetManager
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.flow.first
import java.util.Date

class LiquidWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(100.dp, 100.dp), DpSize(200.dp, 160.dp), DpSize(250.dp, 270.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = Store(context)
        val s = store.settings.first()
        val snap = store.snapshot.first()
        val rows = resolve(snap, store.metas.first())
        provideContent { WidgetContent(context, s, snap, rows) }
    }
}

class LiquidWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LiquidWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        Scheduler.now(context.applicationContext)
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Repo.refresh(context)
    }
}

@Composable
private fun WidgetContent(ctx: Context, s: UiSettings, snap: BatterySnapshot?, all: List<DeviceRow>) {
    val size = LocalSize.current
    val dark = when (s.widgetTheme) {
        1 -> false
        2 -> true
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    val text = if (dark) Color(0xFFF2F6FF) else Color(0xFF111A2B)
    val dim = text.copy(alpha = 0.62f)
    val accent = Color(s.widgetAccent)
    val glass = (if (dark) Color(0xFF16233B) else Color(0xFFFFFFFF)).copy(alpha = s.widgetAlpha)
    val bgMod = if (Build.VERSION.SDK_INT >= 31) {
        GlanceModifier.background(ColorProvider(glass)).cornerRadius(28.dp)
    } else {
        GlanceModifier.background(ImageProvider(if (dark) R.drawable.widget_bg_dark else R.drawable.widget_bg_light))
    }
    val tier = when {
        size.height >= 250.dp -> 2
        size.height >= 150.dp && size.width >= 180.dp -> 1
        else -> 0
    }
    val devices = all.filter { it.meta.inWidget && !it.meta.hidden && !(s.hideUnavailable && !it.connected) }
    val phone = snap?.phone
    val sub = if (phone?.charging == true) "⚡ Charging" else null
    val expanded = !s.widgetCompact
    val btOn = snap?.btOn == true

    Box(
        GlanceModifier.fillMaxSize().then(bgMod).padding(12.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        when (tier) {
            0 -> Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                WHeader(accent, dim, btOn, compact = true)
                Spacer(GlanceModifier.defaultWeight())
                val ringDp = (minOf(size.width.value, size.height.value) - 70f).coerceAtLeast(56f)
                Image(
                    ImageProvider(Render.ring(300, phone?.level, dark, text.toArgb(), sub)),
                    contentDescription = "Phone battery",
                    modifier = GlanceModifier.size(ringDp.dp)
                )
                Spacer(GlanceModifier.defaultWeight())
            }
            1 -> Column(GlanceModifier.fillMaxSize()) {
                WHeader(accent, dim, btOn, compact = false)
                Spacer(GlanceModifier.height(6.dp))
                Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        ImageProvider(Render.ring(300, phone?.level, dark, text.toArgb(), sub)),
                        contentDescription = "Phone battery",
                        modifier = GlanceModifier.size(92.dp)
                    )
                    Spacer(GlanceModifier.width(10.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        if (devices.isEmpty()) {
                            Text("No devices selected", style = TextStyle(color = ColorProvider(dim), fontSize = 11.sp))
                        }
                        devices.take(2).forEach { WDeviceRow(it, text, dim, dark, expanded, s.showLastKnown) }
                    }
                }
            }
            else -> {
                val maxRows = ((size.height.value - 210f) / 36f).toInt().coerceIn(2, 8)
                Column(GlanceModifier.fillMaxSize()) {
                    WHeader(accent, dim, btOn, compact = false)
                    Spacer(GlanceModifier.height(6.dp))
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            ImageProvider(Render.ring(300, phone?.level, dark, text.toArgb(), sub)),
                            contentDescription = "Phone battery",
                            modifier = GlanceModifier.size(110.dp)
                        )
                        Spacer(GlanceModifier.width(12.dp))
                        Column(GlanceModifier.defaultWeight()) {
                            Text("Phone", style = TextStyle(color = ColorProvider(text), fontSize = 15.sp, fontWeight = FontWeight.Medium))
                            val line = if (phone?.charging == true) "Charging" + (phone.plug.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "") else "On battery"
                            Text(line, style = TextStyle(color = ColorProvider(dim), fontSize = 11.sp))
                            if (expanded && !phone?.health.isNullOrEmpty()) {
                                Text("Health: ${phone?.health}", style = TextStyle(color = ColorProvider(dim), fontSize = 11.sp))
                            }
                        }
                    }
                    Spacer(GlanceModifier.height(6.dp))
                    Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
                        if (devices.isEmpty()) {
                            Text("No devices selected", style = TextStyle(color = ColorProvider(dim), fontSize = 11.sp))
                        }
                        devices.take(maxRows).forEach { WDeviceRow(it, text, dim, dark, expanded, s.showLastKnown) }
                    }
                    val at = snap?.updatedAt
                    val stamp = if (at != null) "Updated " + DateFormat.getTimeFormat(ctx).format(Date(at)) else "Not updated yet"
                    Text("$stamp · Tap to open", style = TextStyle(color = ColorProvider(dim), fontSize = 9.sp))
                }
            }
        }
    }
}

@Composable
private fun WHeader(accent: Color, dim: Color, btOn: Boolean, compact: Boolean) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (!compact) {
            Text("Liquid Battery", style = TextStyle(color = ColorProvider(accent), fontSize = 11.sp, fontWeight = FontWeight.Bold))
        }
        Spacer(GlanceModifier.defaultWeight())
        Text(
            if (btOn) "● BT" else "○ BT",
            style = TextStyle(color = ColorProvider(if (btOn) Color(0xFF6FD6A5) else dim), fontSize = 10.sp)
        )
        Spacer(GlanceModifier.width(8.dp))
        Text(
            "↻",
            modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()),
            style = TextStyle(color = ColorProvider(dim), fontSize = 14.sp)
        )
    }
}

@Composable
private fun WDeviceRow(r: DeviceRow, text: Color, dim: Color, dark: Boolean, expanded: Boolean, showLast: Boolean) {
    val pct = r.level?.let { "$it%" } ?: "—"
    Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(r.category.icon, style = TextStyle(fontSize = 13.sp))
        Spacer(GlanceModifier.width(6.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(r.name, maxLines = 1, style = TextStyle(color = ColorProvider(text), fontSize = 12.sp))
            if (expanded) {
                Text(r.status(showLast), maxLines = 1, style = TextStyle(color = ColorProvider(dim), fontSize = 9.sp))
            }
        }
        Image(
            ImageProvider(Render.bar(240, 16, r.level, dark)),
            contentDescription = null,
            modifier = GlanceModifier.width(60.dp).height(4.dp),
            contentScale = ContentScale.FillBounds
        )
        Spacer(GlanceModifier.width(6.dp))
        Text(pct, style = TextStyle(color = ColorProvider(if (r.level != null) text else dim), fontSize = 12.sp, fontWeight = FontWeight.Medium))
    }
}
