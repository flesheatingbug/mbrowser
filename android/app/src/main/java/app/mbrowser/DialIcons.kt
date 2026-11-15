package app.mbrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Large site icons for the icon-only speed dial: the page's own touch icon (the one a phone's home screen would use),
 * else its largest icon. Fetched from the site itself (no third party), once, and kept on the phone.
 */
object DialIcons {
    private val mem = LruCache<String, Bitmap>(120)
    private val busy = HashSet<String>()
    private val failed = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "dialicons").also { it.mkdirs() }

    fun get(ctx: Context, host: String): Bitmap? {
        if (host.isEmpty()) return null
        mem.get(host)?.let { return it }
        val f = File(dir(ctx), "$host.png")
        if (!f.exists()) return null
        val b = BitmapFactory.decodeFile(f.path) ?: return null
        mem.put(host, b)
        return b
    }

    /** Fetch the icon in the background if we do not have it yet; [done] runs on the main thread when one arrived. */
    fun ensure(ctx: Context, url: String, done: () -> Unit) {
        val host = Thumbs.hostOf(url)
        if (host.isEmpty() || get(ctx, host) != null) return
        synchronized(busy) { if (host in failed || !busy.add(host)) return }
        val app = ctx.applicationContext
        pool.execute {
            val b = try { fetch(url) } catch (e: Exception) { null }
            synchronized(busy) { busy.remove(host); if (b == null) failed.add(host) }
            if (b != null) {
                try { File(dir(app), "$host.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (_: Exception) { }
                mem.put(host, b)
                ui.post(done)
            }
        }
    }

    fun clear(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() }; mem.evictAll(); synchronized(busy) { failed.clear() } }

    private fun open(u: String): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        return c
    }

    private fun bytes(u: String, max: Int): ByteArray? = try {
        val c = open(u)
        if (c.responseCode != 200) null else c.inputStream.use { it.readNBytes(max) }
    } catch (e: Exception) { null }

    private fun fetch(pageUrl: String): Bitmap? {
        val u = URL(pageUrl)
        val origin = u.protocol + "://" + u.host
        val cands = ArrayList<Pair<String, Int>>() // url, preference (bigger = better)
        val html = bytes(origin + "/", 300_000)?.toString(Charsets.UTF_8)
        if (html != null) {
            val base = URL(origin + "/")
            for (m in Regex("<link\\s[^>]*>", RegexOption.IGNORE_CASE).findAll(html)) {
                val tag = m.value
                val rel = Regex("rel\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.lowercase() ?: continue
                if (!rel.contains("icon")) continue
                val href = Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) ?: continue
                val size = Regex("sizes\\s*=\\s*[\"'](\\d+)x", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val abs = try { URL(base, href).toString() } catch (e: Exception) { continue }
                if (abs.endsWith(".svg", true)) continue
                cands.add(abs to (if (rel.contains("apple-touch")) 1000 + size else size))
            }
        }
        cands.add("$origin/apple-touch-icon.png" to 900)
        cands.add("$origin/apple-touch-icon-precomposed.png" to 890)
        cands.add("$origin/favicon.ico" to 1)
        var best: Bitmap? = null
        for ((link, _) in cands.sortedByDescending { it.second }.distinctBy { it.first }.take(5)) {
            val raw = bytes(link, 600_000) ?: continue
            val b = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: continue
            if (best == null || b.width > best.width) best = b
            if (b.width >= 120) break
        }
        return best?.let { tile(it) }
    }

    /** A square tile: an icon with transparent corners is set on white so it reads on any wallpaper. */
    private fun tile(src: Bitmap): Bitmap {
        val n = 192
        val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val corners = listOf(src.getPixel(0, 0), src.getPixel(src.width - 1, 0), src.getPixel(0, src.height - 1), src.getPixel(src.width - 1, src.height - 1))
        val transparent = corners.any { Color.alpha(it) < 200 }
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (transparent) {
            c.drawColor(Color.WHITE)
            val m = (n * 0.14f).toInt()
            c.drawBitmap(src, null, android.graphics.Rect(m, m, n - m, n - m), p)
        } else c.drawBitmap(src, null, android.graphics.Rect(0, 0, n, n), p)
        return out
    }

    /** A folder tile: the icons of up to four sites on a soft square. */
    fun folder(ctx: Context, urls: List<String>, size: Int, backdrop: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.color = backdrop
        val r = size * 0.22f
        c.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), r, r, p)
        val pad = size * 0.1f; val gap = size * 0.06f
        val cell = (size - pad * 2 - gap) / 2f
        for ((i, u) in urls.take(4).withIndex()) {
            val host = Thumbs.hostOf(u)
            val x = pad + (i % 2) * (cell + gap); val y = pad + (i / 2) * (cell + gap)
            val b = get(ctx, host)
            val rect = android.graphics.RectF(x, y, x + cell, y + cell)
            if (b != null) {
                val layer = c.saveLayer(rect, null)
                c.drawRoundRect(rect, cell * 0.22f, cell * 0.22f, Paint(Paint.ANTI_ALIAS_FLAG))
                val m = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG); m.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
                c.drawBitmap(b, null, rect, m)
                c.restoreToCount(layer)
            } else {
                p.color = Color.HSVToColor(floatArrayOf(Math.floorMod(host.hashCode(), 360).toFloat(), 0.45f, 0.55f))
                c.drawRoundRect(rect, cell * 0.22f, cell * 0.22f, p)
            }
        }
        return out
    }
}
