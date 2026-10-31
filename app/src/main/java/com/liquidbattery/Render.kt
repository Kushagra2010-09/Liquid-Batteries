package com.liquidbattery

import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils

class WDev(val icon: String, val name: String, val level: Int?, val status: String)

class WArgs(
    val dark: Boolean,
    val panelAlpha: Float,
    val accent: Int,
    val phoneLevel: Int?,
    val charging: Boolean,
    val plug: String,
    val health: String,
    val btOn: Boolean,
    val devices: List<WDev>,
    val expanded: Boolean,
    val updated: String
)

/**
 * Draws the entire widget (glass panel, rings, bars, text) as ONE bitmap at the widget's exact size.
 * This gives full control over the look on every Android version.
 */
object WidgetArt {
    private val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    private fun al(color: Int, a: Float): Int = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((((a shr s) and 0xFF) * (1 - t)) + (((b shr s) and 0xFF) * t)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun lighten(c: Int) = mix(c, 0xFFFFFFFF.toInt(), 0.45f)

    private fun tp(size: Float, color: Int, tf: Typeface, align: Paint.Align = Paint.Align.LEFT): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = tf; textAlign = align }

    private fun fit(s: String, p: TextPaint, maxW: Float): String =
        if (maxW <= 0f) "" else TextUtils.ellipsize(s, p, maxW, TextUtils.TruncateAt.END).toString()

    fun draw(wDp: Float, hDp: Float, density: Float, a: WArgs): Bitmap {
        val u = minOf(density, Math.sqrt(1_200_000.0 / (wDp * hDp).toDouble()).toFloat()).coerceAtLeast(1f)
        val W = (wDp * u).toInt().coerceAtLeast(16)
        val H = (hDp * u).toInt().coerceAtLeast(16)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val w = W.toFloat()
        val h = H.toFloat()
        panel(c, w, h, u, a)

        val ink = if (a.dark) 0xFFF4F7FF.toInt() else 0xFF0F1A2E.toInt()
        val dim = al(ink, 0.6f)
        val pad = 14f * u
        val btColor = if (a.btOn) 0xFF6FD6A5.toInt() else dim

        // ── Small: just a beautiful ring
        if (wDp < 170f && hDp < 170f) {
            val d = minOf(w, h) - 2 * 17f * u
            ring(c, w / 2f, h / 2f + 2f * u, d, a.phoneLevel, u, ink, dim, a.dark, if (a.charging) "⚡ Charging" else "Phone")
            c.drawCircle(pad, pad + 3f * u, 3f * u, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = btColor })
            c.drawText("↻", w - pad, pad + 10f * u, tp(15f * u, dim, regular, Paint.Align.RIGHT))
            return bmp
        }

        header(c, w, pad, u, a, dim, btColor)
        val top = pad + 24f * u
        val footerH = if (hDp >= 200f) 16f * u else 0f
        val bottom = h - pad - footerH
        val rowH = (if (a.expanded) 44f else 34f) * u
        val sub = if (a.charging) "⚡ Charging" else "Phone"

        if (wDp >= 200f && hDp < 200f) {
            val contentH = bottom - top
            val d = minOf(contentH, 118f * u)
            ring(c, pad + d / 2f, top + contentH / 2f, d, a.phoneLevel, u, ink, dim, a.dark, sub)
            val lx = pad + d + 14f * u
            list(c, lx, top, w - pad - lx, contentH, rowH, a, ink, dim, u)
        } else {
            val d = minOf(w * 0.40f, 104f * u, (bottom - top) * 0.55f)
            ring(c, pad + d / 2f, top + d / 2f, d, a.phoneLevel, u, ink, dim, a.dark, sub)
            val tx = pad + d + 14f * u
            val tw = w - pad - tx
            c.drawText(fit("Phone", tp(16f * u, ink, medium), tw), tx, top + d * 0.40f, tp(16f * u, ink, medium))
            val line = if (a.charging) "Charging" + (if (a.plug.isNotEmpty()) " · ${a.plug}" else "") else "On battery"
            val p2 = tp(11f * u, dim, regular)
            c.drawText(fit(line, p2, tw), tx, top + d * 0.40f + 17f * u, p2)
            if (a.expanded && a.health.isNotEmpty()) c.drawText(fit("Health: ${a.health}", p2, tw), tx, top + d * 0.40f + 32f * u, p2)
            val ly = top + d + 10f * u
            list(c, pad, ly, w - 2 * pad, bottom - ly, rowH, a, ink, dim, u)
            if (footerH > 0f) {
                val fp = tp(9f * u, dim, regular)
                c.drawText(a.updated, pad, h - pad, fp)
                c.drawText("Tap to open", w - pad, h - pad, tp(9f * u, dim, regular, Paint.Align.RIGHT))
            }
        }
        return bmp
    }

    // ── glass panel with liquid colour blobs, gloss and a light-catching border
    private fun panel(c: Canvas, w: Float, h: Float, u: Float, a: WArgs) {
        val r = 26f * u
        val rect = RectF(0.6f * u, 0.6f * u, w - 0.6f * u, h - 0.6f * u)
        val path = Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) }
        val top = if (a.dark) 0xFF2A3E68.toInt() else 0xFFFFFFFF.toInt()
        val bot = if (a.dark) 0xFF0C1630.toInt() else 0xFFDCE7F8.toInt()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.shader = LinearGradient(0f, 0f, 0f, h, al(top, a.panelAlpha), al(bot, a.panelAlpha), Shader.TileMode.CLAMP)
        c.drawPath(path, fill)

        c.save()
        c.clipPath(path)
        val k = 0.4f + 0.6f * a.panelAlpha
        val big = maxOf(w, h)
        blob(c, w * 0.12f, h * 0.05f, big * 0.60f, a.accent, 0.42f * k)
        blob(c, w * 0.98f, h * 0.98f, big * 0.65f, 0xFF9B7BFF.toInt(), 0.32f * k)
        blob(c, w * 0.90f, h * 0.10f, big * 0.35f, 0xFF4DE0C8.toInt(), 0.14f * k)
        val g = Paint()
        g.shader = LinearGradient(0f, 0f, 0f, h * 0.5f, if (a.dark) 0x30FFFFFF else 0x99FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h * 0.5f, g)
        c.restore()

        val b = Paint(Paint.ANTI_ALIAS_FLAG)
        b.style = Paint.Style.STROKE
        b.strokeWidth = 1.2f * u
        b.shader = LinearGradient(0f, 0f, w, h, if (a.dark) 0x80FFFFFF.toInt() else 0xFFFFFFFF.toInt(), 0x10FFFFFF, Shader.TileMode.CLAMP)
        c.drawPath(path, b)
    }

    private fun blob(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, alpha: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(cx, cy, r, al(color, alpha), al(color, 0f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
    }

    private fun header(c: Canvas, w: Float, pad: Float, u: Float, a: WArgs, dim: Int, btColor: Int) {
        val bp = tp(9f * u, a.accent, medium)
        bp.letterSpacing = 0.2f
        c.drawText("LIQUID BATTERY", pad, pad + 9f * u, bp)
        c.drawText("↻", w - pad, pad + 11f * u, tp(15f * u, dim, regular, Paint.Align.RIGHT))
        val t = tp(9f * u, dim, medium, Paint.Align.RIGHT)
        val tx = w - pad - 24f * u
        c.drawText("BT", tx, pad + 9f * u, t)
        c.drawCircle(tx - t.measureText("BT") - 6f * u, pad + 6.2f * u, 3f * u, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = btColor })
    }

    // ── the main ring: sweep gradient, soft glow, liquid bead at the tip
    private fun ring(c: Canvas, cx: Float, cy: Float, d: Float, level: Int?, u: Float, ink: Int, dim: Int, dark: Boolean, sub: String?) {
        if (d < 20f) return
        val sw = d * 0.095f
        val r = d / 2f - sw * 1.3f
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)

        // soft glass disc behind the number
        val disc = Paint(Paint.ANTI_ALIAS_FLAG)
        disc.shader = RadialGradient(cx, cy - r * 0.3f, r * 1.1f, al(0xFFFFFFFF.toInt(), if (dark) 0.10f else 0.45f), al(0xFFFFFFFF.toInt(), 0f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r - sw * 0.4f, disc)

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = sw
        p.color = if (dark) 0x24FFFFFF else 0x1A000000
        c.drawArc(rect, 0f, 360f, false, p)

        if (level != null && level > 0) {
            val f = level / 100f
            val sweep = 360f * f
            val base = tierArgb(level)
            // glow
            val glow = Paint(Paint.ANTI_ALIAS_FLAG)
            glow.style = Paint.Style.STROKE; glow.strokeCap = Paint.Cap.ROUND; glow.strokeWidth = sw * 1.3f
            glow.color = al(base, 0.55f)
            glow.maskFilter = BlurMaskFilter(sw * 0.9f, BlurMaskFilter.Blur.NORMAL)
            c.drawArc(rect, -90f, sweep, false, glow)
            // gradient arc
            val sg = SweepGradient(cx, cy, intArrayOf(lighten(base), base, base), floatArrayOf(0f, f.coerceAtLeast(0.02f), 1f))
            val m = Matrix(); m.postRotate(-90f, cx, cy); sg.setLocalMatrix(m)
            p.shader = sg
            c.drawArc(rect, -90f, sweep, false, p)
            p.shader = null
            // glass highlight on the outer edge
            val hi = RectF(rect); hi.inset(-sw * 0.28f, -sw * 0.28f)
            p.color = 0x66FFFFFF; p.strokeWidth = sw * 0.14f
            c.drawArc(hi, -90f, minOf(sweep, 120f), false, p)
            // bead at the tip
            if (f in 0.03f..0.985f) {
                val ang = Math.toRadians((-90f + sweep).toDouble())
                val bx = cx + r * Math.cos(ang).toFloat()
                val by = cy + r * Math.sin(ang).toFloat()
                val bp = Paint(Paint.ANTI_ALIAS_FLAG)
                bp.color = 0xE6FFFFFF.toInt()
                c.drawCircle(bx, by, sw * 0.2f, bp)
            }
        }

        // number
        val numSize = d * 0.29f
        val shift = if (sub != null) d * 0.05f else 0f
        if (level == null) {
            c.drawText("—", cx, cy + numSize * 0.18f - shift, tp(numSize, dim, light, Paint.Align.CENTER))
        } else {
            val np = tp(numSize, ink, light)
            val pp = tp(d * 0.12f, al(ink, 0.65f), light)
            val s = level.toString()
            val nw = np.measureText(s)
            val gap = 2f * u
            val total = nw + gap + pp.measureText("%")
            val x0 = cx - total / 2f
            val by = cy + numSize * 0.18f - shift
            c.drawText(s, x0, by, np)
            c.drawText("%", x0 + nw + gap, by, pp)
        }
        if (sub != null) {
            val sp = tp(d * 0.078f, dim, regular, Paint.Align.CENTER)
            c.drawText(fit(sub, sp, d * 0.7f), cx, cy + d * 0.21f, sp)
        }
    }

    private fun list(c: Canvas, x: Float, y: Float, wd: Float, hd: Float, rowH: Float, a: WArgs, ink: Int, dim: Int, u: Float) {
        if (wd < 40f * u) return
        if (a.devices.isEmpty()) {
            val p = tp(11f * u, dim, regular)
            c.drawText(fit("No connected devices", p, wd), x, y + hd / 2f + 4f * u, p)
            return
        }
        val n = (hd / rowH).toInt().coerceAtLeast(if (hd > 26f * u) 1 else 0)
        a.devices.take(n).forEachIndexed { i, d -> row(c, x, y + i * rowH, wd, d, a, ink, dim, u) }
    }

    private fun row(c: Canvas, x: Float, y: Float, wd: Float, d: WDev, a: WArgs, ink: Int, dim: Int, u: Float) {
        val chipR = 11f * u
        val chipCx = x + chipR
        val chipCy = y + 14f * u
        val chip = Paint(Paint.ANTI_ALIAS_FLAG)
        chip.color = al(ink, 0.10f)
        c.drawCircle(chipCx, chipCy, chipR, chip)
        chip.style = Paint.Style.STROKE; chip.strokeWidth = 1f * u; chip.color = al(ink, 0.18f)
        c.drawCircle(chipCx, chipCy, chipR, chip)
        val ip = tp(11f * u, ink, regular, Paint.Align.CENTER)
        c.drawText(d.icon, chipCx, chipCy + 4f * u, ip)

        val x0 = x + 29f * u
        val colW = wd - 29f * u
        val pct = d.level?.let { "$it%" } ?: "—"
        val pp = tp(12f * u, if (d.level != null) ink else dim, medium, Paint.Align.RIGHT)
        val pctW = pp.measureText("100%")
        c.drawText(pct, x + wd, y + 12f * u, pp)
        val np = tp(12f * u, ink, regular)
        c.drawText(fit(d.name, np, colW - pctW - 6f * u), x0, y + 12f * u, np)

        // bar
        val bt = y + 19f * u
        val bh = 4f * u
        val tr = Paint(Paint.ANTI_ALIAS_FLAG)
        tr.color = al(ink, 0.13f)
        c.drawRoundRect(RectF(x0, bt, x0 + colW, bt + bh), bh / 2f, bh / 2f, tr)
        if (d.level != null && d.level > 0) {
            val fw = maxOf(bh, colW * d.level / 100f)
            val col = tierArgb(d.level)
            val gl = Paint(Paint.ANTI_ALIAS_FLAG)
            gl.color = al(col, 0.45f)
            gl.maskFilter = BlurMaskFilter(3f * u, BlurMaskFilter.Blur.NORMAL)
            c.drawRoundRect(RectF(x0, bt, x0 + fw, bt + bh), bh / 2f, bh / 2f, gl)
            val fp = Paint(Paint.ANTI_ALIAS_FLAG)
            fp.shader = LinearGradient(x0, 0f, x0 + fw, 0f, lighten(col), col, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(x0, bt, x0 + fw, bt + bh), bh / 2f, bh / 2f, fp)
        }
        if (a.expanded) {
            val sp = tp(8.5f * u, dim, regular)
            c.drawText(fit(d.status, sp, colW), x0, y + 34f * u, sp)
        }
    }
}
