package app.mbrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import java.io.File

/** Page thumbnails and favicons for the speed dial, cached on disk by host name. */
object Thumbs {
    private val mem = LruCache<String, Bitmap>(60)
    private val missing = HashSet<String>()

    fun hostOf(url: String): String =
        (try { Uri.parse(url).host } catch (_: Exception) { null } ?: "").removePrefix("www.").lowercase()

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "thumbs").also { it.mkdirs() }

    private fun load(ctx: Context, name: String): Bitmap? {
        mem.get(name)?.let { return it }
        if (name in missing) return null
        val f = File(dir(ctx), name)
        val b = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
        if (b != null) mem.put(name, b) else missing.add(name)
        return b
    }

    fun thumb(ctx: Context, host: String): Bitmap? = if (host.isEmpty()) null else load(ctx, "$host.jpg")
    fun icon(ctx: Context, host: String): Bitmap? = if (host.isEmpty()) null else load(ctx, "$host.png")

    fun ageMs(ctx: Context, host: String): Long {
        val f = File(dir(ctx), "$host.jpg")
        return if (f.exists()) System.currentTimeMillis() - f.lastModified() else Long.MAX_VALUE
    }

    fun saveThumb(ctx: Context, host: String, b: Bitmap) {
        if (host.isEmpty()) return
        try {
            File(dir(ctx), "$host.jpg").outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 78, it) }
            mem.put("$host.jpg", b); missing.remove("$host.jpg")
        } catch (_: Exception) { }
    }

    fun saveIcon(ctx: Context, host: String, b: Bitmap) {
        if (host.isEmpty()) return
        try {
            val s = if (b.width > 96) Bitmap.createScaledBitmap(b, 96, 96, true) else b
            File(dir(ctx), "$host.png").outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 100, it) }
            mem.put("$host.png", s); missing.remove("$host.png")
        } catch (_: Exception) { }
    }

    fun clear(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
        mem.evictAll(); missing.clear()
    }
}
