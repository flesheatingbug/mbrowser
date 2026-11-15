package app.mbrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import java.io.File

/** Start page wallpapers: the built-in ones (original artwork, made by tools/gen_wallpapers.py, CC0) or your own photo. */
object Wallpapers {
    val LIST = listOf(
        "aurora" to "Aurora", "dusk" to "Dusk", "ocean" to "Ocean", "dunes" to "Dunes", "mist" to "Mist", "forest" to "Forest",
        "ember" to "Ember", "orchid" to "Orchid", "slate" to "Slate", "nebula" to "Nebula", "peaks" to "Peaks", "paper" to "Paper",
    )

    private var cacheKey = ""
    private var cacheDrawable: Drawable? = null

    private fun customFile(ctx: Context) = File(ctx.applicationContext.filesDir, "wallpaper.jpg")

    private fun resOf(ctx: Context, id: String) = ctx.resources.getIdentifier("wp_$id", "drawable", ctx.packageName)

    private fun sampled(open: () -> java.io.InputStream?, reqW: Int, reqH: Int): Bitmap? {
        val o = BitmapFactory.Options(); o.inJustDecodeBounds = true
        open()?.use { BitmapFactory.decodeStream(it, null, o) }
        var n = 1
        while (o.outWidth / (n * 2) >= reqW && o.outHeight / (n * 2) >= reqH) n *= 2
        val o2 = BitmapFactory.Options(); o2.inSampleSize = n
        return open()?.use { BitmapFactory.decodeStream(it, null, o2) }
    }

    /** The wallpaper to show behind the speed dial, or null for none. */
    fun drawable(ctx: Context): Drawable? {
        val id = Prefs(ctx).wallpaper
        if (id == "none" || id.isEmpty()) return null
        val key = id + (if (id == "custom") customFile(ctx).lastModified() else 0L)
        if (key == cacheKey && cacheDrawable != null) return cacheDrawable
        val dm = ctx.resources.displayMetrics
        val bmp = if (id == "custom") {
            val f = customFile(ctx)
            if (!f.exists()) null else sampled({ f.inputStream() }, dm.widthPixels, dm.heightPixels)
        } else {
            val r = resOf(ctx, id)
            if (r == 0) null else sampled({ ctx.resources.openRawResource(r) }, dm.widthPixels, dm.heightPixels)
        }
        cacheKey = key
        cacheDrawable = bmp?.let { BitmapDrawable(ctx.resources, it) }
        return cacheDrawable
    }

    /** A small version for the picker. */
    fun thumb(ctx: Context, id: String): Bitmap? {
        if (id == "custom") { val f = customFile(ctx); return if (f.exists()) sampled({ f.inputStream() }, 160, 280) else null }
        val r = resOf(ctx, id)
        return if (r == 0) null else sampled({ ctx.resources.openRawResource(r) }, 160, 280)
    }

    fun hasCustom(ctx: Context) = customFile(ctx).exists()

    /** Copies a picked photo, scaled down, so it keeps working if the original is moved. */
    fun saveCustom(ctx: Context, uri: Uri): Boolean {
        return try {
            val b = sampled({ ctx.contentResolver.openInputStream(uri) }, 1440, 2560) ?: return false
            customFile(ctx).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            cacheKey = ""
            true
        } catch (e: Exception) { false }
    }
}
