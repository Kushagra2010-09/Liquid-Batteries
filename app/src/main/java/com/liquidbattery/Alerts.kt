package com.liquidbattery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll

val ALERT_ID = ActionParameters.Key<String>("alertId")

/** Tapping the "Charge <device>" banner on the widget removes it. */
class DismissAlertAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[ALERT_ID] ?: return
        Store(context).updateAlerts { s -> s.copy(charge = s.charge.filter { it.id != id }) }
        LiquidWidget().updateAll(context)
    }
}

object Alerts {
    private const val WARN_BELOW = 10     // connected device under this -> notification
    private const val WARN_RESET = 15     // must climb above this before it can warn again
    private const val CHARGE_AT_OR_BELOW = 25  // disconnected at/below this -> widget banner

    suspend fun process(c: Context, store: Store, prev: List<DeviceState>, cur: List<DeviceState>, metas: Map<String, DeviceMeta>) {
        val now = System.currentTimeMillis()
        val prevMap = prev.associateBy { it.id }
        val toWarn = ArrayList<DeviceState>()
        store.updateAlerts { s ->
            toWarn.clear()
            val charge = s.charge.toMutableList()
            val warned = s.warned.toMutableSet()
            for (d in cur) {
                val m = metas[d.id]
                val tracked = m?.hidden != true
                val name = m?.name?.takeIf { it.isNotBlank() } ?: d.systemName
                val old = prevMap[d.id]
                val lvl = d.level
                if (d.connected) {
                    // a banner created a moment ago for a device that is connected again was a connection flap
                    charge.removeAll { it.id == d.id && now - it.at < 120_000L }
                    if (lvl != null) {
                        if (lvl < WARN_BELOW && tracked && d.id !in warned) {
                            warned += d.id
                            toWarn += d.copy(systemName = name)
                        }
                        if (lvl >= WARN_RESET) warned -= d.id
                        if (lvl > CHARGE_AT_OR_BELOW) charge.removeAll { it.id == d.id }
                    }
                }
                if (old != null && old.connected && !d.connected && tracked) {
                    val last = old.level
                        ?: old.lastKnownLevel?.takeIf { (old.lastKnownAt ?: 0L) > now - 6 * 3600_000L }
                    if (last != null && last <= CHARGE_AT_OR_BELOW) {
                        charge.removeAll { it.id == d.id }
                        charge += ChargeAlert(d.id, name, last, now)
                    }
                }
            }
            s.copy(charge = charge, warned = warned)
        }
        toWarn.forEach { notifyLow(c, it) }
    }

    private fun notifyLow(c: Context, d: DeviceState) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("charge", "Low battery warnings", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(c, "charge")
                .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
                .setContentTitle("Charge ${d.systemName}")
                .setContentText("Battery is at ${d.level}%")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            if (NotificationManagerCompat.from(c).areNotificationsEnabled()) nm.notify(d.id.hashCode(), n)
        } catch (e: Throwable) {
        }
    }
}
