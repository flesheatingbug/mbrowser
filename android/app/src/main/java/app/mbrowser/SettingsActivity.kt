package app.mbrowser

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
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

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var box: LinearLayout
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Store.init(this)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(getColor(R.color.bg))
        scroll.fitsSystemWindows = true
        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, 0, 0, dp(32))
        scroll.addView(box, ViewGroup.LayoutParams(-1, -2))
        setContentView(scroll)
        build()
    }

    private fun build() {
        box.removeAllViews()
        val top = TextView(this)
        top.text = "Settings"
        top.textSize = 22f
        top.typeface = Typeface.DEFAULT_BOLD
        top.setTextColor(getColor(R.color.text))
        top.setBackgroundColor(getColor(R.color.bar))
        top.setPadding(dp(16), dp(18), dp(16), dp(18))
        box.addView(top)

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
        switchRow("Sync bookmarks", null, { prefs.syncBookmarks }, { prefs.syncBookmarks = it })
        switchRow("Sync passwords", "The encrypted vault, unlocked with your master password", { prefs.syncVault }, { prefs.syncVault = it })
        switchRow("Sync settings and speed dials", "Search engines, speed dials and other settings shared with your desktop browser", { prefs.syncSettings }, { prefs.syncSettings = it })
        switchRow("Sync history", null, { prefs.syncHistory }, { prefs.syncHistory = it })
        switchRow("Sync open tabs", "Lets your other devices see this phone's tabs", { prefs.syncTabs }, { prefs.syncTabs = it })

        header("Search")
        searchRows()

        header("Appearance")
        choiceRow("Theme", listOf("Follow system", "Light", "Dark"), { prefs.themeMode }, { prefs.themeMode = it; App.applyTheme(it) })

        header("Speed dial")
        choiceRow("Columns", listOf("2", "3", "4", "5"), { (prefs.dialCols - 2).coerceIn(0, 3) }, { prefs.dialCols = it + 2 })
        switchRow("Show titles", null, { prefs.showTitles }, { prefs.showTitles = it })
        switchRow("Show thumbnails", "Page previews, captured as you browse", { Settings.thumbnails() }, { Settings.setThumbnails(it) })
        switchRow("Show Add button", null, { prefs.showAdd }, { prefs.showAdd = it })

        header("Browsing")
        switchRow("Request desktop sites", "For new tabs", { prefs.desktopSite }, { prefs.desktopSite = it })
        choiceRow("Text size", listOf("Small (80%)", "Normal (100%)", "Large (120%)", "Extra large (150%)"),
            { listOf(80, 100, 120, 150).indexOf(prefs.textZoom).coerceAtLeast(1) },
            { prefs.textZoom = listOf(80, 100, 120, 150)[it] })
        switchRow("Pull down to refresh", "Off if it gets in the way of pages with their own drag gestures", { prefs.pullRefresh }, { prefs.pullRefresh = it })
        switchRow("Block trackers and ads", "Drops requests to a built-in list of tracker domains", { prefs.blockTrackers }, { prefs.blockTrackers = it })

        header("Privacy and data")
        actionRow("Clear history", null) { confirm("Clear all browsing history on this device?") { Store.clearHistory(); toast("History cleared") } }
        actionRow("Clear cookies and site data", "Signs you out of websites") {
            confirm("Clear cookies and site data?") {
                CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData(); toast("Cleared")
            }
        }
        actionRow("Clear cache", null) { WebView(this).clearCache(true); toast("Cache cleared") }
        actionRow("Clear speed dial thumbnails", "They are captured again as you visit the sites") {
            Thumbs.clear(this); toast("Thumbnails cleared")
        }

        header("About")
        actionRow("mbrowser for Android", "Build ${Updater.current(this)}. Syncs with your own mbrowser server.") { }
        actionRow("Check for updates", "Looks for a newer build now") { Updater.checkAndPrompt(this, true) }
        switchRow("Check for updates automatically", "Notifies you when a new build is ready", { prefs.updateAuto }, { prefs.updateAuto = it })
        actionRow("Update source", prefs.updateRepo + (if (prefs.updateToken.isEmpty()) "" else "  ·  access token set")) {
            promptFields(this, "Update source", listOf("GitHub repository (owner/name)", "Access token (only for a private repository)"),
                listOf(prefs.updateRepo, prefs.updateToken)) { v -> prefs.updateRepo = v[0]; prefs.updateToken = v[1]; build() }
        }
    }

    private val loginLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) { build(); toast("Connected") }
    }

    private fun accountRows() {
        val who = when {
            !prefs.syncConfigured -> "Not connected"
            prefs.accountUser.isNotEmpty() -> "${prefs.accountUser} · profile “${prefs.profileName}” · ${prefs.server}"
            else -> "API key · ${prefs.server}"
        }
        actionRow(if (prefs.syncConfigured) "Account" else "Connect to a server", who) {
            loginLauncher.launch(android.content.Intent(this, ServerLoginActivity::class.java))
        }
        if (prefs.syncConfigured) {
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

    // ---------- row helpers ----------
    private fun header(t: String) {
        val v = TextView(this)
        v.text = t
        v.textSize = 13f
        v.typeface = Typeface.DEFAULT_BOLD
        v.setTextColor(getColor(R.color.accent))
        v.setPadding(dp(16), dp(22), dp(16), dp(6))
        box.addView(v)
    }

    private fun rowView(title: String, sub: String?): Pair<LinearLayout, TextView> {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.VERTICAL
        r.setPadding(dp(16), dp(12), dp(16), dp(12))
        r.setBackgroundColor(getColor(R.color.card))
        val t = TextView(this)
        t.text = title; t.textSize = 16f; t.setTextColor(getColor(R.color.text))
        r.addView(t)
        val s = TextView(this)
        s.textSize = 13f; s.setTextColor(getColor(R.color.muted))
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

    private fun switchRow(title: String, sub: String?, get: () -> Boolean, set: (Boolean) -> Unit) {
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
