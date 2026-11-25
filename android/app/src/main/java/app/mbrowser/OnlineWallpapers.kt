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

    val SOURCES = listOf("bing" to "Bing daily", "nasa" to "NASA", "wiki" to "Wikimedia", "picsum" to "Picsum")
    val CATEGORIES = listOf("Nature", "Landscapes", "Mountains", "Ocean", "Space", "Cities", "Animals", "Flowers", "Abstract")
    private val HOSTS = setOf("www.bing.com", "picsum.photos", "fastly.picsum.photos", "upload.wikimedia.org", "apod.nasa.gov", "api.nasa.gov", "commons.wikimedia.org")

    private fun get(url: String, max: Int = 30 * 1024 * 1024): ByteArray {
        val u = URL(url)
        require(u.protocol == "https" && u.host in HOSTS) { "not an allowed picture source" }
        val c = u.openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 20000; c.setRequestProperty("User-Agent", "mbrowser/1.0 (https://github.com/flesheatingbug/mbrowser)")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP " + c.responseCode)
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384)
            c.inputStream.use { while (true) { val n = it.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > max) throw java.io.IOException("too large") } }
            return out.toByteArray()
        } finally { c.disconnect() }
    }

    fun list(ctx: Context, source: String, page: Int, category: String = "nature"): Page {
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
        if (source == "nasa") {
            fun day(n: Int) = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(System.currentTimeMillis() - n * 86400000L))
            val arr = JSONArray(String(get("https://api.nasa.gov/planetary/apod?api_key=DEMO_KEY&start_date=${day(page * 12 + 11)}&end_date=${day(page * 12)}")))
            val items = (0 until arr.length()).map { arr.getJSONObject(it) }.reversed().filter { it.optString("media_type") == "image" && it.optString("url").startsWith("https://apod.nasa.gov/") }.map {
                val hd = it.optString("hdurl")
                Item(it.getString("url"), if (hd.startsWith("https://apod.nasa.gov/")) hd else it.getString("url"), it.optString("title", "NASA").take(80))
            }
            return Page(items, page < 40)
        }
        if (source == "wiki") {
            val term = category.filter { it.isLetter() || it == ' ' }.take(30).ifEmpty { "nature" }
            val q = java.net.URLEncoder.encode("incategory:\"Featured pictures on Wikimedia Commons\" $term filetype:bitmap", "UTF-8")
            val j = JSONObject(String(get("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=18&gsroffset=${page * 18}&gsrsearch=$q&prop=imageinfo&iiprop=url|size&iiurlwidth=330")))
            val pages = j.optJSONObject("query")?.optJSONObject("pages")
            val list = if (pages == null) emptyList() else pages.keys().asSequence().map { pages.getJSONObject(it) }.sortedBy { it.optInt("index") }.toList()
            val steps = listOf(330, 500, 960, 1280, 1920, 2560, 3840)
            val items = list.mapNotNull { p ->
                val ii = p.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
                val iw = ii.optInt("width"); val ih = ii.optInt("height"); val url = ii.optString("url"); val th = ii.optString("thumburl")
                if (iw <= 0 || ih <= 0 || ih < h * 0.6 || th.isEmpty()) return@mapNotNull null
                val need = minOf(3840, maxOf(w, Math.ceil(h.toDouble() * iw / ih).toInt()))
                val step = steps.firstOrNull { it >= need } ?: 3840
                val m = Regex("^(https://upload\\.wikimedia\\.org/wikipedia/commons)/(.+)/([^/]+)$").find(url)
                val full = if (iw > step && m != null) "${m.groupValues[1]}/thumb/${m.groupValues[2]}/${m.groupValues[3]}/${step}px-${m.groupValues[3]}" + (if (Regex("(?i)\\.(svg|tif|tiff)$").containsMatchIn(m.groupValues[3])) ".png" else "") else url
                Item(th, full, p.optString("title").removePrefix("File:").substringBeforeLast('.').take(80))
            }
            return Page(items, list.size >= 18 && page < 30)
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
