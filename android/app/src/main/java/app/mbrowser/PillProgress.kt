package app.mbrowser

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Fills the address pill from the left as a page loads. Used as the pill's foreground so it sits over the background and under nothing else. */
class PillProgress(private val radiusPx: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private var frac = 0f
    private var fade = 1f
    private var fader: ValueAnimator? = null

    fun setColor(c: Int) {
        paint.color = (c and 0x00FFFFFF) or (0x40 shl 24)
        invalidateSelf()
    }

    /** 0..100 while loading; 100 completes the fill and fades it out; 0 clears at once. */
    fun set(percent: Int) {
        fader?.cancel()
        when {
            percent <= 0 -> { frac = 0f; fade = 1f }
            percent >= 100 -> {
                frac = 1f; fade = 1f
                fader = ValueAnimator.ofFloat(1f, 0f).apply {
                    startDelay = 200; duration = 300
                    addUpdateListener { fade = it.animatedValue as Float; invalidateSelf() }
                    start()
                }
            }
            else -> { frac = percent / 100f; fade = 1f }
        }
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (frac <= 0f || fade <= 0f) return
        val b = bounds
        clip.reset()
        clip.addRoundRect(RectF(b), radiusPx, radiusPx, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)
        paint.alpha = ((paint.color ushr 24) * fade).toInt()
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.left + b.width() * frac, b.bottom.toFloat(), paint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
