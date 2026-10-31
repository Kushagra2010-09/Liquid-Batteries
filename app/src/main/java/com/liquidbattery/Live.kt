package com.liquidbattery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

object Live {
    fun sync(ctx: Context, enabled: Boolean) {
        val i = Intent(ctx.applicationContext, LiveService::class.java)
        if (enabled) {
            try { ContextCompat.startForegroundService(ctx.applicationContext, i) } catch (e: Throwable) { }
        } else {
            ctx.applicationContext.stopService(i)
        }
    }
}

/**
 * Keeps a tiny receiver alive so the widget updates the instant the phone battery changes
 * (Android does not allow ACTION_BATTERY_CHANGED to be received from the manifest).
 */
class LiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            scope.launch { Repo.refreshPhone(c) }
        }
    }

    private val deviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            pending?.cancel()
            pending = scope.launch { delay(1500); Repo.refresh(c) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("live", "Live battery updates", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "live")
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentTitle("Liquid Battery")
            .setContentText("Live battery updates are on")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
        ServiceCompat.startForeground(this, 71, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)

        ContextCompat.registerReceiver(this, phoneReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        val bt = IntentFilter().apply {
            addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED")
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
        }
        ContextCompat.registerReceiver(this, deviceReceiver, bt, ContextCompat.RECEIVER_EXPORTED)

        // Bluetooth devices only report occasionally, so also re-check them every 3 minutes.
        scope.launch {
            while (isActive) {
                delay(3 * 60_000L)
                Repo.refresh(this@LiveService)
            }
        }
        scope.launch { Repo.refresh(this@LiveService) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try { unregisterReceiver(phoneReceiver) } catch (e: Throwable) { }
        try { unregisterReceiver(deviceReceiver) } catch (e: Throwable) { }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Restarts live updates after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val c = ctx.applicationContext
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (Store(c).settings.first().liveUpdates) Live.sync(c, true)
            } finally {
                result.finish()
            }
        }
    }
}
