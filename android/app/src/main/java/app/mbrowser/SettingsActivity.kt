package app.mbrowser

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.OnBackPressedCallback
import android.widget.EditText
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.util.concurrent.Executors

class SettingsActivity : BaseActivity() {
    private lateinit var prefs: Prefs
    private lateinit var box: LinearLayout
    private lateinit var bar: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var query = ""
    private var searching = false
    private var page = "main"

    private class Pg(val id: String, val title: String, val sub: String, val icon: Int, val fill: () -> Unit)

    private val pages by lazy {
        listOf(
            Pg("browser", "Browser", "Identify as, text size, translate", R.drawable.ic_globe) { browserPage() },
            Pg("appearance", "Appearance", "Themes, button style, logo", R.drawable.ic_tune) { appearancePage() },
            Pg("start", "Start page", "Speed dials, thumbnails", R.drawable.ic_home) { startPageSettings() },
            Pg("data", "Personal data", "Sync & backup", R.drawable.ic_cloud) { dataPage() },
            Pg("passwords", "Passwords", "Saved logins, autofill, unlock", R.drawable.ic_key) { passwordsPage() },
            Pg("bookmarks", "Bookmarks", "Manage and sync", R.drawable.ic_bookmarks) { bookmarksPage() },
            Pg("history", "History", "Browse, sync and clear", R.drawable.ic_history) { historyPage() },
            Pg("privacy", "Privacy & security", "Ad blocker, clear browsing data", R.drawable.ic_shield) { privacyPage() },
            Pg("search", "Search", "Search engines", R.drawable.ic_search) { searchRows() },
            Pg("homenet", "Home network", "Open your home devices from anywhere", R.drawable.ic_devices) { homeNetPage() },
            Pg("about", "About", "Updates, source code", R.drawable.ic_info) { aboutPage() },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Store.init(this)
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "main"
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(tc(R.attr.mbBg))
        root.fitsSystemWindows = true
        bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = android.view.Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(tc(R.attr.mbBg))
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))
        val scroll = ScrollView(this)
        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, 0, 0, dp(32))
        scroll.addView(box, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    searching -> { searching = false; query = ""; build() }
                    page != "main" -> { page = "main"; build() }
                    else -> finish()
                }
            }
        })
        build()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("page", page)
    }

    private fun barButton(icon: Int, desc: String, onClick: () -> Unit): ImageButton {
        val b = ImageButton(this)
        b.setImageResource(icon)
        b.imageTintList = tcl(R.attr.mbText)
        b.contentDescription = desc
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        b.setBackgroundResource(tv.resourceId)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildBar() {
        bar.removeAllViews()
        bar.addView(barButton(R.drawable.ic_back, "Back") { onBackPressedDispatcher.onBackPressed() }, LinearLayout.LayoutParams(dp(56), -1))
        if (searching) {
            val e = EditText(this)
            e.hint = "Search settings"
            e.setSingleLine()
            e.setText(query)
            e.setSelection(e.text.length)
            e.setTextColor(tc(R.attr.mbText)); e.setHintTextColor(tc(R.attr.mbMuted))
            e.background = null
            e.textSize = 18f
            e.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { query = s?.toString() ?: ""; applyFilter() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            bar.addView(e, LinearLayout.LayoutParams(0, -1, 1f))
            bar.addView(barButton(R.drawable.ic_close, "Clear") { if (query.isEmpty()) { searching = false; build() } else e.setText("") }, LinearLayout.LayoutParams(dp(56), -1))
            e.requestFocus()
            e.post { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(e, 0) }
        } else {
            bar.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f))
            bar.addView(barButton(R.drawable.ic_search, "Search settings") { searching = true; build() }, LinearLayout.LayoutParams(dp(56), -1))
        }
    }

    private fun bigTitle(t: String) {
        val v = TextView(this)
        v.text = t
        v.textSize = 34f
        v.typeface = Typeface.DEFAULT_BOLD
        v.setTextColor(tc(R.attr.mbText))
        v.setPadding(dp(20), dp(8), dp(20), dp(14))
        box.addView(v)
    }

    private fun build() {
        buildBar()
        box.removeAllViews()
        if (searching) {
            for (p in pages) { header(p.title); p.fill() }
            applyFilter()
            return
        }
        if (page == "main") { mainPage(); return }
        val p = pages.firstOrNull { it.id == page } ?: run { page = "main"; mainPage(); return }
        bigTitle(p.title)
        p.fill()
    }

    // ---------- the main list ----------
    private fun mainPage() {
        bigTitle("Settings")
        switchRow("Ad blocker", if (prefs.blockTrackers) "Enabled" else "Disabled", { prefs.blockTrackers }, { prefs.blockTrackers = it })
        val sep = View(this); sep.setBackgroundColor(tc(R.attr.mbLine))
        box.addView(sep, LinearLayout.LayoutParams(-1, dp(1)).also { it.setMargins(0, dp(8), 0, dp(8)) })
        for (p in pages) navRow(p)
    }

    private fun navRow(p: Pg) {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = android.view.Gravity.CENTER_VERTICAL
        r.setPadding(dp(16), dp(10), dp(16), dp(10))
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        r.setBackgroundResource(tv.resourceId)
        val ic = ImageView(this)
        ic.setImageResource(p.icon)
        ic.imageTintList = tcl(R.attr.mbText)
        ic.setPadding(dp(13), dp(13), dp(13), dp(13))
        val g = GradientDrawable(); g.cornerRadius = dp(14).toFloat(); g.setColor(tc(R.attr.mbCard))
        ic.background = g
        r.addView(ic, LinearLayout.LayoutParams(dp(50), dp(50)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t = TextView(this); t.text = p.title; t.textSize = 18f; t.setTextColor(tc(R.attr.mbText))
        val s = TextView(this); s.text = p.sub; s.textSize = 14f; s.setTextColor(tc(R.attr.mbMuted))
        col.addView(t); col.addView(s)
        val cl = LinearLayout.LayoutParams(0, -2, 1f); cl.marginStart = dp(16)
        r.addView(col, cl)
        r.setOnClickListener { page = p.id; build() }
        box.addView(r, LinearLayout.LayoutParams(-1, -2))
    }

    // ---------- previews ----------
    private fun ring(selected: Boolean, radius: Int): GradientDrawable {
        val g = GradientDrawable(); g.cornerRadius = dp(radius).toFloat(); g.setColor(android.graphics.Color.TRANSPARENT)
        g.setStroke(dp(if (selected) 3 else 1), tc(if (selected) R.attr.mbAccent else R.attr.mbLine))
        return g
    }

    private fun filled(radius: Int, color: Int): GradientDrawable { val g = GradientDrawable(); g.cornerRadius = dp(radius).toFloat(); g.setColor(color); return g }

    private fun hscroll(tag: String, build: (LinearLayout) -> Unit): HorizontalScrollView {
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.tag = tag
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(12), dp(8), dp(12), dp(8))
        build(row)
        hs.addView(row)
        box.addView(hs)
        return hs
    }

    private fun roundClip(v: View, radius: Int) {
        v.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, view.width, view.height, dp(radius).toFloat()) }
        }
        v.clipToOutline = true
    }

    private fun wallpaperSection() {
        header("Wallpaper")
        val note = TextView(this)
        note.text = "Fills the screen behind your speed dial, from the status bar to the navigation bar."
        note.textSize = 13f; note.setTextColor(tc(R.attr.mbMuted)); note.setPadding(dp(20), 0, dp(20), dp(2)); note.tag = "wallpaper background picture"
        box.addView(note)
        hscroll("wallpaper background picture photo") { row ->
            fun tile(id: String, label: String, bmp: android.graphics.Bitmap?, onClick: () -> Unit) {
                val on = prefs.wallpaper == id
                val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; col.gravity = android.view.Gravity.CENTER_HORIZONTAL
                val fl = FrameLayout(this)
                if (bmp != null) {
                    val iv = ImageView(this); iv.setImageBitmap(bmp); iv.scaleType = ImageView.ScaleType.CENTER_CROP
                    fl.addView(iv, FrameLayout.LayoutParams(-1, -1)); roundClip(fl, 14)
                } else {
                    fl.background = filled(14, tc(R.attr.mbCard))
                    val t = TextView(this); t.text = if (id == "none") "✕" else "＋"; t.textSize = 26f; t.gravity = android.view.Gravity.CENTER; t.setTextColor(tc(R.attr.mbMuted))
                    fl.addView(t, FrameLayout.LayoutParams(-1, -1))
                }
                fl.foreground = ring(on, 14)
                col.addView(fl, LinearLayout.LayoutParams(dp(78), dp(138)))
                val l = TextView(this); l.text = label; l.textSize = 12f; l.maxLines = 1; l.gravity = android.view.Gravity.CENTER
                l.setTextColor(tc(if (on) R.attr.mbText else R.attr.mbMuted))
                col.addView(l, LinearLayout.LayoutParams(dp(78), -2))
                col.setOnClickListener { onClick() }
                val lp = LinearLayout.LayoutParams(-2, -2); lp.setMargins(dp(5), 0, dp(5), 0)
                row.addView(col, lp)
            }
            tile("none", "Theme colours", null) { prefs.wallpaper = "none"; build() }
            tile("custom", "Your photo", if (Wallpapers.hasCustom(this)) Wallpapers.thumb(this, "custom") else null) { wpPicker.launch("image/*") }
            for ((id, name) in Wallpapers.LIST) tile(id, name, Wallpapers.thumb(this, id)) { prefs.wallpaper = id; build() }
        }
    }

    private fun buttonStyleSection() {
        hscroll("button style icons outline solid soft expressive") { row ->
            for ((id, name) in IconStyle.LIST) {
                val on = prefs.iconStyle == id
                val card = LinearLayout(this); card.orientation = LinearLayout.VERTICAL
                card.background = ring(on, 16)
                card.setPadding(dp(8), dp(8), dp(8), dp(8))
                val strip = FrameLayout(this); strip.background = filled(12, tc(R.attr.mbBar))
                strip.addView(IconStyle.sample(this, id, listOf("back", "home", "tabs", "menu"), 38), FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
                strip.setPadding(dp(4), dp(6), dp(4), dp(6))
                card.addView(strip, LinearLayout.LayoutParams(dp(200), -2))
                val l = TextView(this); l.text = (if (on) "✓  " else "") + name; l.textSize = 13f; l.gravity = android.view.Gravity.CENTER
                l.setTextColor(tc(R.attr.mbText)); l.setPadding(0, dp(8), 0, 0)
                card.addView(l, LinearLayout.LayoutParams(dp(200), -2))
                card.setOnClickListener { prefs.iconStyle = id; build() }
                val lp = LinearLayout.LayoutParams(-2, -2); lp.setMargins(dp(5), 0, dp(5), 0)
                row.addView(card, lp)
            }
        }
    }

    private fun toolbarSection() {
        val style = prefs.iconStyle
        fun card(id: String, label: String, keys: List<String>, onClick: () -> Unit) {
            val on = prefs.toolbarPreset == id
            val c = LinearLayout(this); c.orientation = LinearLayout.VERTICAL
            c.background = ring(on, 16)
            c.setPadding(dp(12), dp(10), dp(12), dp(10))
            c.tag = "toolbar bottom bar buttons $label"
            val t = TextView(this); t.text = (if (on) "✓  " else "") + label; t.textSize = 15f; t.typeface = Typeface.DEFAULT_BOLD; t.setTextColor(tc(R.attr.mbText))
            c.addView(t)
            val strip = FrameLayout(this); strip.background = filled(12, tc(R.attr.mbBar)); strip.setPadding(dp(4), dp(4), dp(4), dp(4))
            strip.addView(IconStyle.sample(this, style, keys, 40, true), FrameLayout.LayoutParams(-1, -2))
            val sl = LinearLayout.LayoutParams(-1, -2); sl.topMargin = dp(8)
            c.addView(strip, sl)
            c.setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(-1, -2); lp.setMargins(dp(16), dp(4), dp(16), dp(4))
            box.addView(c, lp)
        }
        for (p in Toolbars.PRESETS) card(p.id, p.label, p.keys) { prefs.toolbarPreset = p.id; build() }
        card("custom", "Custom", Toolbars.custom(prefs)) {
            val keys = Toolbars.NAMES.keys.toList()
            val chosen = BooleanArray(keys.size) { keys[it] in Toolbars.custom(prefs) }
            AlertDialog.Builder(this).setTitle("Choose the buttons (up to 6)")
                .setMultiChoiceItems(keys.map { Toolbars.NAMES[it] }.toTypedArray(), chosen) { _, i, v -> chosen[i] = v }
                .setPositiveButton("Use these") { _, _ ->
                    val picked = keys.filterIndexed { i, _ -> chosen[i] }
                    if (picked.size !in 1..6) toast("Pick between 1 and 6 buttons")
                    else { prefs.toolbarCustom = picked.joinToString(","); prefs.toolbarPreset = "custom"; build() }
                }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun dialStyleSection() {
        hscroll("speed dial style cards icons only tiles") {  row ->
            for ((id, name) in listOf("cards" to "Cards with page previews", "icons" to "Icons only")) {
                val on = prefs.dialStyle == id
                val card = LinearLayout(this); card.orientation = LinearLayout.VERTICAL
                card.background = ring(on, 16)
                card.setPadding(dp(8), dp(8), dp(8), dp(8))
                card.addView(DialStylePreview(this, id == "icons", tc(R.attr.mbBg), tc(R.attr.mbCard), tc(R.attr.mbText), tc(R.attr.mbAccent)), LinearLayout.LayoutParams(dp(160), dp(120)))
                val l = TextView(this); l.text = (if (on) "✓  " else "") + name; l.textSize = 13f; l.gravity = android.view.Gravity.CENTER
                l.setTextColor(tc(R.attr.mbText)); l.setPadding(0, dp(8), 0, 0)
                card.addView(l, LinearLayout.LayoutParams(dp(160), -2))
                card.setOnClickListener { prefs.dialStyle = id; build() }
                val lp = LinearLayout.LayoutParams(-2, -2); lp.setMargins(dp(5), 0, dp(5), 0)
                row.addView(card, lp)
            }
        }
    }

    // ---------- home network ----------
    private fun homeNetPage() {
        HomeNet.seed(prefs)
        var sw: SwitchCompat? = null
        var reverting = false
        sw = switchRow("Home network mode", "Addresses on a home network (192.168.x.x, router.lan, a bare name like nas) open on YOUR home network through your own sync server, wherever you are. Everything else goes direct. The server needs ALLOW_TUNNEL=true. This switch and the servers below belong to this profile only.", { prefs.homeNetwork }, { on ->
            if (!reverting) {
                if (on) HomeNet.turnOn(this, prefs) { err ->
                    if (err != null) { toast(err); reverting = true; sw?.isChecked = false; reverting = false } else toast("Home network is on")
                } else HomeNet.turnOff(this, prefs)
            }
        })
        header("Servers")
        val list = HomeNet.servers(prefs)
        if (list.isEmpty()) actionRow("No servers yet", "Add the address of a sync server below") { }
        for (s in list) {
            val status = if (s.key) "Uses this profile's sync key (less secure)" else if (s.token.isNotEmpty()) "Signed in as ${s.user}" else "Not signed in"
            val inUse = if (s.id == (HomeNet.activeCfg(prefs)?.id ?: prefs.homeActive.ifEmpty { list.first().id })) "  ·  in use" else ""
            actionRow(s.name + inUse, s.url + "\n" + status) { serverMenu(s) }
        }
        actionRow("Add a server", "Another home you want to reach") { addServerDialog() }
        header("Addresses")
        actionRow("Addresses that use the home network", (if (prefs.homeRanges.isBlank()) "The usual home ranges (192.168.x.x, 10.x.x.x, 172.16-31.x.x)" else prefs.homeRanges.lines().filter { it.isNotBlank() }.joinToString(", ")) + "\nTap to change") { rangesDialog() }
    }

    private fun rangesDialog() {
        val e = EditText(this); e.setText(prefs.homeRanges); e.hint = "192.168.50.0/24\n10.8.0.0/16\nnas.example.com"
        e.minLines = 4; e.gravity = android.view.Gravity.TOP
        AlertDialog.Builder(this).setTitle("Home network addresses")
            .setMessage("One per line: a range like 192.168.50.0/24, a single address, or a name like nas.example.com or .lan. Leave empty for the usual home ranges. Names without a dot and names ending in .local, .lan, .home or .internal always use it.")
            .setView(e)
            .setPositiveButton("Save") { _, _ -> prefs.homeRanges = e.text.toString().trim(); build() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun refreshHome() { HomeNet.apply(this, prefs); build() }

    private fun serverMenu(s: HomeNet.Srv) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("Use this server" to { prefs.homeActive = s.id; refreshHome() })
        if (!s.key) {
            if (s.token.isNotEmpty()) items.add("Sign out" to {
                Thread { HomeNet.logout(s.url, s.token) }.start()
                HomeNet.update(prefs, s.id) { it.copy(token = "", user = "") }; refreshHome()
            }) else items.add("Sign in" to { signInDialog(s, "", "", null) })
        }
        if (prefs.syncConfigured && prefs.server.trimEnd('/') == s.url)
            items.add((if (s.key) "Sign in with a password instead" else "Use my sync key instead (less secure)") to {
                HomeNet.update(prefs, s.id) { it.copy(key = !s.key) }; refreshHome() })
        items.add("Remove" to { HomeNet.remove(prefs, s.id); refreshHome() })
        AlertDialog.Builder(this).setTitle(s.name).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun addServerDialog() {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; col.setPadding(dp(20), dp(8), dp(20), 0)
        val name = EditText(this); name.hint = "Name (e.g. Home)"; name.setSingleLine()
        val url = EditText(this); url.hint = "https://sync.example.com"; url.setSingleLine(); url.inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        if (prefs.syncConfigured && HomeNet.servers(prefs).none { it.url == prefs.server.trimEnd('/') }) url.setText(prefs.server)
        col.addView(name); col.addView(url)
        AlertDialog.Builder(this).setTitle("Add a server").setView(col)
            .setPositiveButton("Add") { _, _ -> HomeNet.add(prefs, name.text.toString(), url.text.toString())?.let { toast(it) }; build() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun signInDialog(s: HomeNet.Srv, user0: String, pw0: String, note: String?) {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; col.setPadding(dp(20), dp(8), dp(20), 0)
        val msg = TextView(this); msg.text = note ?: "Use the same account name and password as the server's web page."; msg.textSize = 13f; msg.setTextColor(tc(R.attr.mbMuted))
        val u = EditText(this); u.hint = "User name"; u.setSingleLine(); u.setText(user0)
        val p = EditText(this); p.hint = "Password"; p.setSingleLine(); p.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; p.setText(pw0)
        val o = EditText(this); o.hint = "One-time code (if you use one)"; o.setSingleLine(); o.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        col.addView(msg); col.addView(u); col.addView(p); col.addView(o)
        AlertDialog.Builder(this).setTitle("Sign in to " + s.name).setView(col)
            .setPositiveButton("Sign in") { _, _ ->
                val un = u.text.toString().trim(); val pw = p.text.toString(); val otp = o.text.toString()
                Thread {
                    val r = HomeNet.login(s.url, un, pw, otp, prefs.device)
                    runOnUiThread {
                        if (r.token != null) {
                            HomeNet.update(prefs, s.id) { it.copy(token = r.token, user = r.user) }
                            toast("Signed in"); refreshHome()
                        } else signInDialog(s, un, pw, r.error)
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- pages ----------
    private fun browserPage() {
        switchRow("Request desktop sites", "For new tabs", { prefs.desktopSite }, { prefs.desktopSite = it })
        choiceRow("Text size", listOf("Small (80%)", "Normal (100%)", "Large (120%)", "Extra large (150%)"),
            { listOf(80, 100, 120, 150).indexOf(prefs.textZoom).coerceAtLeast(1) },
            { prefs.textZoom = listOf(80, 100, 120, 150)[it] })
        switchRow("Beta features", "Shows features still being tested, such as casting to a TV. Off by default", { prefs.beta }, { prefs.beta = it })
        switchRow("Load tabs only when opened", "After a restart, tabs wait until you switch to them (saves data and battery). Off: all load at once", { prefs.lazyTabs }, { prefs.lazyTabs = it })
        switchRow("Hide bars while scrolling", "The address bar and toolbar slide away as you read down a page", { prefs.hideBarsOnScroll }, { prefs.hideBarsOnScroll = it })
        switchRow("Pull down to refresh", "Off if it gets in the way of pages with their own drag gestures", { prefs.pullRefresh }, { prefs.pullRefresh = it })
        choiceRow("Pull-to-refresh area", listOf("Full screen", "Top of screen", "Bottom of screen"),
            { listOf("full", "top", "bottom").indexOf(prefs.pullArea).coerceAtLeast(0) },
            { prefs.pullArea = listOf("full", "top", "bottom")[it] })
        choiceRow("Identify as", UserAgents.MODES.map { it.second }, { UserAgents.MODES.indexOfFirst { it.first == prefs.uaMode }.coerceAtLeast(0) },
            { prefs.uaMode = UserAgents.MODES[it].first; toast("Applied to open tabs when you return to the browser") })
        choiceRow("Translate to", Translate.LANGS.map { it.second }, { Translate.LANGS.indexOfFirst { it.first == prefs.translateLang }.coerceAtLeast(0) },
            { prefs.translateLang = Translate.LANGS[it].first })
    }

    private fun appearancePage() {
        header("Theme")
        val cards = LinearLayout(this)
        cards.orientation = LinearLayout.HORIZONTAL
        cards.setPadding(dp(12), dp(4), dp(12), dp(8))
        cards.tag = "theme light dark follow system appearance"
        listOf("Follow system", "Light", "Dark").forEachIndexed { i, n ->
            val on = prefs.themeId == "system" && prefs.themeMode == i
            val c = TextView(this)
            c.text = if (on) "✓  $n" else n
            c.gravity = android.view.Gravity.CENTER
            c.textSize = 14f
            c.typeface = Typeface.DEFAULT_BOLD
            c.setTextColor(tc(R.attr.mbText))
            val g = GradientDrawable(); g.cornerRadius = dp(18).toFloat(); g.setColor(tc(R.attr.mbCard))
            g.setStroke(dp(if (on) 3 else 1), tc(if (on) R.attr.mbAccent else R.attr.mbLine))
            c.background = g
            c.setOnClickListener {
                val was = prefs.themeId
                prefs.themeId = "system"; prefs.themeMode = i; App.applyTheme(i)
                if (was != "system") recreate() else build()
            }
            val lp = LinearLayout.LayoutParams(0, dp(80), 1f); lp.setMargins(dp(4), 0, dp(4), 0)
            cards.addView(c, lp)
        }
        box.addView(cards)

        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.tag = "colour theme themes preview"
        val rowv = LinearLayout(this)
        rowv.orientation = LinearLayout.HORIZONTAL
        rowv.setPadding(dp(12), dp(8), dp(12), dp(8))
        for ((id, name) in Themes.LIST) {
            val w = android.view.ContextThemeWrapper(this, Themes.styleId(this, id))
            val on = prefs.themeId == id
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = android.view.Gravity.CENTER_HORIZONTAL
            col.addView(ThemePreview(this, w.tc(R.attr.mbBg), w.tc(R.attr.mbBar), w.tc(R.attr.mbPill), w.tc(R.attr.mbText), w.tc(R.attr.mbAccent),
                if (on) tc(R.attr.mbAccent) else w.tc(R.attr.mbLine), on), LinearLayout.LayoutParams(dp(88), dp(124)))
            val l = TextView(this)
            l.text = name.substringBefore(" (")
            l.textSize = 12f
            l.maxLines = 1
            l.ellipsize = android.text.TextUtils.TruncateAt.END
            l.gravity = android.view.Gravity.CENTER
            l.setTextColor(tc(if (on) R.attr.mbText else R.attr.mbMuted))
            col.addView(l, LinearLayout.LayoutParams(dp(88), -2))
            col.setOnClickListener { prefs.themeId = id; recreate() }
            val lp = LinearLayout.LayoutParams(-2, -2); lp.setMargins(dp(5), 0, dp(5), 0)
            rowv.addView(col, lp)
        }
        hs.addView(rowv)
        box.addView(hs)

        wallpaperSection()

        header("Button style")
        buttonStyleSection()

        header("Bottom toolbar")
        toolbarSection()

        header("App icon")
        appIconSection()
    }

    private fun appIconSection() {
        hscroll("app icon logo launcher home screen") { row ->
            for (e in AppIcon.LIST) {
                val on = prefs.appIcon == e.id
                val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; col.gravity = android.view.Gravity.CENTER_HORIZONTAL
                col.background = ring(on, 16); col.setPadding(dp(8), dp(8), dp(8), dp(8))
                val iv = ImageView(this)
                iv.setImageDrawable(androidx.core.content.ContextCompat.getDrawable(this, if (e.id == "classic") R.mipmap.ic_launcher else resources.getIdentifier("ic_launcher_" + e.id.replace('-', '_'), "mipmap", packageName)))
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                col.addView(iv, LinearLayout.LayoutParams(dp(64), dp(64)))
                val l = TextView(this); l.text = (if (on) "✓ " else "") + e.label; l.textSize = 12f; l.gravity = android.view.Gravity.CENTER; l.maxLines = 1
                l.setTextColor(tc(if (on) R.attr.mbText else R.attr.mbMuted)); l.setPadding(0, dp(6), 0, 0)
                col.addView(l, LinearLayout.LayoutParams(dp(84), -2))
                col.setOnClickListener {
                    prefs.appIcon = e.id; AppIcon.applyLauncher(this)
                    toast("Address bar logo changed. The home screen icon may take a moment to follow."); build()
                }
                val lp = LinearLayout.LayoutParams(-2, -2); lp.setMargins(dp(5), 0, dp(5), 0)
                row.addView(col, lp)
            }
        }
    }

    private fun startPageSettings() {
        header("Speed dial style")
        dialStyleSection()
        header("Layout")
        choiceRow("Columns", listOf("2", "3", "4", "5"), { (prefs.dialCols - 2).coerceIn(0, 3) }, { prefs.dialCols = it + 2 })
        switchRow("Show titles", null, { prefs.showTitles }, { prefs.showTitles = it })
        switchRow("Show thumbnails", "Page previews, captured as you browse", { Settings.thumbnails() }, { Settings.setThumbnails(it) })
        switchRow("Show Add button", null, { prefs.showAdd }, { prefs.showAdd = it })
        actionRow("Clear speed dial thumbnails", "They are captured again as you visit the sites") {
            Thumbs.clear(this); toast("Thumbnails cleared")
        }
    }

    private fun dataPage() {
        header("Sync")
        accountRows()
        textRow("Device name", "Shown to your other devices", { prefs.device }, { prefs.device = it }, "")
        actionRow("Test connection", "Checks the server address and key") {
            Toast.makeText(this, "Testing…", Toast.LENGTH_SHORT).show()
            Executors.newSingleThreadExecutor().execute {
                val r = Sync.test(applicationContext)
                ui.post { AlertDialog.Builder(this).setMessage(r).setPositiveButton("OK", null).show() }
            }
        }
        actionRow("Sync now", "Bookmarks, history and tabs") {
            Executors.newSingleThreadExecutor().execute {
                val r = try { Sync.run(applicationContext, emptyList()) } catch (e: Exception) { "Sync failed: ${e.message}" }
                ui.post { Toast.makeText(this, r, Toast.LENGTH_LONG).show() }
            }
        }
        switchRow("Sync automatically", "When the app opens and closes", { prefs.autoSync }, { prefs.autoSync = it })
        switchRow("Sync settings and speed dials", "Search engines, speed dials and other settings shared with your desktop browser", { prefs.syncSettings }, { prefs.syncSettings = it })
        switchRow("Sync open tabs", "Lets your other devices see this phone's tabs", { prefs.syncTabs }, { prefs.syncTabs = it })
    }

    private fun passwordsPage() {
        actionRow("Password manager", "Saved logins, add, edit and unlock") { startActivity(Intent(this, PasswordsActivity::class.java)) }
        choiceRow("Password manager in pages", listOf("This browser's and Android autofill (e.g. Bitwarden)", "This browser's only", "Android autofill only (e.g. Bitwarden)"),
            { listOf("both", "builtin", "system").indexOf(prefs.passwordMode).coerceAtLeast(0) },
            { prefs.passwordMode = listOf("both", "builtin", "system")[it]; toast("Applied to open tabs when you return to the browser") })
        switchRow("Fill saved logins automatically", "Fill the login by itself when a page opens. Off: tap a login field and pick one from the list that appears under it", { prefs.autoFillLogins }, { prefs.autoFillLogins = it })
        actionRow("Keep passwords unlocked", VaultPolicy.describe(prefs) + "\nHow long before you need to unlock them again") { VaultPolicy.show(this) { build() } }
        switchRow("Sync passwords", "The encrypted vault, unlocked with your master password", { prefs.syncVault }, { prefs.syncVault = it })
    }

    private fun bookmarksPage() {
        actionRow("Bookmarks", "Open your bookmarks") { startActivity(Intent(this, BookmarksActivity::class.java)) }
        switchRow("Sync bookmarks", "Keep them the same on your other devices", { prefs.syncBookmarks }, { prefs.syncBookmarks = it })
    }

    private fun historyPage() {
        actionRow("History", "Open your browsing history") { startActivity(Intent(this, HistoryActivity::class.java)) }
        switchRow("Sync history", "Keep it the same on your other devices", { prefs.syncHistory }, { prefs.syncHistory = it })
        actionRow("Clear history", "Removes all browsing history on this device") { confirm("Clear all browsing history on this device?") { Store.clearHistory(); toast("History cleared") } }
    }

    private fun privacyPage() {
        switchRow("Block ads and trackers", "Uses the EasyList, EasyPrivacy and uBlock filter lists", { prefs.blockTrackers }, { prefs.blockTrackers = it })
        actionRow("Filter lists", AdBlock.status + "\nTap to update now") {
            toast("Updating the filter lists…")
            Thread { val m = AdBlock.download(this); runOnUiThread { toast(m); build() } }.start()
        }
        header("Clear browsing data")
        actionRow("Clear cookies and site data", "Signs you out of websites") {
            confirm("Clear cookies and site data?") {
                CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData(); toast("Cleared")
            }
        }
        actionRow("Clear cache", null) { WebView(this).clearCache(true); toast("Cache cleared") }
    }

    private fun aboutPage() {
        actionRow("mbrowser for Android", "Build ${Updater.current(this)}. Syncs with your own mbrowser server.") { }
        actionRow("Source code on GitHub", "github.com/${prefs.updateRepo}") {
            try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/" + prefs.updateRepo))) }
            catch (e: Exception) { toast("No app can open the link") }
        }
        actionRow("Check for updates", "Looks for a newer build now") { Updater.checkAndPrompt(this, true) }
        switchRow("Check for updates automatically", "Notifies you when a new build is ready", { prefs.updateAuto }, { prefs.updateAuto = it })
        actionRow("Update source", prefs.updateRepo + (if (prefs.updateToken.isEmpty()) "" else "  ·  access token set")) {
            promptFields(this, "Update source", listOf("GitHub repository (owner/name)", "Access token (only for a private repository)"),
                listOf(prefs.updateRepo, prefs.updateToken)) { v -> prefs.updateRepo = v[0]; prefs.updateToken = v[1]; build() }
        }
    }

    private val wpPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            if (Wallpapers.saveCustom(this, uri)) { prefs.wallpaper = "custom"; toast("Wallpaper set") } else toast("Could not use that picture")
            build()
        }
    }

    private val loginLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) { build(); toast("Connected") }
    }

    private val avPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val data = try {
                val src = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                if (src == null) null else {
                    val side = minOf(src.width, src.height)
                    val sq = android.graphics.Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
                    val out = android.graphics.Bitmap.createScaledBitmap(sq, 256, 256, true)
                    val bos = java.io.ByteArrayOutputStream()
                    @Suppress("DEPRECATION") out.compress(android.graphics.Bitmap.CompressFormat.WEBP, 85, bos)
                    "data:image/webp;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
                }
            } catch (_: Exception) { null }
            if (data != null) saveProfileMeta(prefs.profileColor, data) else toast("Could not use that picture")
        }
    }

    /** The profile picture and colour travel with the settings, so the desktop browser shows the same ones. */
    private fun saveProfileMeta(color: String, avatar: String) {
        prefs.profileColor = color; prefs.profileAvatar = avatar
        Settings.set("profileMeta", org.json.JSONObject().put("color", color).put("avatar", avatar))
        toast("Profile picture saved. It reaches your other devices the next time you sync.")
        build()
    }

    private fun profilePictureRow() {
        val name = prefs.profileName.ifBlank { "Profile" }
        actionRow("Profile picture", "Photo, emoji or colour. Shown in the menu and on your desktop browser.") {
            val items = arrayOf("Choose a photo", "Pick an emoji", "Pick a colour", "Remove the picture")
            val colors = listOf("Blue" to "#3b82f6", "Green" to "#22c55e", "Red" to "#ef4444", "Orange" to "#f97316", "Purple" to "#a855f7", "Pink" to "#ec4899", "Teal" to "#14b8a6", "Grey" to "#6b7280")
            AlertDialog.Builder(this).setTitle("Profile picture · $name").setItems(items) { _, i ->
                when (i) {
                    0 -> avPicker.launch("image/*")
                    1 -> promptFields(this, "Emoji", listOf("One emoji, e.g. 🦊"), listOf(if (prefs.profileAvatar.startsWith("data:")) "" else prefs.profileAvatar), "Save") { v ->
                        if (v[0].isNotBlank()) saveProfileMeta(prefs.profileColor, v[0].trim().take(4)) }
                    2 -> AlertDialog.Builder(this).setTitle("Colour").setItems(colors.map { it.first }.toTypedArray()) { _, k -> saveProfileMeta(colors[k].second, prefs.profileAvatar) }.show()
                    3 -> saveProfileMeta(prefs.profileColor, "")
                }
            }.show()
        }
    }

    private fun cloudRows() {
        val name = Cloud.label(prefs.cloudBackend)
        when {
            !prefs.cloudSignedIn -> {
                if (prefs.cloudBackend == "gdrive" && Cloud.googleSecret(prefs).isEmpty())
                    textRow("Google client secret", "Paste it once. It stays on this phone.", { prefs.cloudGSecret }, { prefs.cloudGSecret = it }, "GOCSPX-…")
                actionRow("Sign in to $name", "Opens another browser app so you can allow access") {
                    Cloud.beginAuth(this, prefs.cloudBackend, { u -> startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(android.net.Uri.parse(u))) }) { err -> ui.post {
                        toast(err ?: "Signed in")
                        // the sign-in happened in a browser tab: come back here and ask for the passphrase straight away
                        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        build(); if (err == null) promptPassphrase()
                    } }
                    toast("Sign in on the page that opened, then come back")
                }
            }
            !prefs.cloudReady -> actionRow("Step 2: enter your sync passphrase", "Sync will not start until you do. Tap here") { promptPassphrase() }
            else -> actionRow("$name sync is on", "Everything is encrypted on this phone before it is uploaded") { }
        }
        if (prefs.cloudSignedIn) actionRow("Sign out of $name", "Stops syncing. Bookmarks and history stay on this phone.") {
            confirm("Sign out of $name?") { Cloud.disconnect(prefs); build() }
        }
    }

    private fun promptPassphrase() {
        val et = EditText(this)
        et.hint = "At least 8 characters"
        et.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        et.hint = "Sync passphrase (8+ characters)"
        AlertDialog.Builder(this).setTitle("One more step: sync passphrase")
            .setMessage("Everything is encrypted with this passphrase before it is uploaded.\n\nFirst device: make up a new passphrase.\nAlready syncing another device: type the same one you used there.\n\nWrite it down. It is never uploaded, so if you forget it your synced data cannot be recovered.")
            .setView(et)
            .setCancelable(false).setPositiveButton("Continue") { _, _ ->
                val pass = et.text.toString()
                toast("Checking…")
                Executors.newSingleThreadExecutor().execute {
                    val err = Cloud.setPassphrase(applicationContext, pass)
                    ui.post { toast(err ?: "Ready. Press Sync now."); build() }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun accountRows() {
        choiceRow("Sync with", listOf("My sync server", "Google Drive", "OneDrive"),
            { when (prefs.syncBackend) { "gdrive" -> 1; "onedrive" -> 2; else -> 0 } },
            { Cloud.selectBackend(prefs, listOf("server", "gdrive", "onedrive")[it]); build() })
        if (prefs.cloudBackend.isNotEmpty()) { cloudRows(); return }
        val who = when {
            !prefs.syncConfigured -> "Not connected"
            prefs.accountUser.isNotEmpty() -> "${prefs.accountUser} · profile “${prefs.profileName}” · ${prefs.server}"
            else -> "API key · ${prefs.server}"
        }
        actionRow(if (prefs.syncConfigured) "Account" else "Connect to a server", who) {
            loginLauncher.launch(android.content.Intent(this, ServerLoginActivity::class.java))
        }
        if (prefs.syncConfigured) {
            profilePictureRow()
            actionRow("Switch profile or account", "Sign in again to pick a different profile") {
                loginLauncher.launch(android.content.Intent(this, ServerLoginActivity::class.java))
            }
            actionRow("Use an API key instead", null) {
                loginLauncher.launch(android.content.Intent(this, ServerLoginActivity::class.java).putExtra("mode", "key"))
            }
            actionRow("Disconnect", "Stops syncing. Bookmarks and history stay on this phone.") {
                confirm("Disconnect from the server?") {
                    prefs.token = ""; prefs.accountUser = ""; prefs.profileName = ""; build()
                }
            }
        }
    }

    private fun searchRows() {
        val engines = Settings.engines()
        val names = engines.map { it.name }
        switchRow("Keep the engine I switch to", "Picking another engine under the address bar makes it the default. Off: it only applies to that search", { prefs.rememberEngine }, { prefs.rememberEngine = it })
        choiceRow("Search engine", names, { engines.indexOfFirst { it.id == Settings.defaultEngine().id }.coerceAtLeast(0) },
            { Settings.setDefaultEngine(engines[it].id) })
        actionRow("Add search engine", "Use %s where the search words go") {
            promptFields(this, "Add search engine", listOf("Name", "Address, e.g. https://example.com/?q=%s"), listOf("", ""), "Add") { v ->
                if (v[0].isNotEmpty() && v[1].contains("%s")) { Settings.addEngine(v[0], v[1]); build() }
                else toast("Enter a name and an address containing %s")
            }
        }
        if (engines.size > 1) actionRow("Remove a search engine", null) {
            AlertDialog.Builder(this).setTitle("Remove").setItems(names.toTypedArray()) { _, i ->
                Settings.removeEngine(engines[i].id); build()
            }.show()
        }
    }

    // ---------- search ----------
    private val HEADER = "#header"
    private fun applyFilter() {
        val q = query.trim().lowercase()
        var header: View? = null
        var shown = 0
        fun close() { header?.visibility = if (q.isEmpty() || shown > 0) View.VISIBLE else View.GONE }
        for (i in 0 until box.childCount) {
            val v = box.getChildAt(i)
            if (v.tag == HEADER) { close(); header = v; shown = 0; continue }
            val ok = q.isEmpty() || ((v.tag as? String) ?: "").contains(q)
            v.visibility = if (ok) View.VISIBLE else View.GONE
            if (ok) shown++
        }
        close()
    }

    // ---------- row helpers ----------
    private fun header(t: String) {
        val v = TextView(this)
        v.tag = HEADER
        v.text = t
        v.textSize = 13f
        v.typeface = Typeface.DEFAULT_BOLD
        v.setTextColor(tc(R.attr.mbAccent))
        v.setPadding(dp(20), dp(22), dp(16), dp(6))
        box.addView(v)
    }

    private fun rowView(title: String, sub: String?): Pair<LinearLayout, TextView> {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.VERTICAL
        r.setPadding(dp(20), dp(14), dp(16), dp(14))
        run { val tv = android.util.TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); r.setBackgroundResource(tv.resourceId) }
        r.tag = (title + " " + (sub ?: "")).lowercase()
        val t = TextView(this)
        t.text = title; t.textSize = 16f; t.setTextColor(tc(R.attr.mbText))
        r.addView(t)
        val s = TextView(this)
        s.textSize = 13f; s.setTextColor(tc(R.attr.mbMuted))
        s.text = sub ?: ""
        s.visibility = if (sub.isNullOrEmpty()) View.GONE else View.VISIBLE
        r.addView(s)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = 1
        box.addView(r, lp)
        return r to s
    }

    private fun actionRow(title: String, sub: String?, onClick: () -> Unit) {
        val (r, _) = rowView(title, sub)
        r.setOnClickListener { onClick() }
    }

    private fun textRow(title: String, help: String?, get: () -> String, set: (String) -> Unit, hint: String,
                        password: Boolean = false, blankInitial: Boolean = false) {
        val (r, s) = rowView(title, null)
        fun show() {
            val shown = get()
            s.text = if (shown.isEmpty()) (help ?: "Not set") else shown
            s.visibility = View.VISIBLE
        }
        show()
        r.setOnClickListener {
            promptFields(this, title, listOf(hint.ifEmpty { title }), listOf(if (blankInitial) "" else get()), "Save", password) {
                set(it[0]); show()
            }
        }
    }

    private fun switchRow(title: String, sub: String?, get: () -> Boolean, set: (Boolean) -> Unit): SwitchCompat {
        val (r, _) = rowView(title, sub)
        val sw = SwitchCompat(this)
        sw.isChecked = get()
        sw.setOnCheckedChangeListener { _, c -> set(c) }
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = android.view.Gravity.CENTER_VERTICAL
        // move the two text views into a column on the left
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        while (r.childCount > 0) { val c = r.getChildAt(0); r.removeViewAt(0); col.addView(c) }
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(sw)
        r.setOnClickListener { sw.toggle() }
        return sw
    }

    private fun choiceRow(title: String, options: List<String>, get: () -> Int, set: (Int) -> Unit) {
        val (r, s) = rowView(title, options.getOrElse(get()) { "" })
        r.setOnClickListener {
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), get()) { d, i ->
                    set(i); s.text = options[i]; d.dismiss()
                }.show()
        }
    }

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK") { _, _ -> ok() }.setNegativeButton("Cancel", null).show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
