package app.mbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Engine(val id: String, val name: String, val url: String)

/**
 * Local copy of the settings groups that travel between devices ("search" and "startPage" here; the desktop
 * browser has more). Same shape as the server document: { key: { updated, value } }.
 */
object Settings {
    val MANAGED = listOf("search", "startPage", "look", "profileMeta")
    private lateinit var file: File
    private var groups = JSONObject()
    private var loaded = false

    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        file = File(ctx.applicationContext.filesDir, "settings.json")
        try { if (file.exists()) groups = JSONObject(file.readText()) } catch (_: Exception) { groups = JSONObject() }
        loaded = true
    }

    @Synchronized fun reset() { groups = JSONObject(); save() }

    private fun save() { try { file.writeText(groups.toString()) } catch (_: Exception) { } }

    private fun defaults(key: String): JSONObject = when (key) {
        "search" -> JSONObject(
            """{"default":"ddg","engines":[
            {"id":"ddg","name":"DuckDuckGo","nick":"d","url":"https://duckduckgo.com/?q=%s"},
            {"id":"google","name":"Google","nick":"g","url":"https://www.google.com/search?q=%s"},
            {"id":"bing","name":"Bing","nick":"b","url":"https://www.bing.com/search?q=%s"},
            {"id":"wiki","name":"Wikipedia","nick":"w","url":"https://en.wikipedia.org/w/index.php?search=%s"},
            {"id":"yt","name":"YouTube","nick":"y","url":"https://www.youtube.com/results?search_query=%s"},
            {"id":"gh","name":"GitHub","nick":"gh","url":"https://github.com/search?q=%s"}]}"""
        )
        "startPage" -> JSONObject(
            """{"tileSize":150,"thumbnails":true,"showSearch":true,"activeGroup":"main",
            "groups":[{"id":"main","name":"Speed Dial","dials":[]}]}"""
        )
        "profileMeta" -> JSONObject("""{"color":"","avatar":""}""")
        else -> JSONObject()
    }

    @Synchronized fun updated(key: String): Long = groups.optJSONObject(key)?.optLong("updated") ?: 0L

    /** A copy of the group's value, with any missing top-level keys filled in from the defaults. */
    @Synchronized fun value(key: String): JSONObject {
        val d = defaults(key)
        val v = groups.optJSONObject(key)?.optJSONObject("value") ?: return d
        val out = JSONObject(v.toString())
        for (k in d.keys()) if (!out.has(k)) out.put(k, d.get(k))
        return out
    }

    @Synchronized fun set(key: String, value: JSONObject) {
        groups.put(key, JSONObject().put("updated", System.currentTimeMillis()).put("value", value))
        save()
    }

    @Synchronized fun applyRemote(key: String, value: JSONObject, updated: Long) {
        groups.put(key, JSONObject().put("updated", updated).put("value", JSONObject(value.toString())))
        save()
    }

    // ---- search ----
    fun engines(): List<Engine> {
        val a = value("search").optJSONArray("engines") ?: JSONArray()
        return (0 until a.length()).mapNotNull {
            val o = a.optJSONObject(it) ?: return@mapNotNull null
            val url = o.optString("url")
            if (url.contains("%s")) Engine(o.optString("id"), o.optString("name", o.optString("id")), url) else null
        }
    }

    fun defaultEngine(): Engine {
        val list = engines()
        val id = value("search").optString("default")
        return list.firstOrNull { it.id == id } ?: list.firstOrNull() ?: Engine("ddg", "DuckDuckGo", "https://duckduckgo.com/?q=%s")
    }

    fun setDefaultEngine(id: String) { set("search", value("search").put("default", id)) }

    fun addEngine(name: String, url: String) {
        val v = value("search")
        val a = v.optJSONArray("engines") ?: JSONArray()
        a.put(JSONObject().put("id", "c" + System.currentTimeMillis()).put("name", name).put("nick", "").put("url", url))
        set("search", v.put("engines", a))
    }

    fun removeEngine(id: String) {
        val v = value("search")
        val a = v.optJSONArray("engines") ?: return
        val n = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") != id) n.put(a.get(i))
        if (n.length() == 0) return
        v.put("engines", n)
        if (v.optString("default") == id) v.put("default", n.getJSONObject(0).optString("id"))
        set("search", v)
    }

    fun searchUrl(query: String): String =
        defaultEngine().url.replace("%s", java.net.URLEncoder.encode(query, "UTF-8"))

    // ---- speed dial ----
    fun thumbnails(): Boolean = value("startPage").optBoolean("thumbnails", true)
    fun setThumbnails(on: Boolean) { set("startPage", value("startPage").put("thumbnails", on)) }

    fun dialHosts(): Set<String> {
        val out = HashSet<String>()
        val g = value("startPage").optJSONArray("groups") ?: return out
        for (i in 0 until g.length()) collectHosts(g.getJSONObject(i).optJSONArray("dials"), out)
        return out
    }

    /** Every speed dial address, folders included. */
    fun dialUrls(): List<String> {
        val out = ArrayList<String>()
        fun walk(a: JSONArray?) {
            if (a == null) return
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val u = o.optString("url"); if (u.startsWith("http")) out.add(u)
                walk(o.optJSONArray("dials"))
            }
        }
        val g = value("startPage").optJSONArray("groups") ?: return out
        for (i in 0 until g.length()) walk(g.getJSONObject(i).optJSONArray("dials"))
        return out
    }

    private fun collectHosts(a: JSONArray?, out: HashSet<String>) {
        if (a == null) return
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val u = o.optString("url")
            if (u.isNotEmpty()) out.add(Thumbs.hostOf(u))
            collectHosts(o.optJSONArray("dials"), out)   // folders hold more dials
        }
    }

    fun addToGroup(groupId: String?, title: String, url: String) {
        val v = value("startPage")
        val g = v.optJSONArray("groups") ?: JSONArray()
        var target: JSONObject? = null
        for (i in 0 until g.length()) if (g.getJSONObject(i).optString("id") == groupId) target = g.getJSONObject(i)
        if (target == null && g.length() > 0) target = g.getJSONObject(0)
        if (target == null) return
        val d = target.optJSONArray("dials") ?: JSONArray().also { target.put("dials", it) }
        d.put(JSONObject().put("title", title).put("url", url))
        set("startPage", v)
    }
}
