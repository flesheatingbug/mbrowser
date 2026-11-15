package app.mbrowser

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** The colour themes (the same ones as the desktop browser). "system" keeps the original light/dark look. */
object Themes {
    val LIST = listOf(
        "system" to "Default (follows system)",
        "mbrowser" to "mbrowser",
        "midnight" to "Midnight",
        "daylight" to "Daylight",
        "nord" to "Nord",
        "dracula" to "Dracula",
        "gruvbox" to "Gruvbox",
        "solarized-dark" to "Solarized Dark",
        "solarized-light" to "Solarized Light",
        "forest" to "Forest",
        "ocean" to "Ocean",
        "rose" to "Rose",
        "sand" to "Sand",
        "contrast" to "High contrast",
    )

    fun styleId(ctx: android.content.Context, id: String): Int {
        if (id == "system") return R.style.Theme_MBrowser
        val r = ctx.resources.getIdentifier("MbTheme_" + id.replace('-', '_'), "style", ctx.packageName)
        return if (r != 0) r else R.style.Theme_MBrowser
    }

    fun styleFor(ctx: android.content.Context): Int = styleId(ctx, Prefs(ctx).themeId)
}

/** Every screen starts from here so it gets the chosen theme, and redraws when the theme changes. */
open class BaseActivity : AppCompatActivity() {
    protected var appliedTheme = ""
    override fun onCreate(savedInstanceState: Bundle?) {
        appliedTheme = Prefs(this).themeId
        setTheme(Themes.styleFor(this))
        super.onCreate(savedInstanceState)
    }
    override fun onResume() {
        super.onResume()
        if (Prefs(this).themeId != appliedTheme) recreate()
    }
}
