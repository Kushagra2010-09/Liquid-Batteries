package com.liquidbattery

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.appwidget.AppWidgetManager
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.*
import kotlinx.coroutines.flow.first
import java.util.Date

class LiquidWidget : GlanceAppWidget() {
    // Exact: LocalSize is the real widget size, so the artwork always fits perfectly.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = Store(context)
        val s = store.settings.first()
        val snap = store.snapshot.first()
        val rows = resolve(snap, store.metas.first())
        val alerts = store.alertState.first().charge
        provideContent { WidgetContent(context, s, snap, rows, alerts) }
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
private fun WidgetContent(ctx: Context, s: UiSettings, snap: BatterySnapshot?, all: List<DeviceRow>, alerts: List<ChargeAlert>) {
    val size = LocalSize.current
    val dark = when (s.widgetTheme) {
        1 -> false
        2 -> true
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    val devices = all
        .filter { it.meta.inWidget && !it.meta.hidden && !(s.hideUnavailable && !it.connected) }
        // connected devices first, then the rest, keeping the user's order inside each group
        .sortedBy { if (it.connected) 0 else 1 }
        .map { WDev(it.category.icon, it.name, it.level, it.status(s.showLastKnown)) }
    val sorted = alerts.sortedByDescending { it.at }
    val topAlert = sorted.firstOrNull()
    val ph = snap?.phone
    val args = WArgs(
        dark = dark,
        panelAlpha = s.widgetAlpha,
        accent = s.widgetAccent,
        phoneLevel = ph?.level,
        charging = ph?.charging == true,
        plug = ph?.plug ?: "",
        health = ph?.health ?: "",
        devices = devices,
        expanded = !s.widgetCompact,
        updated = snap?.let { "Updated " + DateFormat.getTimeFormat(ctx).format(Date(it.updatedAt)) } ?: "Not updated yet",
        alert = topAlert?.let { WAlert(it.name, it.level) },
        alertExtra = (sorted.size - 1).coerceAtLeast(0)
    )
    val bmp = WidgetArt.draw(size.width.value, size.height.value, ctx.resources.displayMetrics.density, args)

    Box(GlanceModifier.fillMaxSize().clickable(actionStartActivity(Intent(ctx, MainActivity::class.java)))) {
        Image(
            ImageProvider(bmp),
            contentDescription = "Liquid Battery",
            modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )
        // invisible tap target over the refresh arrow (top-right)
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
            Spacer(GlanceModifier.size(46.dp).clickable(actionRunCallback<RefreshAction>()))
        }
        // tap the "Charge <device>" banner to dismiss it
        if (topAlert != null) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Spacer(
                    GlanceModifier.fillMaxWidth().height(46.dp)
                        .clickable(actionRunCallback<DismissAlertAction>(actionParametersOf(ALERT_ID to topAlert.id)))
                )
            }
        }
    }
}
