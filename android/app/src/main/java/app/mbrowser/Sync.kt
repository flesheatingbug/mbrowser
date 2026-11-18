package app.mbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Syncs bookmarks, history and open tabs with the mbrowser server. Vault and settings sync are not done here yet. */
object Sync {
    private val pool = Executors.newSingleThreadExecutor()
    private const val PAGE = 5000
    const val OFFLINE = "Server not reachable. Your changes are kept and will sync when it is back."

    /** True when the server simply can't be reached (off, no network, or a proxy in front of it answering 502/503/504). */
    private fun unreachable(e: Exception): Boolean =
        e is java.io.IOException && (e !is ApiException || e.code in setOf(502, 503, 504, 521, 522, 523, 524))

    /** Runs a sync in the background; onDone is called on the calling thread's main looper via the callback owner. */
    fun runAsync(ctx: Context, tabs: () -> List<Pair<String, String>>, onDone: (String) -> Unit) {
        val app = ctx.applicationContext
        val openTabs = tabs()   // read on the UI thread
        pool.execute {
            val msg = try { run(app, openTabs) } catch (e: Exception) { if (unreachable(e)) OFFLINE else "Sync failed: ${e.message}" }
            onDone(msg)
        }
    }

    @Synchronized
    fun run(ctx: Context, openTabs: List<Pair<String, String>>): String {
        val prefs = Prefs(ctx)
        if (!prefs.syncConfigured) return "Sync is not set up"
        val api = Api(prefs.server, prefs.token, prefs.device)
        ensureSameProfile(ctx, prefs, api)

        if (prefs.syncBookmarks) {
        val sentB = Store.dirtyBookmarks()
        val bItems = JSONArray()
        for (b in sentB) {
            bItems.put(JSONObject().put("url", b.url).put("title", b.title).put("folder", b.folder)
                .put("added", b.added).put("updated", b.updated).put("deleted", b.deleted && !b.bin).put("recycleBin", b.bin && b.deleted))
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

        if (prefs.syncSettings) { try { syncWallpaper(ctx, api) } catch (_: Exception) { }; syncSettings(api, ctx, prefs) }

        if (prefs.syncVault) { try { VaultSync.run(ctx, api) } catch (e: Exception) { prefs.vaultStatus = "Sync failed: ${e.message}" } }

        return "Synced"
    }

    /**
     * Local bookmarks, history and settings belong to one server profile. If this token now points at a different one (API key, pairing code,
     * account switch) the local copy is dropped first, so two profiles are never merged.
     */
    private fun ensureSameProfile(ctx: Context, prefs: Prefs, api: Api) {
        if (prefs.verifiedToken == prefs.token) return
        val pid = (try {
            val arr = api.request("GET", "/api/profiles").optJSONArray("profiles") ?: return
            var found: String? = null
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.optBoolean("current")) found = o.get("id").toString() }
            found
        } catch (_: Exception) { null }) ?: return
        val owner = prefs.server.trimEnd('/') + "|" + pid
        val before = if (prefs.dataOwner.isNotEmpty()) prefs.dataOwner
                     else if (prefs.profileId.isNotEmpty()) prefs.server.trimEnd('/') + "|" + prefs.profileId else ""
        if (before.isNotEmpty() && before != owner) {
            Store.resetAll(); Settings.reset(); Vault.forgetLocal(ctx)
            prefs.bmRev = 0; prefs.histRev = 0; prefs.otherTabs = "[]"; prefs.lookHash = ""
        }
        prefs.dataOwner = owner; prefs.profileId = pid; prefs.verifiedToken = prefs.token
    }

    /** Merges the managed settings groups with the server's document. The newest change to a group wins. */
    /** Your own wallpaper photo: the newer copy wins, in either direction. */
    private fun syncWallpaper(ctx: Context, api: Api) {
        val f = Wallpapers.customFile(ctx)
        val local = if (f.exists()) f.lastModified() else 0L
        val r = api.request("GET", "/api/wallpaper")
        val ru = r.optLong("updated")
        val data = r.optString("data", "")
        if (local > ru && f.exists()) {
            val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
            api.request("PUT", "/api/wallpaper", JSONObject().put("updated", local).put("data", b64))
        } else if (ru > local && data.isNotEmpty()) {
            Wallpapers.storeCustom(ctx, android.util.Base64.decode(data, android.util.Base64.DEFAULT), ru)
        }
    }

    private fun syncSettings(api: Api, ctx: Context, prefs: Prefs) {
        for (attempt in 0 until 3) {
            val remote = api.request("GET", "/api/settings")
            val version = remote.optLong("version")
            val doc = remote.optJSONObject("doc")
            val rgroups = doc?.optJSONObject("groups") ?: JSONObject()
            val merged = JSONObject()
            var push = doc == null
            // the look: if it changed on this phone since the last sync, that is a new local change
            val rl = rgroups.optJSONObject("look")
            val remoteHasLook = rl != null && rl.has("updated") && rl.optJSONObject("value") != null
            val curLook = Look.json(prefs)
            if (prefs.lookHash.isNotEmpty() && prefs.lookHash != Look.hash(prefs)) Settings.set("look", curLook)
            else if (!remoteHasLook && Settings.updated("look") == 0L) Settings.set("look", curLook)
            // groups this app doesn't manage (the desktop has more) are passed through untouched
            for (k in rgroups.keys()) if (k !in Settings.MANAGED) merged.put(k, rgroups.get(k))
            for (key in Settings.MANAGED) {
                val lu = Settings.updated(key)
                val local = JSONObject().put("updated", lu).put("value", Settings.raw(key))
                val r = rgroups.optJSONObject(key)
                val valid = r != null && r.has("updated") && r.optJSONObject("value") != null
                if (!valid) { merged.put(key, local); push = true; continue }
                val ru = r!!.optLong("updated")
                if (ru > lu) {
                    Settings.applyRemote(key, r.getJSONObject("value"), ru); merged.put(key, r)
                    if (key == "look") Look.apply(ctx, prefs, r.getJSONObject("value"))
                }
                else if (ru < lu) { merged.put(key, local); push = true }
                else merged.put(key, r)
            }
            if (Settings.updated("profileMeta") > 0) {
                val pm = Settings.value("profileMeta")
                prefs.profileColor = pm.optString("color"); prefs.profileAvatar = pm.optString("avatar")
            }
            prefs.lookHash = Look.hash(prefs)
            if (!push) return
            try {
                api.request("PUT", "/api/settings", JSONObject().put("base", version).put("doc", JSONObject().put("groups", merged)))
                return
            } catch (e: ApiException) {
                if (e.code != 409) throw e   // 409: the server changed meanwhile, so merge again
            }
        }
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
