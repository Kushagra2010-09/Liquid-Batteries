package com.liquidbattery

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

val LocalGlass = compositionLocalOf { 0.16f }
val LocalDark = compositionLocalOf { true }

private val ACCENTS = listOf(0xFF7AB8FF, 0xFF6FD6A5, 0xFFB69BFF, 0xFFFF9EC4, 0xFFF2B35C, 0xFF5CE0D8).map { it.toInt() }

// ───────────────────────── Theme & glass primitives ─────────────────────────

@Composable
fun LiquidTheme(s: UiSettings, content: @Composable () -> Unit) {
    val dark = when (s.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val accent = Color(s.accent)
    val scheme = if (dark) darkColorScheme(
        primary = accent, background = Color(0xFF0B1220), surface = Color(0xFF111A2B),
        onBackground = Color(0xFFEAF0FA), onSurface = Color(0xFFEAF0FA)
    ) else lightColorScheme(
        primary = accent, background = Color(0xFFEAF1FB), surface = Color.White,
        onBackground = Color(0xFF111A2B), onSurface = Color(0xFF111A2B)
    )
    CompositionLocalProvider(LocalGlass provides s.glass, LocalDark provides dark) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    val dark = LocalDark.current
    val accent = MaterialTheme.colorScheme.primary
    val sky = if (dark) listOf(Color(0xFF0A101C), Color(0xFF14233F), Color(0xFF0B1424))
              else listOf(Color(0xFFDDE9FA), Color(0xFFF1E9FB), Color(0xFFE4F3F6))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(sky))) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(
            listOf(accent.copy(alpha = if (dark) 0.35f else 0.28f), Color.Transparent), center = Offset(200f, 400f), radius = 800f)))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(
            listOf(Color(0xFF9B7BFF).copy(alpha = if (dark) 0.25f else 0.20f), Color.Transparent), center = Offset(900f, 1600f), radius = 900f)))
        content()
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, pad: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    val a = LocalGlass.current + if (LocalDark.current) 0f else 0.35f
    val dark = LocalDark.current
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier.clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = (a + 0.08f).coerceAtMost(1f)), Color.White.copy(alpha = a.coerceAtMost(1f)))), shape)
            .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = if (dark) 0.45f else 0.9f), Color.White.copy(alpha = 0.06f))), shape)
            .padding(pad),
        content = content
    )
}

@Composable
fun SectionLabel(t: String) {
    Text(t.uppercase(), fontSize = 11.sp, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        modifier = Modifier.padding(start = 6.dp, top = 4.dp))
}

@Composable
fun Header(title: String, btOn: Boolean?, onSettings: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("LIQUID BATTERY", fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
            Text(title, fontSize = 32.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onBackground)
        }
        if (btOn != null) {
            val c = if (btOn) Color(0xFF6FD6A5) else Color(0xFF8A94A6)
            Row(Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(6.dp))
                Text(if (btOn) "Bluetooth" else "Bluetooth off", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground)
            }
        }
        if (onSettings != null) {
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

// ───────────────────────── Battery visuals ─────────────────────────

@Composable
fun BatteryRing(level: Int?, ringSize: Dp, modifier: Modifier = Modifier, sub: String? = null) {
    val p by animateFloatAsState((level ?: 0) / 100f, tween(900, easing = FastOutSlowInEasing), label = "ring")
    val color = Color(tierArgb(level))
    val track = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)
    val textColor = MaterialTheme.colorScheme.onBackground
    val sw = ringSize.value * 0.085f
    Box(modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = sw.dp.toPx()
            val inset = w * 1.5f
            val tl = Offset(inset, inset)
            val sz = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            drawArc(color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = tl, size = sz, style = Stroke(w))
            if (p > 0.001f) {
                val sweep = 360f * p
                drawArc(color = color.copy(alpha = 0.14f), startAngle = -90f, sweepAngle = sweep, useCenter = false, topLeft = tl, size = sz, style = Stroke(w * 2.0f, cap = StrokeCap.Round))
                drawArc(color = color.copy(alpha = 0.26f), startAngle = -90f, sweepAngle = sweep, useCenter = false, topLeft = tl, size = sz, style = Stroke(w * 1.4f, cap = StrokeCap.Round))
                drawArc(color = color, startAngle = -90f, sweepAngle = sweep, useCenter = false, topLeft = tl, size = sz, style = Stroke(w, cap = StrokeCap.Round))
                val o = w * 0.28f
                drawArc(color = Color.White.copy(alpha = 0.38f), startAngle = -90f, sweepAngle = minOf(sweep, 110f), useCenter = false,
                    topLeft = Offset(tl.x - o, tl.y - o), size = Size(sz.width + o * 2, sz.height + o * 2), style = Stroke(w * 0.16f, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(level?.toString() ?: "—", fontSize = (ringSize.value * 0.30f).sp, fontWeight = FontWeight.Light, color = textColor)
                if (level != null) Text("%", fontSize = (ringSize.value * 0.11f).sp, fontWeight = FontWeight.Light, color = textColor.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = (ringSize.value * 0.05f).dp, start = 2.dp))
            }
            if (sub != null) Text(sub, fontSize = (ringSize.value * 0.085f).sp, color = textColor.copy(alpha = 0.65f))
        }
    }
}

@Composable
fun LevelBar(level: Int?, modifier: Modifier = Modifier) {
    val p by animateFloatAsState((level ?: 0) / 100f, tween(700, easing = FastOutSlowInEasing), label = "bar")
    val c = Color(tierArgb(level))
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f))) {
        if (level != null) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceAtLeast(0.03f)).clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.7f), c))))
        }
    }
}

@Composable
fun DeviceBarRow(r: DeviceRow, showLast: Boolean) {
    val on = MaterialTheme.colorScheme.onBackground
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(r.category.icon, fontSize = 22.sp, modifier = Modifier.width(38.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, fontSize = 15.sp, maxLines = 1, color = on, modifier = Modifier.weight(1f))
                Text(r.level?.let { "$it%" } ?: "—", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (r.level != null) on else on.copy(alpha = 0.5f))
            }
            Spacer(Modifier.height(5.dp))
            LevelBar(r.level)
            Spacer(Modifier.height(4.dp))
            Text(r.status(showLast), fontSize = 11.sp, color = on.copy(alpha = 0.6f))
        }
    }
}

// ───────────────────────── Screens ─────────────────────────

@Composable
fun Dashboard(snap: BatterySnapshot?, rows: List<DeviceRow>, s: UiSettings, onPermission: () -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit) {
    val on = MaterialTheme.colorScheme.onBackground
    val vis = rows.filter { !it.meta.hidden }
    val rings = vis.filter { it.category == Category.WATCH || it.meta.favourite }
    val bars = vis.filter { it !in rings }
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Header("Dashboard", snap?.btOn, onSettings) }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BatteryRing(snap?.phone?.level, 168.dp, sub = if (snap?.phone?.charging == true) "⚡ Charging" else null)
                    Spacer(Modifier.width(18.dp))
                    Column {
                        Text("Phone", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = on)
                        val ph = snap?.phone
                        Text(
                            if (ph == null) "Reading…" else if (ph.charging) "Charging" + (ph.plug.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "") else "On battery",
                            fontSize = 13.sp, color = on.copy(alpha = 0.7f)
                        )
                        if (ph != null && ph.health.isNotEmpty()) Text("Health: ${ph.health}", fontSize = 13.sp, color = on.copy(alpha = 0.7f))
                        if (ph != null && ph.tempC > 0f) Text("Temp: ${"%.1f".format(ph.tempC)} °C", fontSize = 13.sp, color = on.copy(alpha = 0.7f))
                    }
                }
            }
        }
        if (snap != null && !snap.hasPermission) item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Bluetooth permission needed", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = on)
                Text("Liquid Battery needs the nearby-devices permission to see which paired devices are connected. It never scans or tracks location.",
                    fontSize = 13.sp, color = on.copy(alpha = 0.7f), modifier = Modifier.padding(vertical = 6.dp))
                Button(onClick = onPermission) { Text("Grant permission") }
            }
        }
        if (rings.isNotEmpty()) {
            item { SectionLabel("Watch & favourites") }
            items(rings.chunked(2)) { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pair.forEach { r ->
                        GlassCard(Modifier.weight(1f)) {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                BatteryRing(r.level, 120.dp)
                                Spacer(Modifier.height(8.dp))
                                Text("${r.category.icon} ${r.name}", fontSize = 14.sp, maxLines = 1, color = on)
                                Text(r.status(s.showLastKnown), fontSize = 11.sp, color = on.copy(alpha = 0.6f), maxLines = 2)
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (bars.isNotEmpty()) {
            item { SectionLabel("Connected devices") }
            item {
                GlassCard(Modifier.fillMaxWidth()) {
                    bars.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(color = on.copy(alpha = 0.08f))
                        DeviceBarRow(r, s.showLastKnown)
                    }
                }
            }
        }
        if (vis.isEmpty() && snap != null && snap.hasPermission) item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(if (snap.btOn) "No paired devices found. Pair a device in system Bluetooth settings, then open the Devices tab." else "Bluetooth is off.",
                    fontSize = 14.sp, color = on.copy(alpha = 0.8f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(snap?.let { "Updated ${ago(it.updatedAt)}" } ?: "Not updated yet", fontSize = 12.sp, color = on.copy(alpha = 0.55f), modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
        }
    }
}

@Composable
fun DevicesScreen(rows: List<DeviceRow>, s: UiSettings, store: Store, scope: CoroutineScope) {
    val ctx = LocalContext.current
    val on = MaterialTheme.colorScheme.onBackground
    var probeText by remember { mutableStateOf<String?>(null) }
    var probeName by remember { mutableStateOf("") }

    fun refreshWidget() { scope.launch { LiquidWidget().updateAll(ctx) } }
    fun move(index: Int, delta: Int) {
        val ids = rows.map { it.id }.toMutableList()
        val j = index + delta
        if (j !in ids.indices) return
        val t = ids[index]; ids[index] = ids[j]; ids[j] = t
        scope.launch { store.setOrder(ids); LiquidWidget().updateAll(ctx) }
    }

    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Header("Devices", null, null) }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Paired devices are listed below. Battery is shown only when the device genuinely reports it. Use Probe to check what a device exposes.",
                    fontSize = 13.sp, color = on.copy(alpha = 0.75f))
                TextButton(onClick = { ctx.startActivity(Intent("android.settings.BLUETOOTH_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                    Text("Pair a new device")
                }
            }
        }
        if (rows.isEmpty()) item { Text("No paired devices yet.", color = on.copy(alpha = 0.6f), modifier = Modifier.padding(6.dp)) }
        items(rows.size) { idx ->
            val r = rows[idx]
            var rename by remember { mutableStateOf(false) }
            var menu by remember { mutableStateOf(false) }
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.category.icon, fontSize = 24.sp, modifier = Modifier.width(40.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = on, maxLines = 1)
                        Text(r.status(s.showLastKnown), fontSize = 11.sp, color = on.copy(alpha = 0.6f))
                        Text(r.state.source?.let { "Source: $it" } ?: "No battery source available", fontSize = 11.sp, color = on.copy(alpha = 0.5f))
                    }
                    IconButton(onClick = { scope.launch { store.updateMeta(r.id) { it.copy(favourite = !it.favourite) }; LiquidWidget().updateAll(ctx) } }) {
                        Icon(Icons.Default.Star, contentDescription = "Favourite",
                            tint = if (r.meta.favourite) Color(0xFFF2B35C) else on.copy(alpha = 0.25f))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton(onClick = { menu = true }) { Text(r.category.label) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            Category.entries.filter { it != Category.PHONE }.forEach { c ->
                                DropdownMenuItem(text = { Text("${c.icon} ${c.label}") }, onClick = {
                                    menu = false
                                    scope.launch { store.updateMeta(r.id) { it.copy(category = c) }; LiquidWidget().updateAll(ctx) }
                                })
                            }
                        }
                    }
                    TextButton(onClick = { rename = true }) { Text("Rename") }
                    TextButton(onClick = {
                        probeName = r.name; probeText = "Probing… keep the device nearby and awake."
                        scope.launch { probeText = Reader.probe(ctx, r.id) }
                    }) { Text("Probe") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { move(idx, -1) }, enabled = idx > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                    IconButton(onClick = { move(idx, 1) }, enabled = idx < rows.size - 1) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = !r.meta.hidden, onCheckedChange = { v -> scope.launch { store.updateMeta(r.id) { it.copy(hidden = !v) }; LiquidWidget().updateAll(ctx) } })
                    Text("  Dashboard", fontSize = 13.sp, color = on)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = r.meta.inWidget, onCheckedChange = { v -> scope.launch { store.updateMeta(r.id) { it.copy(inWidget = v) }; LiquidWidget().updateAll(ctx) } })
                    Text("  Widget", fontSize = 13.sp, color = on)
                }
            }
            if (rename) {
                var text by remember { mutableStateOf(r.meta.name ?: r.state.systemName) }
                AlertDialog(
                    onDismissRequest = { rename = false },
                    title = { Text("Rename device") },
                    text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
                    confirmButton = {
                        TextButton(onClick = {
                            rename = false
                            scope.launch { store.updateMeta(r.id) { it.copy(name = text.trim().ifEmpty { null }) }; LiquidWidget().updateAll(ctx) }
                        }) { Text("Save") }
                    },
                    dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } }
                )
            }
        }
    }
    if (probeText != null) {
        AlertDialog(
            onDismissRequest = { probeText = null },
            title = { Text("Probe: $probeName") },
            text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(probeText ?: "", fontSize = 12.sp) } },
            confirmButton = { TextButton(onClick = { probeText = null }) { Text("Close") } }
        )
    }
}

@Composable
fun <T> Choice(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, v) ->
            FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label) })
        }
    }
}

@Composable
fun AccentPicker(selected: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ACCENTS.forEach { c ->
            Box(Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                .border(if (c == selected) 3.dp else 1.dp, Color.White.copy(alpha = if (c == selected) 0.95f else 0.4f), CircleShape)
                .clickable { onPick(c) })
        }
    }
}

@Composable
fun WidgetSettingsSection(s: UiSettings, change: ((UiSettings) -> UiSettings) -> Unit) {
    val on = MaterialTheme.colorScheme.onBackground
    Text("Appearance", fontSize = 13.sp, color = on.copy(alpha = 0.7f))
    Choice(listOf("System" to 0, "Light" to 1, "Dark" to 2), s.widgetTheme) { v -> change { it.copy(widgetTheme = v) } }
    Text("Transparency", fontSize = 13.sp, color = on.copy(alpha = 0.7f), modifier = Modifier.padding(top = 10.dp))
    Slider(value = s.widgetAlpha, onValueChange = { v -> change { it.copy(widgetAlpha = v) } }, valueRange = 0.15f..0.95f)
    Text("Accent colour", fontSize = 13.sp, color = on.copy(alpha = 0.7f))
    Spacer(Modifier.height(6.dp))
    AccentPicker(s.widgetAccent) { c -> change { it.copy(widgetAccent = c) } }
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Expanded layout (connection status lines)", fontSize = 14.sp, color = on, modifier = Modifier.weight(1f))
        Switch(checked = !s.widgetCompact, onCheckedChange = { v -> change { it.copy(widgetCompact = !v) } })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Hide devices that aren't connected", fontSize = 14.sp, color = on, modifier = Modifier.weight(1f))
        Switch(checked = s.hideUnavailable, onCheckedChange = { v -> change { it.copy(hideUnavailable = v) } })
    }
}

@Composable
fun SettingsScreen(s: UiSettings, store: Store, scope: CoroutineScope) {
    val ctx = LocalContext.current
    val on = MaterialTheme.colorScheme.onBackground
    val change: ((UiSettings) -> UiSettings) -> Unit = { f -> scope.launch { store.updateSettings(f); LiquidWidget().updateAll(ctx) } }
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Header("Settings", null, null) }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("App theme", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = on)
                Spacer(Modifier.height(8.dp))
                Choice(listOf("System" to 0, "Light" to 1, "Dark" to 2), s.theme) { v -> change { it.copy(theme = v) } }
                Text("Glass transparency", fontSize = 13.sp, color = on.copy(alpha = 0.7f), modifier = Modifier.padding(top = 12.dp))
                Slider(value = s.glass, onValueChange = { v -> change { it.copy(glass = v) } }, valueRange = 0.06f..0.35f)
                Text("Accent colour", fontSize = 13.sp, color = on.copy(alpha = 0.7f))
                Spacer(Modifier.height(6.dp))
                AccentPicker(s.accent) { c -> change { it.copy(accent = c) } }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Battery display", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = on)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show last known value (with time) when unavailable", fontSize = 14.sp, color = on, modifier = Modifier.weight(1f))
                    Switch(checked = s.showLastKnown, onCheckedChange = { v -> change { it.copy(showLastKnown = v) } })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Live updates: refresh the instant phone battery changes (small silent notification)", fontSize = 14.sp, color = on, modifier = Modifier.weight(1f))
                    Switch(checked = s.liveUpdates, onCheckedChange = { v -> change { it.copy(liveUpdates = v) }; Live.sync(ctx, v) })
                }
                Text("Background refresh (Android minimum is 15 min)", fontSize = 13.sp, color = on.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                Choice(listOf("15 min" to 15, "30 min" to 30, "1 hour" to 60), s.refreshMinutes) { v -> change { it.copy(refreshMinutes = v) } }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Widget", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = on)
                Spacer(Modifier.height(8.dp))
                WidgetSettingsSection(s, change)
                Text("Choose which devices appear in the widget, and their order, on the Devices tab.", fontSize = 12.sp, color = on.copy(alpha = 0.55f), modifier = Modifier.padding(top = 8.dp))
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Permissions & privacy", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = on)
                Text("• Uses only the Bluetooth \"nearby devices\" permission to read paired devices and their battery.\n" +
                    "• No location, no internet, no accounts, no analytics. Everything stays on your phone.\n" +
                    "• Never scans continuously. Refreshes on connect/disconnect events and roughly every 15 minutes.\n" +
                    "• Devices that don't expose battery data show \"Battery unavailable\". Values are never guessed.",
                    fontSize = 13.sp, color = on.copy(alpha = 0.75f), modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
fun BottomBar(tab: Int, onTab: (Int) -> Unit) {
    Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        GlassCard(Modifier.fillMaxWidth(), pad = 4.dp) {
            Row(Modifier.fillMaxWidth()) {
                listOf("Dashboard", "Devices", "Settings").forEachIndexed { i, label ->
                    TextButton(onClick = { onTab(i) }, modifier = Modifier.weight(1f)) {
                        Text(label, fontWeight = if (i == tab) FontWeight.Bold else FontWeight.Normal,
                            color = if (i == tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

@Composable
fun AppRoot(store: Store, s: UiSettings, snap: BatterySnapshot?, metas: Map<String, DeviceMeta>, requestPermission: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(tab != 0) { tab = 0 }
    LaunchedEffect(s.refreshMinutes) { Scheduler.periodic(ctx, s.refreshMinutes, update = true) }
    val rows = remember(snap, metas) { resolve(snap, metas) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> Dashboard(snap, rows, s, requestPermission, { Scheduler.now(ctx) }, { tab = 2 })
                1 -> DevicesScreen(rows, s, store, scope)
                else -> SettingsScreen(s, store, scope)
            }
        }
        BottomBar(tab) { tab = it }
    }
}

@Composable
fun WidgetConfigScreen(s: UiSettings, rows: List<DeviceRow>, store: Store, scope: CoroutineScope, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val on = MaterialTheme.colorScheme.onBackground
    val change: ((UiSettings) -> UiSettings) -> Unit = { f -> scope.launch { store.updateSettings(f) } }
    LazyColumn(Modifier.statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Header("Widget", null, null) }
        item { GlassCard(Modifier.fillMaxWidth()) { WidgetSettingsSection(s, change) } }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Devices in widget", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = on)
                if (rows.isEmpty()) Text("No paired devices yet.", fontSize = 13.sp, color = on.copy(alpha = 0.6f))
                rows.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${r.category.icon}  ${r.name}", fontSize = 14.sp, color = on, modifier = Modifier.weight(1f), maxLines = 1)
                        Switch(checked = r.meta.inWidget, onCheckedChange = { v -> scope.launch { store.updateMeta(r.id) { it.copy(inWidget = v) } } })
                    }
                }
            }
        }
        item {
            Button(onClick = { scope.launch { LiquidWidget().updateAll(ctx); onDone() } }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}
