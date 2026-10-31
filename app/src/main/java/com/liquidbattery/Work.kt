package com.liquidbattery

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import androidx.work.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Scheduler.periodic(this, 15, update = false)
    }
}

object Repo {
    private val mutex = Mutex()

    suspend fun refresh(ctx: Context) = mutex.withLock {
        val c = ctx.applicationContext
        try {
            val store = Store(c)
            val prev = store.snapshot.first()
            val phone = Reader.phone(c)
            val (devs, btOn) = Reader.devices(c, prev?.devices.orEmpty().associateBy { it.id })
            store.saveSnapshot(BatterySnapshot(phone, devs, btOn, Reader.hasPermission(c), System.currentTimeMillis()))
        } catch (e: Throwable) {
            // keep the previous snapshot; never crash a background refresh
        }
        LiquidWidget().updateAll(c)
    }
}

class BatteryWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        Repo.refresh(applicationContext)
        return Result.success()
    }
}

object Scheduler {
    /** Android enforces a 15 minute minimum for periodic work. */
    fun periodic(ctx: Context, minutes: Int, update: Boolean) {
        val req = PeriodicWorkRequestBuilder<BatteryWorker>(maxOf(15, minutes).toLong(), TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "lb_periodic",
            if (update) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            req
        )
    }

    fun now(ctx: Context, delaySec: Long = 0) {
        val req = OneTimeWorkRequestBuilder<BatteryWorker>().setInitialDelay(delaySec, TimeUnit.SECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("lb_now", ExistingWorkPolicy.REPLACE, req)
    }
}

/** Connect/disconnect, Bluetooth on/off and charger events trigger one short refresh. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Scheduler.now(ctx.applicationContext, 4)
    }
}

/** Some headsets push their battery level via an AT command; capture it when available. */
class VendorReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, i: Intent) {
        val cmd = i.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD) ?: return
        if (!cmd.contains("IPHONEACCEV")) return
        val args = i.getSerializableExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS) as? Array<*> ?: return
        val dev = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val n = (args.getOrNull(0) as? Number)?.toInt() ?: return
        var k = 1
        repeat(n) {
            val key = (args.getOrNull(k) as? Number)?.toInt()
            val v = (args.getOrNull(k + 1) as? Number)?.toInt()
            if (key == 1 && v != null) HfpCache.put(ctx, dev.address, ((v + 1) * 10).coerceIn(0, 100))
            k += 2
        }
        Scheduler.now(ctx.applicationContext, 1)
    }
}
