package com.liquidbattery

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.lbStore by preferencesDataStore("liquid_battery")

class Store(context: Context) {
    private val dataStore = context.applicationContext.lbStore

    private object K {
        val theme = intPreferencesKey("theme")
        val glass = floatPreferencesKey("glass")
        val accent = intPreferencesKey("accent")
        val wTheme = intPreferencesKey("wtheme")
        val wAlpha = floatPreferencesKey("walpha")
        val wAccent = intPreferencesKey("waccent")
        val wCompact = booleanPreferencesKey("wcompact")
        val hide = booleanPreferencesKey("whide")
        val showLast = booleanPreferencesKey("showlast")
        val refresh = intPreferencesKey("refresh")
        val snapshot = stringPreferencesKey("snapshot")
        val metas = stringPreferencesKey("metas")
    }

    private fun readSettings(p: Preferences): UiSettings {
        val d = UiSettings()
        return UiSettings(
            theme = p[K.theme] ?: d.theme,
            glass = p[K.glass] ?: d.glass,
            accent = p[K.accent] ?: d.accent,
            widgetTheme = p[K.wTheme] ?: d.widgetTheme,
            widgetAlpha = p[K.wAlpha] ?: d.widgetAlpha,
            widgetAccent = p[K.wAccent] ?: d.widgetAccent,
            widgetCompact = p[K.wCompact] ?: d.widgetCompact,
            hideUnavailable = p[K.hide] ?: d.hideUnavailable,
            showLastKnown = p[K.showLast] ?: d.showLastKnown,
            refreshMinutes = p[K.refresh] ?: d.refreshMinutes
        )
    }

    val settings: Flow<UiSettings> = dataStore.data.map { readSettings(it) }
    val snapshot: Flow<BatterySnapshot?> = dataStore.data.map { decodeSnap(it[K.snapshot]) }
    val metas: Flow<Map<String, DeviceMeta>> = dataStore.data.map { decodeMetas(it[K.metas]) }

    suspend fun updateSettings(f: (UiSettings) -> UiSettings) {
        dataStore.edit { p ->
            val n = f(readSettings(p))
            p[K.theme] = n.theme; p[K.glass] = n.glass; p[K.accent] = n.accent
            p[K.wTheme] = n.widgetTheme; p[K.wAlpha] = n.widgetAlpha; p[K.wAccent] = n.widgetAccent
            p[K.wCompact] = n.widgetCompact; p[K.hide] = n.hideUnavailable
            p[K.showLast] = n.showLastKnown; p[K.refresh] = n.refreshMinutes
        }
    }

    suspend fun saveSnapshot(s: BatterySnapshot) {
        dataStore.edit { it[K.snapshot] = encodeSnap(s) }
    }

    suspend fun updateMeta(id: String, f: (DeviceMeta) -> DeviceMeta) {
        dataStore.edit { p ->
            val m = decodeMetas(p[K.metas]).toMutableMap()
            m[id] = f(m[id] ?: DeviceMeta(id))
            p[K.metas] = encodeMetas(m.values)
        }
    }

    suspend fun setOrder(orderedIds: List<String>) {
        dataStore.edit { p ->
            val m = decodeMetas(p[K.metas]).toMutableMap()
            orderedIds.forEachIndexed { i, id -> m[id] = (m[id] ?: DeviceMeta(id)).copy(order = i) }
            p[K.metas] = encodeMetas(m.values)
        }
    }

    // ---- JSON ----
    private fun encodeSnap(s: BatterySnapshot): String {
        val o = JSONObject()
        o.put("bt", s.btOn); o.put("perm", s.hasPermission); o.put("at", s.updatedAt)
        o.put("phone", JSONObject()
            .put("l", s.phone.level).put("c", s.phone.charging).put("p", s.phone.plug)
            .put("h", s.phone.health).put("t", s.phone.tempC.toDouble()))
        val arr = JSONArray()
        s.devices.forEach { d ->
            arr.put(JSONObject()
                .put("id", d.id).put("n", d.systemName).put("cat", d.category.name)
                .put("conn", d.connected).put("l", d.level ?: -1)
                .put("lk", d.lastKnownLevel ?: -1).put("lkAt", d.lastKnownAt ?: 0L)
                .put("src", d.source ?: "").put("at", d.updatedAt))
        }
        o.put("devices", arr)
        return o.toString()
    }

    private fun decodeSnap(s: String?): BatterySnapshot? {
        if (s == null) return null
        return runCatching {
            val o = JSONObject(s)
            val ph = o.getJSONObject("phone")
            val arr = o.getJSONArray("devices")
            val list = (0 until arr.length()).map { i ->
                val j = arr.getJSONObject(i)
                DeviceState(
                    id = j.getString("id"), systemName = j.getString("n"),
                    category = runCatching { Category.valueOf(j.getString("cat")) }.getOrDefault(Category.OTHER),
                    connected = j.getBoolean("conn"),
                    level = j.optInt("l", -1).takeIf { it >= 0 },
                    lastKnownLevel = j.optInt("lk", -1).takeIf { it >= 0 },
                    lastKnownAt = j.optLong("lkAt", 0L).takeIf { it > 0 },
                    source = j.optString("src", "").takeIf { it.isNotEmpty() },
                    updatedAt = j.optLong("at", 0L)
                )
            }
            BatterySnapshot(
                PhoneInfo(ph.getInt("l"), ph.getBoolean("c"), ph.optString("p", ""), ph.optString("h", ""), ph.optDouble("t", 0.0).toFloat()),
                list, o.getBoolean("bt"), o.getBoolean("perm"), o.getLong("at")
            )
        }.getOrNull()
    }

    private fun encodeMetas(c: Collection<DeviceMeta>): String {
        val o = JSONObject()
        c.forEach { m ->
            o.put(m.id, JSONObject()
                .put("n", m.name ?: JSONObject.NULL).put("c", m.category?.name ?: JSONObject.NULL)
                .put("h", m.hidden).put("f", m.favourite).put("w", m.inWidget).put("o", m.order))
        }
        return o.toString()
    }

    private fun decodeMetas(s: String?): Map<String, DeviceMeta> {
        if (s == null) return emptyMap()
        return runCatching {
            val o = JSONObject(s)
            val out = LinkedHashMap<String, DeviceMeta>()
            val it = o.keys()
            while (it.hasNext()) {
                val id = it.next()
                val j = o.getJSONObject(id)
                out[id] = DeviceMeta(
                    id = id,
                    name = if (j.isNull("n")) null else j.getString("n"),
                    category = if (j.isNull("c")) null else runCatching { Category.valueOf(j.getString("c")) }.getOrNull(),
                    hidden = j.optBoolean("h", false), favourite = j.optBoolean("f", false),
                    inWidget = j.optBoolean("w", true), order = j.optInt("o", 1000)
                )
            }
            out
        }.getOrDefault(emptyMap())
    }
}
