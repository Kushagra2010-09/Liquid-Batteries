package com.liquidbattery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

class LiquidBatteryWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val percent = readPhoneBattery(context)
        provideContent {
            WidgetContent(percent)
        }
    }

    private fun readPhoneBattery(context: Context): Int? {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100) / scale
    }
}

private fun tierColor(percent: Int): Color = when {
    percent > 60 -> Color(0xFF34C759)   // green
    percent >= 30 -> Color(0xFF32D4DE)  // cyan
    percent >= 15 -> Color(0xFFFFB020)  // amber
    else -> Color(0xFFFF453A)           // red
}

@androidx.compose.runtime.Composable
private fun WidgetContent(percent: Int?) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color(0x66FFFFFF))
            .cornerRadius(24.dp)
            .padding(16.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Phone",
            style = TextStyle(
                color = ColorProvider(Color(0xFF444444)),
                fontSize = 12.sp
            )
        )
        if (percent == null) {
            Text(
                text = "Battery unavailable",
                style = TextStyle(
                    color = ColorProvider(Color(0xFF444444)),
                    fontSize = 14.sp
                )
            )
        } else {
            Text(
                text = "$percent%",
                style = TextStyle(
                    color = ColorProvider(tierColor(percent)),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

class LiquidBatteryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LiquidBatteryWidget()
}
