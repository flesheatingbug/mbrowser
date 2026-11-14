package app.mbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Syncs bookmarks, history and open tabs with the mbrowser server. Vault and settings sync are not done here yet. */
object Sync {
    private val pool = Executors.newSingleThreadExecutor()
    private const val PAGE = 5000

    /** Runs a sync in the background; onDone is called on the calling thread's main looper via the callback owner. */
    fun runAsync(ctx: Context, tabs: () -> List<Pair<String, String>>, onDone: (String) -> Unit) {
        val app = ctx.applicationContext
        val openTabs = tabs()   // read on the UI thread
        pool.execute {
            val msg = try { run(app, openTabs) } catch (e: Exception) { "Sync failed: ${e.message}" }
            onDone(msg)
        }
    }

    @Synchronized
    fun run(ctx: Context, openTabs: List<Pair<String, String>>): String {
        val prefs = Prefs(ctx)
        if (!prefs.syncConfigured) return "Sync is not set up"
        val api = Api(prefs.server, prefs.token, prefs.device)

        if (prefs.syncBookmarks) {
        val sentB = Store.dirtyBookmarks()
        val bItems = JSONArray()
        for (b in sentB) {
            bItems.put(JSONObject().put("url", b.url).put("title", b.title).put("folder", b.folder)
                .put("added", b.added).put("updated", b.updated).put("deleted", b.deleted))
        }
        val rb = api.request("POST", "/api/bookmarks", JSONObject().put("since", prefs.bmRev).put("items", bItems))
        Store.markBookmarksSent(sentB)
        Store.mergeBookmarks(rb.optJSONArray("items") ?: JSONArray())
        prefs.bmRev = rb.optLong("rev", prefs.bmRev)
        }

        if (prefs.syncHistory) {
        val sentH = Store.dirtyHistory()
        val hItems = JSONArray()
        for (v in sentH) hItems.put(JSONObject().put("url", v.url).put("title", v.title).put("time", v.time))
        var body = JSONObject().put("since", prefs.histRev).put("items", hItems)
        for (round in 0 until 20) {
            val rh = api.request("POST", "/api/history", body)
            val got = rh.optJSONArray("items") ?: JSONArray()
            Store.mergeHistory(got)
            prefs.histRev = rh.optLong("rev", prefs.histRev)
            if (round == 0) Store.markHistorySent(sentH)
            if (got.length() < PAGE) break
            body = JSONObject().put("since", prefs.histRev).put("items", JSONArray())
        }
        }

        if (prefs.syncTabs) {
        val tArr = JSONArray()
        for ((u, t) in openTabs) if (u.startsWith("http")) tArr.put(JSONObject().put("url", u).put("title", t))
        api.request("PUT", "/api/tabs", JSONObject().put("device", prefs.device).put("tabs", tArr))
        prefs.otherTabs = (api.tabsGet().optJSONArray("devices") ?: JSONArray()).toString()
        }

        return "Synced"
    }

    fun test(ctx: Context): String {
        val prefs = Prefs(ctx)
        if (!prefs.syncConfigured) return "Enter the server URL and API key"
        return try {
            Api(prefs.server, prefs.token, prefs.device).tabsGet()
            "Connected. The key works."
        } catch (e: ApiException) {
            if (e.code == 401) "The server rejected the API key. ${e.message}" else "Server error: ${e.message}"
        } catch (e: Exception) {
            "Could not reach the server: ${e.message}"
        }
    }
}
