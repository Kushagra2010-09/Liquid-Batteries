package com.liquidbattery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.*
import java.util.UUID
import kotlin.coroutines.resume

/** Battery levels reported by headsets over HFP vendor events (see VendorReceiver). */
object HfpCache {
    private fun p(c: Context) = c.getSharedPreferences("hfp", Context.MODE_PRIVATE)
    fun put(c: Context, addr: String, level: Int) {
        p(c).edit().putString(addr, "$level|${System.currentTimeMillis()}").apply()
    }
    fun get(c: Context, addr: String, maxAgeMs: Long = 6 * 3600_000L): Int? {
        val v = p(c).getString(addr, null) ?: return null
        val parts = v.split("|")
        if (parts.size != 2) return null
        val t = parts[1].toLongOrNull() ?: return null
        return if (System.currentTimeMillis() - t <= maxAgeMs) parts[0].toIntOrNull() else null
    }
}

object Reader {
    private val BATT_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val BATT_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    fun hasPermission(c: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun phone(c: Context): PhoneInfo {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val plug = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> ""
        }
        val health = when (i?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheated"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            else -> ""
        }
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        return PhoneInfo(pct, charging, plug, health, temp)
    }

    @SuppressLint("MissingPermission")
    suspend fun devices(c: Context, prev: Map<String, DeviceState>): Pair<List<DeviceState>, Boolean> {
        val now = System.currentTimeMillis()
        val mgr = c.getSystemService(BluetoothManager::class.java) ?: return emptyList<DeviceState>() to false
        val adapter = mgr.adapter ?: return emptyList<DeviceState>() to false
        if (!adapter.isEnabled) {
            return prev.values.map { it.copy(connected = false, level = null, updatedAt = now) } to false
        }
        if (!hasPermission(c)) return emptyList<DeviceState>() to true

        val bonded = try { adapter.bondedDevices.toList() } catch (e: SecurityException) { emptyList() }
        val connectedSet: Set<String> = coroutineScope {
            listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, 4 /* HID host */)
                .map { pr -> async { profileConnected(c, adapter, pr) } }
                .awaitAll().flatten().toSet()
        }
        val gattSet: Set<String> = try {
            mgr.getConnectedDevices(BluetoothProfile.GATT).map { it.address }.toSet()
        } catch (e: Throwable) { emptySet() }

        val out = ArrayList<DeviceState>()
        for (d in bonded) {
            val old = prev[d.address]
            val connected = d.address in connectedSet || d.address in gattSet || reflectConnected(d)
            var level: Int? = null
            var src: String? = null
            if (connected) {
                reflectBattery(d)?.let { level = it; src = "Bluetooth stack (system)" }
                if (level == null) HfpCache.get(c, d.address)?.let { level = it; src = "Headset report (HFP)" }
                if (level == null && d.type != BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                    readGatt(c, d)?.let { level = it; src = "GATT Battery Service (0x180F)" }
                }
            }
            val lk = level ?: old?.lastKnownLevel
            val lkAt = if (level != null) now else old?.lastKnownAt
            out += DeviceState(d.address, d.name ?: d.address, guess(d), connected, level, lk, lkAt, src, now)
        }
        return out to true
    }

    @SuppressLint("MissingPermission")
    private fun guess(d: BluetoothDevice): Category {
        val n = (d.name ?: "").lowercase()
        val cls = d.bluetoothClass
        val major = cls?.majorDeviceClass ?: -1
        val dev = cls?.deviceClass ?: -1
        return when {
            major == BluetoothClass.Device.Major.WEARABLE && dev == BluetoothClass.Device.WEARABLE_WRIST_WATCH -> Category.WATCH
            "watch" in n || "band" in n -> Category.WATCH
            "bud" in n || "airdopes" in n || "pods" in n || "tws" in n -> Category.EARBUDS
            dev == BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER || dev == BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO || "speaker" in n -> Category.SPEAKER
            dev == BluetoothClass.Device.PERIPHERAL_KEYBOARD || dev == BluetoothClass.Device.PERIPHERAL_KEYBOARD_POINTING || "keyboard" in n -> Category.KEYBOARD
            dev == BluetoothClass.Device.PERIPHERAL_POINTING || "mouse" in n -> Category.MOUSE
            major == BluetoothClass.Device.Major.AUDIO_VIDEO -> Category.HEADPHONES
            else -> Category.OTHER
        }
    }

    // Hidden APIs: best effort only, silently ignored if the OS blocks them.
    private fun reflectBattery(d: BluetoothDevice): Int? = try {
        val v = BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(d) as Int
        v.takeIf { it in 0..100 }
    } catch (e: Throwable) { null }

    private fun reflectConnected(d: BluetoothDevice): Boolean = try {
        BluetoothDevice::class.java.getMethod("isConnected").invoke(d) as Boolean
    } catch (e: Throwable) { false }

    @SuppressLint("MissingPermission")
    private suspend fun profileConnected(c: Context, adapter: BluetoothAdapter, profile: Int): Set<String> =
        withTimeoutOrNull(3000) {
            suspendCancellableCoroutine<Set<String>> { cont ->
                val l = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        val set = try { proxy.connectedDevices.map { it.address }.toSet() } catch (e: Throwable) { emptySet() }
                        try { adapter.closeProfileProxy(p, proxy) } catch (e: Throwable) {}
                        if (cont.isActive) cont.resume(set)
                    }
                    override fun onServiceDisconnected(p: Int) {}
                }
                val ok = try { adapter.getProfileProxy(c, l, profile) } catch (e: Throwable) { false }
                if (!ok && cont.isActive) cont.resume(emptySet())
            }
        } ?: emptySet()

    /** Reads the standard Battery Level characteristic (0x2A19) of the Battery Service (0x180F). */
    @SuppressLint("MissingPermission")
    private suspend fun readGatt(c: Context, d: BluetoothDevice): Int? = withTimeoutOrNull(9000) {
        suspendCancellableCoroutine<Int?> { cont ->
            var gatt: BluetoothGatt? = null
            fun finish(v: Int?) {
                try { gatt?.close() } catch (e: Throwable) {}
                if (cont.isActive) cont.resume(v)
            }
            fun parse(status: Int, b: ByteArray?): Int? =
                if (status == BluetoothGatt.GATT_SUCCESS) b?.firstOrNull()?.toInt()?.and(0xFF)?.takeIf { it in 0..100 } else null
            val cb = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices() else finish(null)
                }
                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    val ch = g.getService(BATT_SERVICE)?.getCharacteristic(BATT_LEVEL)
                    if (ch == null || !g.readCharacteristic(ch)) finish(null)
                }
                override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                    finish(parse(status, value))
                }
                @Deprecated("Deprecated in API 33")
                override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                    if (Build.VERSION.SDK_INT < 33) finish(parse(status, ch.value))
                }
            }
            cont.invokeOnCancellation { try { gatt?.close() } catch (e: Throwable) {} }
            gatt = try { d.connectGatt(c, false, cb, BluetoothDevice.TRANSPORT_LE) } catch (e: Throwable) { null }
            if (gatt == null) finish(null)
        }
    }

    /** Diagnostic: lists GATT services so unsupported devices (e.g. boAt watches) can be investigated. */
    @SuppressLint("MissingPermission")
    suspend fun probe(c: Context, address: String): String {
        val adapter = c.getSystemService(BluetoothManager::class.java)?.adapter ?: return "Bluetooth unavailable."
        if (!hasPermission(c)) return "Bluetooth permission not granted."
        val d = try { adapter.getRemoteDevice(address) } catch (e: Throwable) { return "Invalid device." }
        return withTimeoutOrNull(15000) {
            suspendCancellableCoroutine<String> { cont ->
                var gatt: BluetoothGatt? = null
                fun done(s: String) {
                    try { gatt?.close() } catch (e: Throwable) {}
                    if (cont.isActive) cont.resume(s)
                }
                val cb = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                        else done("Could not connect over Bluetooth LE (status $status). The device may be classic-only, out of range, or already held by its own app.")
                    }
                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        val sb = StringBuilder()
                        val hasBatt = g.getService(BATT_SERVICE)?.getCharacteristic(BATT_LEVEL) != null
                        sb.append(if (hasBatt) "Standard Battery Service (0x180F) FOUND.\n\n" else "No standard Battery Service (0x180F). Battery is likely reported through a proprietary protocol.\n\n")
                        g.services.forEach { s ->
                            sb.append("Service ${s.uuid}\n")
                            s.characteristics.forEach { ch -> sb.append("   ${ch.uuid}  props=0x${ch.properties.toString(16)}\n") }
                        }
                        done(sb.toString())
                    }
                }
                cont.invokeOnCancellation { try { gatt?.close() } catch (e: Throwable) {} }
                gatt = try { d.connectGatt(c, false, cb, BluetoothDevice.TRANSPORT_LE) } catch (e: Throwable) { null }
                if (gatt == null) done("GATT connection could not be started.")
            }
        } ?: "Timed out. The device did not answer over Bluetooth LE within 15 seconds."
    }
}
