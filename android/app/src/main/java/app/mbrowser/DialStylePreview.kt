package app.mbrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** A small drawing of the speed dial: tiles in cards, or icons only. */
class DialStylePreview(ctx: Context, private val icons: Boolean, private val bg: Int, private val card: Int, private val text: Int, private val accent: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun d(n: Float) = n * resources.displayMetrics.density
    private val hues = floatArrayOf(210f, 30f, 340f, 140f)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        fun rr(l: Float, t: Float, r: Float, b: Float, rad: Float, col: Int) { p.color = col; c.drawRoundRect(RectF(l, t, r, b), rad, rad, p) }
        rr(0f, 0f, w, h, d(14f), bg)
        val pad = d(10f); val gap = d(8f)
        val cw = (w - pad * 2 - gap) / 2f
        val ch = (h - pad * 2 - gap) / 2f
        for (i in 0..3) {
            val x = pad + (i % 2) * (cw + gap); val y = pad + (i / 2) * (ch + gap)
            val col = Color.HSVToColor(floatArrayOf(hues[i], 0.55f, 0.75f))
            if (icons) {
                val s = minOf(cw * 0.62f, ch - d(14f))
                val ix = x + (cw - s) / 2f
                rr(ix, y, ix + s, y + s, s * 0.24f, col)
                rr(x + cw * 0.22f, y + s + d(4f), x + cw * 0.78f, y + s + d(7f), d(2f), text and 0x00FFFFFF or 0x88000000.toInt())
            } else {
                rr(x, y, x + cw, y + ch, d(8f), card)
                rr(x + d(3f), y + d(3f), x + cw - d(3f), y + ch * 0.62f, d(6f), col)
                rr(x + d(6f), y + ch * 0.74f, x + cw * 0.7f, y + ch * 0.74f + d(3f), d(2f), text and 0x00FFFFFF or 0x88000000.toInt())
            }
        }
    }
}
