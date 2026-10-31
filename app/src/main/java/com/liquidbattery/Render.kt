package com.liquidbattery

import android.graphics.*

/** Bitmaps used by the home-screen widget (Glance cannot draw custom shapes). */
object Render {
    fun ring(px: Int, level: Int?, dark: Boolean, textColor: Int, sub: String?): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val sw = px * 0.085f
        val pad = sw * 1.5f
        val rect = RectF(pad, pad, px - pad, px - pad)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND
        }
        p.color = if (dark) 0x26FFFFFF else 0x1A000000
        c.drawArc(rect, 0f, 360f, false, p)
        if (level != null && level > 0) {
            val sweep = 360f * level / 100f
            val col = tierArgb(level)
            p.color = col; p.alpha = 45; p.strokeWidth = sw * 1.8f
            c.drawArc(rect, -90f, sweep, false, p)
            p.color = col; p.strokeWidth = sw
            c.drawArc(rect, -90f, sweep, false, p)
            val hi = RectF(rect); hi.inset(-sw * 0.28f, -sw * 0.28f)
            p.color = 0x59FFFFFF; p.strokeWidth = sw * 0.16f
            c.drawArc(hi, -90f, minOf(sweep, 110f), false, p)
        }
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor; textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            textSize = px * 0.25f
        }
        val cx = px / 2f
        val cy = px / 2f
        c.drawText(level?.let { "$it%" } ?: "—", cx, cy + t.textSize * 0.2f - (if (sub != null) px * 0.04f else 0f), t)
        if (sub != null) {
            t.textSize = px * 0.075f; t.alpha = 170
            c.drawText(sub, cx, cy + px * 0.19f, t)
        }
        return bmp
    }

    fun bar(w: Int, h: Int, level: Int?, dark: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = h / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (dark) 0x33FFFFFF else 0x22000000
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, p)
        if (level != null && level > 0) {
            val fw = maxOf(h.toFloat(), w * level / 100f)
            p.color = tierArgb(level)
            c.drawRoundRect(RectF(0f, 0f, fw, h.toFloat()), r, r, p)
            p.color = 0x55FFFFFF
            c.drawRoundRect(RectF(r, h * 0.15f, maxOf(r + 1f, fw - r), h * 0.35f), h * 0.1f, h * 0.1f, p)
        }
        return bmp
    }
}
