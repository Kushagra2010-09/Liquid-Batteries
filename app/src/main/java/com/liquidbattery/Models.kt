package com.liquidbattery

enum class Category(val label: String, val icon: String) {
    PHONE("Phone", "📱"),
    WATCH("Smartwatch", "⌚"),
    EARBUDS("Earbuds", "🎧"),
    HEADPHONES("Headphones", "🎧"),
    SPEAKER("Speaker", "🔊"),
    KEYBOARD("Keyboard", "⌨️"),
    MOUSE("Mouse", "🖱️"),
    OTHER("Other", "🔋")
}

data class PhoneInfo(
    val level: Int,
    val charging: Boolean,
    val plug: String,
    val health: String,
    val tempC: Float
)

/** One bonded Bluetooth device. [level] is a genuine reading from this refresh, or null. */
data class DeviceState(
    val id: String,
    val systemName: String,
    val category: Category,
    val connected: Boolean,
    val level: Int?,
    val lastKnownLevel: Int?,
    val lastKnownAt: Long?,
    val source: String?,
    val updatedAt: Long
)

data class BatterySnapshot(
    val phone: PhoneInfo,
    val devices: List<DeviceState>,
    val btOn: Boolean,
    val hasPermission: Boolean,
    val updatedAt: Long
)

data class DeviceMeta(
    val id: String,
    val name: String? = null,
    val category: Category? = null,
    val hidden: Boolean = false,
    val favourite: Boolean = false,
    val inWidget: Boolean = true,
    val order: Int = 1000
)

data class UiSettings(
    val theme: Int = 0,               // 0 system, 1 light, 2 dark
    val glass: Float = 0.16f,
    val accent: Int = 0xFF7AB8FF.toInt(),
    val widgetTheme: Int = 0,         // 0 system, 1 light, 2 dark
    val widgetAlpha: Float = 0.6f,
    val widgetAccent: Int = 0xFF7AB8FF.toInt(),
    val widgetCompact: Boolean = false,
    val hideUnavailable: Boolean = false,
    val showLastKnown: Boolean = true,
    val refreshMinutes: Int = 15,
    val liveUpdates: Boolean = true
)

data class DeviceRow(
    val id: String,
    val name: String,
    val category: Category,
    val state: DeviceState,
    val meta: DeviceMeta
) {
    val level: Int? get() = state.level
    val connected: Boolean get() = state.connected
}

fun resolve(snap: BatterySnapshot?, metas: Map<String, DeviceMeta>): List<DeviceRow> =
    snap?.devices.orEmpty().map { d ->
        val m = metas[d.id] ?: DeviceMeta(d.id)
        DeviceRow(d.id, m.name?.takeIf { it.isNotBlank() } ?: d.systemName, m.category ?: d.category, d, m)
    }.sortedWith(compareBy({ it.meta.order }, { it.name.lowercase() }))

fun ago(t: Long, now: Long = System.currentTimeMillis()): String {
    val m = (now - t) / 60000
    return when {
        m < 1 -> "just now"
        m < 60 -> "${m}m ago"
        m < 1440 -> "${m / 60}h ago"
        else -> "${m / 1440}d ago"
    }
}

fun DeviceRow.status(showLast: Boolean, now: Long = System.currentTimeMillis()): String {
    val base = when {
        connected && level != null -> "Connected"
        connected -> "Connected · Battery unavailable"
        else -> "Not connected"
    }
    val lk = state.lastKnownLevel
    val at = state.lastKnownAt
    return if (level == null && showLast && lk != null && at != null) "$base · last $lk% ${ago(at, now)}" else base
}

/** Understated tier colours: >60 green, 30-60 cyan, 15-29 amber, <15 red. */
fun tierArgb(level: Int?): Int = when {
    level == null -> 0xFF8A94A6.toInt()
    level > 60 -> 0xFF6FD6A5.toInt()
    level >= 30 -> 0xFF5CC8E8.toInt()
    level >= 15 -> 0xFFF2B35C.toInt()
    else -> 0xFFEF6B6B.toInt()
}
