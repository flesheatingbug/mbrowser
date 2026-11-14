package app.mbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Bookmark(
    val url: String,
    var title: String,
    var folder: String,
    val added: Long,
    var updated: Long,
    var deleted: Boolean,
    var dirty: Boolean = false,
)

data class Visit(val url: String, val title: String, val time: Long, var dirty: Boolean = true)

/** Local bookmarks + history, kept as small JSON files. The same shapes the sync server uses. */
object Store {
    private lateinit var dir: File
    private val bookmarks = LinkedHashMap<String, Bookmark>()
    private val history = ArrayList<Visit>()
    private var loaded = false
    private const val MAX_HISTORY = 5000

    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        dir = ctx.applicationContext.filesDir
        try {
            val b = File(dir, "bookmarks.json")
            if (b.exists()) {
                val a = JSONArray(b.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    bookmarks[o.getString("url")] = Bookmark(
                        o.getString("url"), o.optString("title"), o.optString("folder"),
                        o.optLong("added"), o.optLong("updated"), o.optBoolean("deleted"), o.optBoolean("dirty"),
                    )
                }
            }
            val h = File(dir, "history.json")
            if (h.exists()) {
                val a = JSONArray(h.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    history.add(Visit(o.getString("url"), o.optString("title"), o.optLong("time"), o.optBoolean("dirty")))
                }
            }
        } catch (_: Exception) {
            // a corrupt file just starts empty
        }
        loaded = true
    }

    @Synchronized
    private fun saveBookmarks() {
        val a = JSONArray()
        for (b in bookmarks.values) {
            a.put(JSONObject().put("url", b.url).put("title", b.title).put("folder", b.folder)
                .put("added", b.added).put("updated", b.updated).put("deleted", b.deleted).put("dirty", b.dirty))
        }
        File(dir, "bookmarks.json").writeText(a.toString())
    }

    @Synchronized
    private fun saveHistory() {
        val a = JSONArray()
        for (v in history) {
            a.put(JSONObject().put("url", v.url).put("title", v.title).put("time", v.time).put("dirty", v.dirty))
        }
        File(dir, "history.json").writeText(a.toString())
    }

    @Synchronized fun bookmarkList(): List<Bookmark> =
        bookmarks.values.filter { !it.deleted }.sortedByDescending { it.updated }

    @Synchronized fun isBookmarked(url: String): Boolean = bookmarks[url]?.deleted == false

    /** Returns true if the page is now bookmarked. */
    @Synchronized fun toggleBookmark(url: String, title: String): Boolean {
        val now = System.currentTimeMillis()
        val cur = bookmarks[url]
        val on: Boolean
        if (cur != null && !cur.deleted) {
            cur.deleted = true; cur.updated = now; cur.dirty = true; on = false
        } else if (cur != null) {
            cur.deleted = false; cur.updated = now; cur.dirty = true
            if (title.isNotBlank()) cur.title = title
            on = true
        } else {
            bookmarks[url] = Bookmark(url, title.ifBlank { url }, "", now, now, false, true); on = true
        }
        saveBookmarks()
        return on
    }

    @Synchronized fun removeBookmark(url: String) {
        bookmarks[url]?.let { it.deleted = true; it.updated = System.currentTimeMillis(); it.dirty = true }
        saveBookmarks()
    }

    @Synchronized fun addVisit(url: String, title: String) {
        if (!url.startsWith("http")) return
        history.add(Visit(url, title, System.currentTimeMillis(), true))
        if (history.size > MAX_HISTORY) history.subList(0, history.size - MAX_HISTORY).clear()
        saveHistory()
    }

    @Synchronized fun historyList(): List<Visit> = history.sortedByDescending { it.time }

    @Synchronized fun clearHistory() { history.clear(); saveHistory() }

    // ---- speed dial = bookmark folders (top-level folder name is the dial) ----
    fun topOf(folder: String): String = folder.substringBefore('/').trim()

    @Synchronized fun topFolders(): List<String> =
        bookmarks.values.filter { !it.deleted }.map { topOf(it.folder) }.filter { it.isNotEmpty() }.distinct()

    @Synchronized fun inDial(dial: String, isDefault: Boolean, defaultName: String): List<Bookmark> =
        bookmarks.values.filter { !it.deleted }.filter {
            val t = topOf(it.folder)
            if (isDefault) t.isEmpty() || t == defaultName else t == dial
        }.sortedBy { it.added }

    @Synchronized fun upsert(url: String, title: String, folder: String, oldUrl: String? = null) {
        val now = System.currentTimeMillis()
        if (oldUrl != null && oldUrl != url) {
            bookmarks[oldUrl]?.let { it.deleted = true; it.updated = now; it.dirty = true }
        }
        val cur = bookmarks[url]
        if (cur != null) {
            cur.title = title.ifBlank { url }; cur.folder = folder; cur.deleted = false; cur.updated = now; cur.dirty = true
        } else {
            bookmarks[url] = Bookmark(url, title.ifBlank { url }, folder, now, now, false, true)
        }
        saveBookmarks()
    }

    @Synchronized fun renameTopFolder(old: String, new: String) {
        val now = System.currentTimeMillis()
        for (b in bookmarks.values) if (!b.deleted && topOf(b.folder) == old) {
            b.folder = new + b.folder.removePrefix(old); b.updated = now; b.dirty = true
        }
        saveBookmarks()
    }

    @Synchronized fun deleteTopFolder(name: String) {
        val now = System.currentTimeMillis()
        for (b in bookmarks.values) if (!b.deleted && topOf(b.folder) == name) {
            b.deleted = true; b.updated = now; b.dirty = true
        }
        saveBookmarks()
    }

    @Synchronized fun folderOf(url: String): String = bookmarks[url]?.folder ?: ""

    // ---- sync helpers ----
    @Synchronized fun dirtyBookmarks(): List<Bookmark> = bookmarks.values.filter { it.dirty }.map { it.copy() }

    @Synchronized fun markBookmarksSent(sent: List<Bookmark>) {
        for (s in sent) {
            val cur = bookmarks[s.url] ?: continue
            if (cur.updated == s.updated) cur.dirty = false   // untouched since we sent it
        }
        saveBookmarks()
    }

    @Synchronized fun mergeBookmarks(items: JSONArray) {
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val url = o.optString("url")
            if (url.isEmpty()) continue
            val upd = o.optLong("updated")
            val cur = bookmarks[url]
            if (cur == null || upd > cur.updated) {
                bookmarks[url] = Bookmark(url, o.optString("title"), o.optString("folder"),
                    o.optLong("added"), upd, o.optBoolean("deleted"), false)
            }
        }
        saveBookmarks()
    }

    @Synchronized fun dirtyHistory(): List<Visit> = history.filter { it.dirty }.take(MAX_HISTORY).map { it.copy() }

    @Synchronized fun markHistorySent(sent: List<Visit>) {
        val keys = sent.map { it.url + "|" + it.time }.toSet()
        for (v in history) if (v.dirty && (v.url + "|" + v.time) in keys) v.dirty = false
        saveHistory()
    }

    @Synchronized fun mergeHistory(items: JSONArray) {
        val have = history.map { it.url + "|" + it.time }.toHashSet()
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val url = o.optString("url")
            val time = o.optLong("time")
            if (url.isEmpty() || !have.add("$url|$time")) continue
            history.add(Visit(url, o.optString("title"), time, false))
        }
        history.sortBy { it.time }
        if (history.size > MAX_HISTORY) history.subList(0, history.size - MAX_HISTORY).clear()
        saveHistory()
    }
}
