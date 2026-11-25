package app.mbrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Wallpapers from the internet, sized for this phone: Bing's daily pictures and the Picsum photo library (no account or key). */
object OnlineWallpapers {
    class Item(val thumb: String, val full: String, val label: String)
    class Page(val items: List<Item>, val more: Boolean)

    val SOURCES = listOf("bing" to "Bing daily", "picsum" to "Picsum photos")
    private val HOSTS = setOf("www.bing.com", "picsum.photos", "fastly.picsum.photos")

    private fun get(url: String, max: Int = 30 * 1024 * 1024): ByteArray {
        val u = URL(url)
        require(u.protocol == "https" && u.host in HOSTS) { "not an allowed picture source" }
        val c = u.openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 20000; c.setRequestProperty("User-Agent", "mbrowser")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP " + c.responseCode)
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384)
            c.inputStream.use { while (true) { val n = it.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > max) throw java.io.IOException("too large") } }
            return out.toByteArray()
        } finally { c.disconnect() }
    }

    fun list(ctx: Context, source: String, page: Int): Page {
        val dm = ctx.resources.displayMetrics
        val w = minOf(dm.widthPixels, 1440); val h = minOf(dm.heightPixels, 3120)
        if (source == "picsum") {
            val arr = JSONArray(String(get("https://picsum.photos/v2/list?page=${page + 1}&limit=18")))
            val items = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("id").all(Char::isDigit) && it.optString("id").isNotEmpty() }.map {
                val id = it.getString("id")
                Item("https://picsum.photos/id/$id/240/${240 * h / w}.jpg", "https://picsum.photos/id/$id/$w/$h.jpg", it.optString("author", "Picsum"))
            }
            return Page(items, true)
        }
        val j = JSONObject(String(get("https://www.bing.com/HPImageArchive.aspx?format=js&idx=${page * 8}&n=8&mkt=en-GB")))
        val arr = j.optJSONArray("images") ?: JSONArray()
        val items = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { Regex("^/th\\?id=[\\w.\\-]+_$").matches(it.optString("urlbase")) }.map {
            val b = "https://www.bing.com" + it.getString("urlbase")
            Item(b + "480x800.jpg", b + "1080x1920.jpg", it.optString("title", it.optString("copyright", "Bing")).take(80))
        }
        return Page(items, page < 12)
    }

    fun thumb(url: String): Bitmap? = try { val b = get(url, 4 * 1024 * 1024); BitmapFactory.decodeByteArray(b, 0, b.size) } catch (_: Exception) { null }

    /** Downloads the picture and makes it the wallpaper. Returns an error message, or null when it worked. */
    fun use(ctx: Context, url: String): String? = try {
        if (Wallpapers.saveBytes(ctx, get(url))) { Prefs(ctx).wallpaper = "custom"; null } else "That was not a usable picture."
    } catch (e: Exception) { "Could not download it: " + e.message }
}
