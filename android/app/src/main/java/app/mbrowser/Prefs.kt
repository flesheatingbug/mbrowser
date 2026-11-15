package app.mbrowser

import android.content.Context
import android.os.Build

class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("mb", Context.MODE_PRIVATE)

    private fun str(k: String, d: String) = p.getString(k, d) ?: d
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()
    private fun int(k: String, d: Int) = p.getInt(k, d)
    private fun putI(k: String, v: Int) = p.edit().putInt(k, v).apply()
    private fun bool(k: String, d: Boolean) = p.getBoolean(k, d)
    private fun putB(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()

    // app updates
    var translateLang: String
        get() = str("translateLang", java.util.Locale.getDefault().language.ifEmpty { "en" })
        set(v) = put("translateLang", v)
    var updateRepo: String
        get() = str("updateRepo", "flesheatingbug/mbrowser")
        set(v) = put("updateRepo", v.trim())
    var updateToken: String
        get() = str("updateToken", "")
        set(v) = put("updateToken", v.trim())
    var updateAuto: Boolean
        get() = bool("updateAuto", true)
        set(v) = putB("updateAuto", v)
    var updateLast: Long
        get() = p.getLong("updateLast", 0L)
        set(v) { p.edit().putLong("updateLast", v).apply() }
    var updateSkipped: Int
        get() = int("updateSkipped", 0)
        set(v) = putI("updateSkipped", v)
    var updateNotified: Int
        get() = int("updateNotified", 0)
        set(v) = putI("updateNotified", v)

    // password vault sync
    var syncVault: Boolean
        get() = bool("syncVault", true)
        set(v) = putB("syncVault", v)
    var vaultVer: Long
        get() = p.getLong("vaultVer", 0L)
        set(v) { p.edit().putLong("vaultVer", v).apply() }
    var vaultHash: String
        get() = str("vaultHash", "")
        set(v) = put("vaultHash", v)
    var vaultStatus: String
        get() = str("vaultStatus", "")
        set(v) = put("vaultStatus", v)

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
    var accountUser: String
        get() = str("accountUser", "")
        set(v) = put("accountUser", v)
    var profileName: String
        get() = str("profileName", "")
        set(v) = put("profileName", v)
    var profileId: String
        get() = str("profileId", "")
        set(v) = put("profileId", v)
    var autoSync: Boolean
        get() = bool("autoSync", true)
        set(v) = putB("autoSync", v)
    var syncBookmarks: Boolean
        get() = bool("syncBookmarks", true)
        set(v) = putB("syncBookmarks", v)
    var syncHistory: Boolean
        get() = bool("syncHistory", true)
        set(v) = putB("syncHistory", v)
    var syncSettings: Boolean
        get() = bool("syncSettings", true)
        set(v) = putB("syncSettings", v)
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
    var themeId: String
        get() = str("themeId", "system")
        set(v) = put("themeId", v)
    var appIcon: String
        get() = str("appIcon", "classic")
        set(v) = put("appIcon", v)
    var pullRefresh: Boolean
        get() = bool("pullRefresh", true)
        set(v) = putB("pullRefresh", v)
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
