package app.mbrowser

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64

/** The round profile picture: a photo (data URL), an emoji, or the first letter of the name on the profile colour. */
object Avatars {
    fun make(ctx: Context, name: String, color: String, avatar: String, px: Int): Drawable {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val base = try { Color.parseColor(color) } catch (_: Exception) { Color.HSVToColor(floatArrayOf(Math.floorMod(name.hashCode(), 360).toFloat(), 0.5f, 0.55f)) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val photo = if (avatar.startsWith("data:") && avatar.contains(",")) try {
            val raw = Base64.decode(avatar.substringAfter(','), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(raw, 0, raw.size)
        } catch (_: Exception) { null } else null
        if (photo != null) {
            val r = px / 2f
            c.drawCircle(r, r, r, p)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            val side = minOf(photo.width, photo.height)
            val src = Rect((photo.width - side) / 2, (photo.height - side) / 2, (photo.width + side) / 2, (photo.height + side) / 2)
            c.drawBitmap(photo, src, Rect(0, 0, px, px), p)
        } else {
            val emoji = avatar.isNotEmpty() && !avatar.startsWith("data:")
            p.color = if (emoji) Color.argb(0x55, Color.red(base), Color.green(base), Color.blue(base)) else base
            c.drawCircle(px / 2f, px / 2f, px / 2f, p)
            val t = Paint(Paint.ANTI_ALIAS_FLAG)
            t.textAlign = Paint.Align.CENTER; t.textSize = px * 0.5f; t.color = Color.WHITE
            if (!emoji) t.typeface = Typeface.DEFAULT_BOLD
            val s = if (emoji) avatar.take(4) else (name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?').toString()
            c.drawText(s, px / 2f, px / 2f - (t.descent() + t.ascent()) / 2f, t)
        }
        return BitmapDrawable(ctx.resources, bmp)
    }
}
