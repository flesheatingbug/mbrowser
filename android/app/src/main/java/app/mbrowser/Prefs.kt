package app.mbrowser

import android.content.Context
import android.os.Build

object Engines {
    val names = listOf("DuckDuckGo", "Google", "Brave", "Bing", "Startpage", "Ecosia", "Custom")
    private val urls = listOf(
        "https://duckduckgo.com/?q=", "https://www.google.com/search?q=", "https://search.brave.com/search?q=",
        "https://www.bing.com/search?q=", "https://www.startpage.com/do/search?q=", "https://www.ecosia.org/search?q=",
    )
    fun url(index: Int, custom: String): String =
        if (index in urls.indices) urls[index] else custom.ifBlank { urls[0] }
}

class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("mb", Context.MODE_PRIVATE)

    private fun str(k: String, d: String) = p.getString(k, d) ?: d
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()
    private fun int(k: String, d: Int) = p.getInt(k, d)
    private fun putI(k: String, v: Int) = p.edit().putInt(k, v).apply()
    private fun bool(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun putB(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()

    // sync
    var server: String
        get() = str("server", "")
        set(v) = put("server", v.trim().trimEnd('/'))
    var token: String
        get() = str("token", "")
        set(v) = put("token", v.trim())
    var device: String
        get() = str("device", "Android ${Build.MODEL}")
        set(v) = put("device", v.trim())
    var autoSync: Boolean
        get() = bool("autoSync", true)
        set(v) = putB("autoSync", v)
    var syncBookmarks: Boolean
        get() = bool("syncBookmarks", true)
        set(v) = putB("syncBookmarks", v)
    var syncHistory: Boolean
        get() = bool("syncHistory", true)
        set(v) = putB("syncHistory", v)
    var syncTabs: Boolean
        get() = bool("syncTabs", true)
        set(v) = putB("syncTabs", v)
    var bmRev: Long
        get() = p.getLong("bmRev", 0)
        set(v) = p.edit().putLong("bmRev", v).apply()
    var histRev: Long
        get() = p.getLong("histRev", 0)
        set(v) = p.edit().putLong("histRev", v).apply()
    var otherTabs: String
        get() = str("otherTabs", "[]")
        set(v) = put("otherTabs", v)

    // search
    var engine: Int
        get() = int("engine", 0)
        set(v) = putI("engine", v)
    var customSearch: String
        get() = str("customSearch", "")
        set(v) = put("customSearch", v.trim())
    val searchUrl: String get() = Engines.url(engine, customSearch)

    // appearance: 0 system, 1 light, 2 dark
    var themeMode: Int
        get() = int("theme", 0)
        set(v) = putI("theme", v)

    // speed dial
    var dialCols: Int
        get() = int("dialCols", 3)
        set(v) = putI("dialCols", v)
    var showTitles: Boolean
        get() = bool("showTitles", true)
        set(v) = putB("showTitles", v)
    var showThumbs: Boolean
        get() = bool("showThumbs", true)
        set(v) = putB("showThumbs", v)
    var showAdd: Boolean
        get() = bool("showAdd", true)
        set(v) = putB("showAdd", v)
    var defaultDialName: String
        get() = str("defaultDial", "Speed Dial")
        set(v) = put("defaultDial", v.trim().ifEmpty { "Speed Dial" })
    var extraDials: String
        get() = str("extraDials", "[]")
        set(v) = put("extraDials", v)
    var currentDial: String
        get() = str("currentDial", "")
        set(v) = put("currentDial", v)

    // browsing
    var desktopSite: Boolean
        get() = bool("desktopSite", false)
        set(v) = putB("desktopSite", v)
    var textZoom: Int
        get() = int("textZoom", 100)
        set(v) = putI("textZoom", v)
    var blockTrackers: Boolean
        get() = bool("blockTrackers", true)
        set(v) = putB("blockTrackers", v)
    var firstRunDone: Boolean
        get() = bool("firstRun", false)
        set(v) = putB("firstRun", v)

    val syncConfigured: Boolean get() = server.isNotEmpty() && token.isNotEmpty()
}
