package app.mbrowser

import android.content.Context
import org.json.JSONObject

/**
 * The part of the Android setup that follows you between devices and survives a reset:
 * theme, wallpaper choice, toolbar, button style, app icon, speed dial look, user agent, translate language.
 * Device-specific things (server login, text size, update settings) stay on the phone.
 */
object Look {
    fun json(p: Prefs): JSONObject = JSONObject()
        .put("themeMode", p.themeMode).put("themeId", p.themeId).put("iconStyle", p.iconStyle).put("appIcon", p.appIcon)
        .put("toolbarPreset", p.toolbarPreset).put("toolbarCustom", p.toolbarCustom)
        .put("wallpaper", p.wallpaper).put("dialStyle", p.dialStyle).put("dialCols", p.dialCols)
        .put("showTitles", p.showTitles).put("showThumbs", p.showThumbs).put("showAdd", p.showAdd).put("pullRefresh", p.pullRefresh)
        .put("uaMode", p.uaMode).put("translateLang", p.translateLang).put("blockTrackers", p.blockTrackers)

    fun hash(p: Prefs): String = json(p).toString().hashCode().toString()

    /** Applies a synced look to this phone. */
    fun apply(ctx: Context, p: Prefs, v: JSONObject) {
        if (v.has("themeMode")) { p.themeMode = v.optInt("themeMode"); try { App.applyTheme(p.themeMode) } catch (_: Exception) { } }
        if (v.has("themeId")) p.themeId = v.optString("themeId")
        if (v.has("iconStyle")) p.iconStyle = v.optString("iconStyle")
        if (v.has("appIcon")) {
            val id = v.optString("appIcon")
            if (id != p.appIcon && AppIcon.LIST.any { it.id == id }) { p.appIcon = id; try { AppIcon.applyLauncher(ctx) } catch (_: Exception) { } }
        }
        if (v.has("toolbarPreset")) p.toolbarPreset = v.optString("toolbarPreset")
        if (v.has("toolbarCustom")) p.toolbarCustom = v.optString("toolbarCustom")
        if (v.has("wallpaper")) {
            val w = v.optString("wallpaper")
            if (w != "custom" || Wallpapers.hasCustom(ctx)) p.wallpaper = w
        }
        if (v.has("dialStyle")) p.dialStyle = v.optString("dialStyle")
        if (v.has("dialCols")) p.dialCols = v.optInt("dialCols", 3)
        if (v.has("showTitles")) p.showTitles = v.optBoolean("showTitles", true)
        if (v.has("showThumbs")) p.showThumbs = v.optBoolean("showThumbs", true)
        if (v.has("showAdd")) p.showAdd = v.optBoolean("showAdd", true)
        if (v.has("pullRefresh")) p.pullRefresh = v.optBoolean("pullRefresh", true)
        if (v.has("uaMode")) p.uaMode = v.optString("uaMode")
        if (v.has("translateLang")) p.translateLang = v.optString("translateLang")
        if (v.has("blockTrackers")) p.blockTrackers = v.optBoolean("blockTrackers", true)
    }
}
