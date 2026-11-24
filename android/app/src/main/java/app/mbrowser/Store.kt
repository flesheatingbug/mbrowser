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
    var bin: Boolean = false,     // the desktop's recycle bin flag; here it counts as deleted
)

data class Visit(val url: String, val title: String, val time: Long, var dirty: Boolean = true)

/** A history entry in the recycle bin. */
data class BinVisit(val visit: Visit, val binTime: Long)

/** Local bookmarks + history, kept as small JSON files. The same shapes the sync server uses. */
object Store {
    private lateinit var dir: File
    private val bookmarks = LinkedHashMap<String, Bookmark>()
    private val history = ArrayList<Visit>()
    private val historyBin = ArrayList<BinVisit>()
    private var loaded = false
    private const val MAX_HISTORY = 20000

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
                        o.getString("url"), o.optString("title"), cleanPath(o.optString("folder")),
                        o.optLong("added"), o.optLong("updated"), o.optBoolean("deleted"), o.optBoolean("dirty"), o.optBoolean("bin"),
                    )
                }
            }
            val hb = File(dir, "history-bin.json")
            if (hb.exists()) {
                val a = JSONArray(hb.readText()); val cut = System.currentTimeMillis() - 30L * 86_400_000L
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    if (o.optLong("binTime") > cut) historyBin.add(BinVisit(Visit(o.getString("url"), o.optString("title"), o.optLong("time"), false), o.optLong("binTime")))
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
                .put("added", b.added).put("updated", b.updated).put("deleted", b.deleted).put("dirty", b.dirty).put("bin", b.bin))
        }
        File(dir, "bookmarks.json").writeText(a.toString())
    }

    // history is written in the background, a moment after the last change, so a long history never slows down page loads
    private val saver = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private var pendingSave: java.util.concurrent.ScheduledFuture<*>? = null

    @Synchronized
    private fun saveHistory() {
        pendingSave?.cancel(false)
        pendingSave = saver.schedule({
            val snapshot = synchronized(this) { history.toList() }
            val a = JSONArray()
            for (v in snapshot) a.put(JSONObject().put("url", v.url).put("title", v.title).put("time", v.time).put("dirty", v.dirty))
            try {
                val f = File(dir, "history.json"); val tmp = File(dir, "history.json.tmp")
                tmp.writeText(a.toString()); tmp.renameTo(f)
            } catch (_: Exception) { }
        }, 1500, java.util.concurrent.TimeUnit.MILLISECONDS)
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
            cur.deleted = false; cur.bin = false; cur.updated = now; cur.dirty = true
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

    // ---- history manager helpers (removing history only affects this phone; the server keeps no deletions) ----
    private fun saveHistoryBin() {
        try {
            val a = JSONArray()
            for (b in historyBin) a.put(JSONObject().put("url", b.visit.url).put("title", b.visit.title).put("time", b.visit.time).put("binTime", b.binTime))
            File(dir, "history-bin.json").writeText(a.toString())
        } catch (_: Exception) { }
    }

    private fun vkey(v: Visit) = v.url + "|" + v.time

    @Synchronized fun historyBinList(): List<BinVisit> = historyBin.sortedByDescending { it.binTime }

    /** Moves visits (keys are "url|time") to the recycle bin, kept 30 days. Returns how many were removed. */
    @Synchronized fun removeVisits(keys: Set<String>): Int {
        val gone = history.filter { vkey(it) in keys }
        if (gone.isEmpty()) return 0
        history.removeAll(gone.toSet())
        val now = System.currentTimeMillis()
        for (v in gone) historyBin.add(BinVisit(v.copy(dirty = false), now))
        if (historyBin.size > 5000) historyBin.subList(0, historyBin.size - 5000).clear()
        saveHistory(); saveHistoryBin()
        return gone.size
    }

    @Synchronized fun restoreVisits(keys: Set<String>): Int {
        val back = historyBin.filter { vkey(it.visit) in keys }
        if (back.isEmpty()) return 0
        historyBin.removeAll(back.toSet())
        val have = history.map { vkey(it) }.toHashSet()
        for (b in back) if (have.add(vkey(b.visit))) history.add(b.visit.copy(dirty = false))
        history.sortBy { it.time }
        saveHistory(); saveHistoryBin()
        return back.size
    }

    @Synchronized fun emptyHistoryBin() { historyBin.clear(); saveHistoryBin() }

    @Synchronized fun historyList(): List<Visit> = history.sortedByDescending { it.time }

    @Synchronized fun clearHistory() { history.clear(); saveHistory() }
    fun visitKey(v: Visit) = v.url + "|" + v.time

    /** Everything is sent again (the sync backend changed, so the new place starts empty). */
    @Synchronized fun markAllDirty() {
        for (b in bookmarks.values) b.dirty = true
        for (v in history) v.dirty = true
        saveBookmarks(); saveHistory()
    }

    /** Forgets all local bookmarks and history (used when the phone is pointed at a different server profile). */
    @Synchronized fun resetAll() {
        bookmarks.clear(); history.clear(); historyBin.clear()
        saveBookmarks(); saveHistory(); saveHistoryBin()
    }

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
            cur.title = title.ifBlank { url }; cur.folder = folder; cur.deleted = false; cur.bin = false; cur.updated = now; cur.dirty = true
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


    // ---- bookmark manager helpers ----
    fun cleanPath(p: String): String = p.split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")

    fun ready(): Boolean = loaded

    @Synchronized fun liveBookmarks(): List<Bookmark> = bookmarks.values.filter { !it.deleted }.map { it.copy() }

    /** Deleted bookmarks stay restorable for 30 days. */
    @Synchronized fun binList(): List<Bookmark> {
        val cut = System.currentTimeMillis() - 30L * 86_400_000L
        return bookmarks.values.filter { it.deleted && it.updated > cut }.sortedByDescending { it.updated }.map { it.copy() }
    }

    @Synchronized fun moveMany(urls: Collection<String>, folder: String) {
        val now = System.currentTimeMillis(); val f = cleanPath(folder)
        for (u in urls) bookmarks[u]?.let { if (!it.deleted) { it.folder = f; it.updated = now; it.dirty = true } }
        saveBookmarks()
    }

    @Synchronized fun deleteMany(urls: Collection<String>) {
        val now = System.currentTimeMillis()
        for (u in urls) bookmarks[u]?.let { it.deleted = true; it.updated = now; it.dirty = true }
        saveBookmarks()
    }

    @Synchronized fun restoreMany(urls: Collection<String>) {
        val now = System.currentTimeMillis()
        for (u in urls) bookmarks[u]?.let { it.deleted = false; it.bin = false; it.updated = now; it.dirty = true }
        saveBookmarks()
    }

    /** Renames or moves a folder with everything inside it (any depth). */
    @Synchronized fun renameFolder(old: String, new: String) {
        val now = System.currentTimeMillis(); val n = cleanPath(new)
        for (b in bookmarks.values) if (!b.deleted && (b.folder == old || b.folder.startsWith("$old/"))) {
            b.folder = cleanPath(n + b.folder.removePrefix(old)); b.updated = now; b.dirty = true
        }
        saveBookmarks()
    }

    @Synchronized fun deleteFolder(path: String) {
        val now = System.currentTimeMillis()
        for (b in bookmarks.values) if (!b.deleted && (b.folder == path || b.folder.startsWith("$path/"))) {
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
                bookmarks[url] = Bookmark(url, o.optString("title"), cleanPath(o.optString("folder")),
                    o.optLong("added"), upd, o.optBoolean("deleted") || o.optBoolean("recycleBin"), false, o.optBoolean("recycleBin"))
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
