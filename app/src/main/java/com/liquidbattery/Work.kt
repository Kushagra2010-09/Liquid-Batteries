package com.liquidbattery

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Scheduler.periodic(this, 15, update = false)
    }
}

object Repo {
    private val mutex = Mutex()
    private val gattBusy = AtomicBoolean(false)

    /** Cheap update used by live mode: only the phone battery, no Bluetooth reads. */
    suspend fun refreshPhone(ctx: Context) {
        val c = ctx.applicationContext
        val store = Store(c)
        if (store.snapshot.first() == null) { refresh(c, gatt = false); return }
        mutex.withLock {
            val prev = store.snapshot.first() ?: return@withLock
            val ph = Reader.phone(c)
            if (ph.level == prev.phone.level && ph.charging == prev.phone.charging && ph.plug == prev.phone.plug && ph.health == prev.phone.health) return@withLock
            store.saveSnapshot(prev.copy(phone = ph, updatedAt = System.currentTimeMillis()))
            LiquidWidget().updateAll(c)
        }
    }

    private fun sameContent(a: BatterySnapshot, b: BatterySnapshot): Boolean =
        a.phone.copy(tempC = 0f) == b.phone.copy(tempC = 0f) &&
            a.btOn == b.btOn && a.hasPermission == b.hasPermission &&
            b.updatedAt - a.updatedAt < 5 * 60_000L &&
            a.devices.map { it.copy(updatedAt = 0L, lastKnownAt = 0L) } == b.devices.map { it.copy(updatedAt = 0L, lastKnownAt = 0L) }

    /**
     * gatt = false is the FAST path (well under a second): connection state + instantly available
     * battery sources only. gatt = true also opens BLE connections to read 0x180F (can take seconds).
     */
    suspend fun refresh(ctx: Context, gatt: Boolean = true) {
        val c = ctx.applicationContext
        mutex.withLock {
            var changed = true
            try {
                val store = Store(c)
                val prev = store.snapshot.first()
                val phone = Reader.phone(c)
                val (devs, btOn) = Reader.devices(c, prev?.devices.orEmpty().associateBy { it.id }, gatt = false)
                Alerts.process(c, store, prev?.devices.orEmpty(), devs, store.metas.first())
                val snap = BatterySnapshot(phone, devs, btOn, Reader.hasPermission(c), System.currentTimeMillis())
                // skip the save and the widget redraw when nothing visible changed (saves battery)
                changed = prev == null || !sameContent(prev, snap)
                if (changed) store.saveSnapshot(snap)
            } catch (e: Throwable) {
                // keep the previous snapshot; never crash a background refresh
            }
            if (changed) LiquidWidget().updateAll(c)
        }
        // the slow BLE battery read runs OUTSIDE the lock, so quick refreshes are never blocked by it
        if (gatt) gattPass(c)
    }

    private suspend fun gattPass(c: Context) {
        if (!gattBusy.compareAndSet(false, true)) return
        try {
            val store = Store(c)
            val targets = (store.snapshot.first()?.devices.orEmpty()).filter { it.connected && it.level == null }
            if (targets.isEmpty() || !Reader.hasPermission(c)) return
            for (t in targets) {
                val lvl = Reader.gattLevel(c, t.id) ?: continue
                mutex.withLock {
                    val latest = store.snapshot.first() ?: return@withLock
                    val now = System.currentTimeMillis()
                    var hit = false
                    val devs = latest.devices.map { d ->
                        if (d.id == t.id && d.connected && d.level == null) {
                            hit = true
                            d.copy(level = lvl, lastKnownLevel = lvl, lastKnownAt = now, source = "GATT Battery Service (0x180F)", updatedAt = now)
                        } else d
                    }
                    if (hit) {
                        Alerts.process(c, store, latest.devices, devs, store.metas.first())
                        store.saveSnapshot(latest.copy(devices = devs, updatedAt = now))
                        LiquidWidget().updateAll(c)
                    }
                }
            }
        } catch (e: Throwable) {
        } finally {
            gattBusy.set(false)
        }
    }

    /** A device connects in stages (link, then audio profile, then battery report): re-check quickly 3 times. */
    suspend fun quickBurst(ctx: Context) {
        refresh(ctx, gatt = false)
        delay(2000)
        refresh(ctx, gatt = false)
        delay(3000)
        refresh(ctx, gatt = false)
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

/** Connect/disconnect, Bluetooth on/off and charger events: refresh immediately (no WorkManager delay). */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val c = ctx.applicationContext
        val action = i.action
        val result = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (action) {
                    Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> {
                        delay(400)
                        Repo.refreshPhone(c)
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_ACL_DISCONNECTED,
                    BluetoothAdapter.ACTION_STATE_CHANGED -> Repo.quickBurst(c)
                    else -> Repo.refresh(c, gatt = false)
                }
            } finally {
                result.finish()
            }
        }
        // the slower full read (including BLE battery service) follows a few seconds later
        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) Scheduler.now(c, 6)
    }
}

/** Some headsets push their battery level via an AT command; capture it and refresh right away. */
class VendorReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val c = ctx.applicationContext
        if (!HfpCache.parseVendor(c, i)) return
        val result = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { Repo.refresh(c, gatt = false) } finally { result.finish() }
        }
    }
}
