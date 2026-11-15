package app.mbrowser

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/** How the toolbar buttons look: outline, Material 3 Expressive (tinted pills), solid, or soft buttons. */
object IconStyle {
    val LIST = listOf("minimal" to "Minimal outline", "expressive" to "Material 3 Expressive", "solid" to "Solid geometric", "soft" to "Soft buttons")

    private class Btn(val id: Int, val outline: Int, val solid: Int, val solidIn: Set<String>)

    private val BUTTONS = listOf(
        Btn(R.id.btnDownloads, R.drawable.ic_download, R.drawable.ic_download_solid, setOf("solid")),
        Btn(R.id.btnBack, R.drawable.ic_back, R.drawable.ic_back_solid, setOf("solid")),
        Btn(R.id.btnHome, R.drawable.ic_home, R.drawable.ic_home_solid, setOf("solid", "expressive", "soft")),
        Btn(R.id.btnForward, R.drawable.ic_forward, R.drawable.ic_forward_solid, setOf("solid")),
        Btn(R.id.tabsIcon, R.drawable.ic_tabs, R.drawable.ic_tabs_solid, setOf("solid")),
    )

    fun apply(act: Activity) {
        val style = Prefs(act).iconStyle
        val tint = ColorStateList.valueOf(if (style == "expressive") act.tc(R.attr.mbAccent) else act.tc(R.attr.mbText))
        for (b in BUTTONS) {
            val v = act.findViewById<ImageView>(b.id) ?: continue
            v.setImageResource(if (style in b.solidIn) b.solid else b.outline)
            v.imageTintList = tint
            // the tab button's background sits on the frame around its icon
            val target: View = if (b.id == R.id.tabsIcon) v.parent as View else v
            target.background = background(act, style)
            target.elevation = if (style == "soft") act.dp(2).toFloat() else 0f
        }
        act.findViewById<TextView>(R.id.tabCount)?.setTextColor(if (style == "solid") act.tc(R.attr.mbBar) else if (style == "expressive") act.tc(R.attr.mbAccent) else act.tc(R.attr.mbText))
        // the reload button in the address bar follows the solid style too
        act.findViewById<ImageView>(R.id.btnReload)?.setImageResource(if (style == "solid") R.drawable.ic_reload_solid else R.drawable.ic_reload)
    }

    private fun background(act: Activity, style: String): Drawable? {
        val shape = GradientDrawable()
        when (style) {
            "expressive" -> { shape.cornerRadius = act.dp(22).toFloat(); shape.setColor(withAlpha(act.tc(R.attr.mbAccent), 0x2E)) }
            "soft" -> { shape.cornerRadius = act.dp(14).toFloat(); shape.setColor(act.tc(R.attr.mbPill)) }
            else -> {
                val tv = android.util.TypedValue()
                act.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
                return act.getDrawable(tv.resourceId)
            }
        }
        val inset = InsetDrawable(shape, act.dp(if (style == "expressive") 8 else 10), act.dp(8), act.dp(if (style == "expressive") 8 else 10), act.dp(8))
        return RippleDrawable(ColorStateList.valueOf(withAlpha(act.tc(R.attr.mbText), 0x30)), inset, null)
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}
