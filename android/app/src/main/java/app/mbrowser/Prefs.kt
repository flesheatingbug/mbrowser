package app.mbrowser

import android.content.Context
import android.os.Build

class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("mb", Context.MODE_PRIVATE)

    var server: String
        get() = p.getString("server", "") ?: ""
        set(v) = p.edit().putString("server", v.trim().trimEnd('/')).apply()
    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v.trim()).apply()
    var device: String
        get() = p.getString("device", "") ?: "Android ${Build.MODEL}"
        set(v) = p.edit().putString("device", v.trim()).apply()
    var searchUrl: String
        get() = p.getString("search", "") ?: "https://duckduckgo.com/?q="
        set(v) = p.edit().putString("search", v.trim()).apply()
    var bmRev: Long
        get() = p.getLong("bmRev", 0)
        set(v) = p.edit().putLong("bmRev", v).apply()
    var histRev: Long
        get() = p.getLong("histRev", 0)
        set(v) = p.edit().putLong("histRev", v).apply()
    var otherTabs: String
        get() = p.getString("otherTabs", "[]") ?: "[]"
        set(v) = p.edit().putString("otherTabs", v).apply()
    var firstRunDone: Boolean
        get() = p.getBoolean("firstRun", false)
        set(v) = p.edit().putBoolean("firstRun", v).apply()

    val syncConfigured: Boolean get() = server.isNotEmpty() && token.isNotEmpty()
}
