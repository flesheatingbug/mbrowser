package app.mbrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** A small drawing of the browser in one theme's colours, used as the theme picker's preview. */
class ThemePreview(
    ctx: Context, private val bg: Int, private val bar: Int, private val pill: Int, private val text: Int,
    private val accent: Int, private val ring: Int, private val selected: Boolean,
) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun d(n: Float) = n * resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        fun rr(l: Float, t: Float, r: Float, b: Float, rad: Float, col: Int) { p.style = Paint.Style.FILL; p.color = col; c.drawRoundRect(RectF(l, t, r, b), rad, rad, p) }
        rr(0f, 0f, w, h, d(16f), bg)
        // address bar with the url pill
        rr(d(6f), d(6f), w - d(6f), d(30f), d(10f), bar)
        rr(d(12f), d(12f), w - d(12f), d(24f), d(6f), pill)
        rr(d(15f), d(16f), d(30f), d(20f), d(2f), accent)
        // a 2 x 2 speed dial
        val tw = (w - d(18f)) / 2f; val th = d(26f)
        for (row in 0..1) for (col in 0..1) {
            val x = d(6f) + col * (tw + d(6f)); val y = d(38f) + row * (th + d(6f))
            rr(x, y, x + tw, y + th, d(8f), bar)
            rr(x + d(5f), y + d(5f), x + tw * 0.55f, y + d(9f), d(2f), if (row == 0 && col == 0) accent else pill)
            rr(x + d(5f), y + d(13f), x + tw - d(5f), y + d(17f), d(2f), text and 0x00FFFFFF or 0x55000000)
        }
        // the bottom bar
        rr(d(6f), h - d(26f), w - d(6f), h - d(6f), d(10f), bar)
        val gap = (w - d(12f)) / 5f
        for (i in 0..4) { p.color = if (i == 2) accent else text; p.style = Paint.Style.FILL; c.drawCircle(d(6f) + gap * (i + 0.5f), h - d(16f), d(2.6f), p) }
        // outline
        p.style = Paint.Style.STROKE
        p.strokeWidth = d(if (selected) 3f else 1f)
        p.color = ring
        val inset = p.strokeWidth / 2f
        c.drawRoundRect(RectF(inset, inset, w - inset, h - inset), d(16f), d(16f), p)
    }
}
