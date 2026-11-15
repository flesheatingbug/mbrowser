package app.mbrowser

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** How the toolbar buttons look: outline, Material 3 Expressive (tinted pills), solid, or soft buttons. */
object IconStyle {
    val LIST = listOf("minimal" to "Minimal outline", "expressive" to "Material 3 Expressive", "solid" to "Solid geometric", "soft" to "Soft buttons")

    class Glyph(val outline: Int, val solid: Int, val solidIn: Set<String>)

    val GLYPHS = mapOf(
        "downloads" to Glyph(R.drawable.ic_download, R.drawable.ic_download_solid, setOf("solid")),
        "back" to Glyph(R.drawable.ic_back, R.drawable.ic_back_solid, setOf("solid")),
        "home" to Glyph(R.drawable.ic_home, R.drawable.ic_home_solid, setOf("solid", "expressive", "soft")),
        "forward" to Glyph(R.drawable.ic_forward, R.drawable.ic_forward_solid, setOf("solid")),
        "tabs" to Glyph(R.drawable.ic_tabs, R.drawable.ic_tabs_solid, setOf("solid")),
        "search" to Glyph(R.drawable.ic_search, R.drawable.ic_search, setOf()),
        "newtab" to Glyph(R.drawable.ic_add, R.drawable.ic_add, setOf()),
        "reload" to Glyph(R.drawable.ic_reload, R.drawable.ic_reload_solid, setOf("solid")),
        "bookmarks" to Glyph(R.drawable.ic_star, R.drawable.ic_star_filled, setOf("solid")),
        "menu" to Glyph(R.drawable.ic_more, R.drawable.ic_more, setOf()),
    )

    fun glyph(key: String, style: String): Int { val g = GLYPHS[key] ?: GLYPHS["home"]!!; return if (style in g.solidIn) g.solid else g.outline }

    /** Styles the buttons of the bottom bar (and the reload button in the address bar). */
    fun apply(act: Activity, onWallpaper: Boolean) {
        val style = Prefs(act).iconStyle
        val bar = act.findViewById<ViewGroup>(R.id.bottomBar) ?: return
        for (i in 0 until bar.childCount) styleCell(act, bar.getChildAt(i), style, onWallpaper)
        act.findViewById<ImageView>(R.id.btnReload)?.setImageResource(if (style == "solid") R.drawable.ic_reload_solid else R.drawable.ic_reload)
    }

    /** One bar button: [cell] is its ImageButton, or the frame around the tab button's icon and count. */
    fun styleCell(ctx: Context, cell: View, style: String, onWallpaper: Boolean = false) {
        val key = cell.tag as? String ?: return
        val icon: ImageView = (if (cell is FrameLayout) cell.getChildAt(0) else cell) as ImageView
        val tint = if (onWallpaper) Color.WHITE else if (style == "expressive") ctx.tc(R.attr.mbAccent) else ctx.tc(R.attr.mbText)
        icon.setImageResource(glyph(key, style))
        icon.imageTintList = ColorStateList.valueOf(tint)
        cell.background = background(ctx, style, onWallpaper)
        cell.elevation = if (style == "soft" && !onWallpaper) ctx.dp(2).toFloat() else 0f
        if (cell is FrameLayout) {
            (cell.getChildAt(1) as? TextView)?.setTextColor(if (style == "solid" && !onWallpaper) ctx.tc(R.attr.mbBar) else tint)
        }
    }

    fun background(ctx: Context, style: String, onWallpaper: Boolean = false): Drawable? {
        val shape = GradientDrawable()
        when (style) {
            "expressive" -> { shape.cornerRadius = ctx.dp(22).toFloat(); shape.setColor(withAlpha(if (onWallpaper) Color.WHITE else ctx.tc(R.attr.mbAccent), if (onWallpaper) 0x38 else 0x2E)) }
            "soft" -> { shape.cornerRadius = ctx.dp(14).toFloat(); shape.setColor(if (onWallpaper) withAlpha(Color.BLACK, 0x66) else ctx.tc(R.attr.mbPill)) }
            else -> {
                val tv = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
                return ctx.getDrawable(tv.resourceId)
            }
        }
        val h = ctx.dp(if (style == "expressive") 8 else 10)
        val inset = InsetDrawable(shape, h, ctx.dp(8), h, ctx.dp(8))
        return RippleDrawable(ColorStateList.valueOf(withAlpha(if (onWallpaper) Color.WHITE else ctx.tc(R.attr.mbText), 0x30)), inset, null)
    }

    /** A row of the given buttons in this style, for the settings previews. */
    fun sample(ctx: Context, style: String, keys: List<String>, cellDp: Int = 40, spread: Boolean = false): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER
        for (k in keys) {
            val iv = ImageView(ctx)
            iv.tag = k
            iv.scaleType = ImageView.ScaleType.CENTER
            iv.setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
            styleCell(ctx, iv, style)
            iv.isClickable = false
            row.addView(iv, if (spread) LinearLayout.LayoutParams(0, ctx.dp(cellDp), 1f) else LinearLayout.LayoutParams(ctx.dp(cellDp), ctx.dp(cellDp)))
        }
        return row
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}
