package app.mbrowser

import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeSet

/**
 * Speed dial tiles are bookmarks in the top-level "Speed Dials" folder (one sub-folder per dial page, more inside for dial
 * folders), so they sync with the server like any bookmark. The start page settings only keep the page names and the
 * arranged order ("dialLayout", the same text the desktop writes). The start page code still sees pages with a "dials" list:
 * [attach] builds that from the bookmarks and [detach] writes changes back.
 */
object Dials {
    const val ROOT = "Speed Dials"

    private fun norm(f: String) = Store.cleanPath(f)
    private fun clean(n: String, fb: String) = n.replace("/", "-").trim().ifEmpty { fb }.take(60)
    private fun groupPath(groups: JSONArray, i: Int) = ROOT + "/" + clean(groups.getJSONObject(i).optString("name"), "Page ${i + 1}")
    private fun lk(path: String) = path.split('/').joinToString(" / ")     // layout key, as written by the desktop
    private fun isFolder(o: JSONObject) = o.optString("url").isEmpty() && (o.has("dials") || o.has("items") || o.optString("type") == "folder")
    private fun kids(o: JSONObject): JSONArray = o.optJSONArray("dials") ?: o.optJSONArray("items") ?: JSONArray()
    private fun keyOf(o: JSONObject) = if (isFolder(o)) "f:" + o.optString("title") else "u:" + o.optString("url")
    private fun parents(p: String): List<String> { val parts = p.split('/'); return (1..parts.size).map { parts.take(it).joinToString("/") } }

    /** The bookmark folder that holds the tiles of speed dial page [i] (0 is the first page). */
    fun pathFor(i: Int): String? {
        val groups = Settings.raw("startPage").optJSONArray("groups") ?: return null
        return if (i in 0 until groups.length()) groupPath(groups, i) else null
    }

    /** The saved tile order: its own synced settings group (older versions kept it inside the start page group). */
    private fun savedLayout(sp: JSONObject): JSONObject {
        val own = Settings.raw("dialLayout").optJSONObject("layout")
        if (own != null && own.length() > 0) return own
        return sp.optJSONObject("dialLayout") ?: JSONObject()
    }

    /** Puts the tiles (read from the bookmarks) into each page of [sp] as "dials". */
    fun attach(sp: JSONObject) {
        if (!Store.ready()) return
        val groups = sp.optJSONArray("groups") ?: return
        val live = Store.liveBookmarks().map { it to norm(it.folder) }.filter { it.second == ROOT || it.second.startsWith("$ROOT/") }
        val layout = savedLayout(sp)
        val paths = (0 until groups.length()).map { groupPath(groups, it) }
        val folders = TreeSet<String>()
        for (p in live.map { it.second }) folders.addAll(parents(p))
        sp.optJSONArray("dialFolders")?.let { a -> for (i in 0 until a.length()) { val p = norm(a.optString(i)); if (p == ROOT || p.startsWith("$ROOT/")) folders.addAll(parents(p)) } }

        fun arrange(path: String, items: List<JSONObject>): List<JSONObject> {
            val order = layout.optJSONArray(lk(path))
            val pos = HashMap<String, Int>()
            if (order != null) for (i in 0 until order.length()) pos[order.optString(i)] = i
            return items.withIndex().sortedWith(compareBy({ pos[keyOf(it.value)] ?: 1_000_000 }, { it.index })).map { it.value }
        }
        fun build(path: String, adopt: Boolean): List<JSONObject> {
            val out = ArrayList<JSONObject>()
            for (f in folders) {
                if (!f.startsWith("$path/") || f.substring(path.length + 1).contains('/')) continue
                if (adopt && f in paths) continue
                val arr = JSONArray(); for (k in build(f, false)) arr.put(k)
                out.add(JSONObject().put("type", "folder").put("folder", true).put("title", f.substringAfterLast('/')).put("dials", arr))
            }
            for ((b, f) in live.filter { it.second == path }.sortedBy { it.first.added }) out.add(JSONObject().put("title", b.title.ifBlank { b.url }).put("url", b.url))
            return arrange(path, out)
        }
        for (i in 0 until groups.length()) {
            val items = ArrayList(build(paths[i], false))
            if (i == 0) items.addAll(build(ROOT, true))
            val arr = JSONArray(); for (k in items) arr.put(k)
            groups.getJSONObject(i).put("dials", arr)
        }
    }

    /** Returns [sp] without the tiles, after writing any "dials" in it back into the bookmarks and the layout. */
    fun detach(sp: JSONObject): JSONObject {
        val out = JSONObject(sp.toString())
        if (!Store.ready()) return out
        val groups = out.optJSONArray("groups") ?: return out
        var has = false
        for (i in 0 until groups.length()) if (groups.getJSONObject(i).has("dials")) has = true
        if (!has) return out
        write(groups, out)
        for (i in 0 until groups.length()) groups.getJSONObject(i).remove("dials")
        return out
    }

    /** Makes the bookmarks match the tiles in [groups]; records the order and any empty folders in [sp]. */
    private fun write(groups: JSONArray, sp: JSONObject) {
        val want = LinkedHashMap<String, Pair<String, String>>()   // url -> title, folder
        val layout = JSONObject()
        val folders = LinkedHashSet<String>()
        fun walk(items: JSONArray, base: String) {
            val keys = JSONArray()
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                if (isFolder(o)) {
                    val nm = clean(o.optString("title"), "Folder"); val p = "$base/$nm"
                    folders.add(p); keys.put("f:$nm"); walk(kids(o), p)
                } else {
                    val u = o.optString("url")
                    if (u.startsWith("http") && u !in want) { want[u] = o.optString("title") to base; keys.put("u:$u") }
                }
            }
            layout.put(lk(base), keys)
        }
        for (i in 0 until groups.length()) {
            val p = groupPath(groups, i); folders.add(p)
            walk(groups.getJSONObject(i).optJSONArray("dials") ?: JSONArray(), p)
        }
        val live = Store.liveBookmarks().associateBy { it.url }
        for ((u, tf) in want) {
            val cur = live[u]
            val title = tf.first.ifBlank { cur?.title ?: u }
            if (cur == null || cur.title != title || norm(cur.folder) != tf.second) Store.upsert(u, title, tf.second)
        }
        val gone = live.values.filter { val f = norm(it.folder); (f == ROOT || f.startsWith("$ROOT/")) && it.url !in want }.map { it.url }
        if (gone.isNotEmpty()) Store.deleteMany(gone)
        val used = HashSet<String>(); for (tf in want.values) used.addAll(parents(tf.second))
        val empty = JSONArray(); for (f in folders) if (f !in used) empty.put(f)
        if (layout.toString() != savedLayout(sp).toString()) Settings.set("dialLayout", JSONObject().put("layout", layout))
        sp.remove("dialLayout"); sp.put("dialFolders", empty)
    }

    /** A start page group from an older desktop still carries its tiles: add the ones not yet bookmarked, then drop them from [sp]. */
    fun mergeLegacy(sp: JSONObject): JSONObject {
        val groups = sp.optJSONArray("groups") ?: return sp
        var has = false
        for (i in 0 until groups.length()) { val d = groups.getJSONObject(i).optJSONArray("dials"); if (d != null && d.length() > 0) has = true }
        if (!has || !Store.ready()) return sp
        val legacy = JSONObject(sp.toString()).getJSONArray("groups")
        val cur = JSONObject(sp.toString())
        val cg = cur.getJSONArray("groups")
        for (i in 0 until cg.length()) cg.getJSONObject(i).remove("dials")
        attach(cur)
        val have = HashSet<String>(); Store.liveBookmarks().forEach { have.add(it.url) }
        fun merge(from: JSONArray, into: JSONArray) {
            for (i in 0 until from.length()) {
                val o = from.optJSONObject(i) ?: continue
                if (isFolder(o)) {
                    var found: JSONObject? = null
                    for (k in 0 until into.length()) { val x = into.getJSONObject(k); if (isFolder(x) && x.optString("title") == o.optString("title")) found = x }
                    val fo: JSONObject = found ?: JSONObject().put("type", "folder").put("folder", true).put("title", o.optString("title")).put("dials", JSONArray()).also { into.put(it) }
                    merge(kids(o), fo.optJSONArray("dials") ?: JSONArray().also { fo.put("dials", it) })
                } else {
                    val u = o.optString("url")
                    if (u.startsWith("http") && u !in have) { into.put(JSONObject().put("title", o.optString("title")).put("url", u)); have.add(u) }
                }
            }
        }
        for (i in 0 until minOf(cg.length(), legacy.length())) {
            val into = cg.getJSONObject(i).optJSONArray("dials") ?: JSONArray().also { cg.getJSONObject(i).put("dials", it) }
            merge(legacy.getJSONObject(i).optJSONArray("dials") ?: JSONArray(), into)
        }
        val out = detach(cur)
        sp.remove("dialLayout"); sp.put("dialFolders", out.optJSONArray("dialFolders") ?: JSONArray())
        for (i in 0 until groups.length()) groups.getJSONObject(i).remove("dials")
        return sp
    }
}
