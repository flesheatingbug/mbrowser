package app.mbrowser

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** The logo choices from the desktop browser: used for the logo in the address bar and for the home screen icon. */
object AppIcon {
    // id, label, launcher alias, in-app drawable (null = the normal app icon)
    val LIST = listOf(
        Entry("classic", "Classic", "LauncherClassic", null),
        Entry("color", "Logo (colour)", "LauncherColor", R.drawable.logo_color),
        Entry("blue", "Logo (blue)", "LauncherBlue", R.drawable.logo_blue),
        Entry("white", "Logo (white)", "LauncherWhite", R.drawable.logo_white),
        Entry("blue-m", "M (blue)", "LauncherBlueM", R.drawable.logo_blue_m),
        Entry("white-m", "M (white)", "LauncherWhiteM", R.drawable.logo_white_m),
    )
    class Entry(val id: String, val label: String, val alias: String, val logo: Int?)

    fun current(ctx: Context): Entry = LIST.firstOrNull { it.id == Prefs(ctx).appIcon } ?: LIST[0]

    /** Switches the home screen icon (the launcher can take a moment, or a restart of it, to show the change). */
    fun applyLauncher(ctx: Context) {
        val pm = ctx.packageManager
        val want = current(ctx).alias
        pm.setComponentEnabledSetting(ComponentName(ctx, "app.mbrowser.$want"), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        for (e in LIST) if (e.alias != want)
            pm.setComponentEnabledSetting(ComponentName(ctx, "app.mbrowser.${e.alias}"), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }
}
