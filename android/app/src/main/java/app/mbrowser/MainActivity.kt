package app.mbrowser

import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.ByteArrayInputStream
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private class Tab(val web: WebView) {
        var title = ""
        @Volatile var url = ""
        var home = true                 // true until something has been loaded: shows the speed dial
        var thumb: Bitmap? = null
        var icon: Bitmap? = null
        var desktop = false
    }

    private val MOBILE_FIX = "; wv"
    private val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private lateinit var prefs: Prefs
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var container: FrameLayout
    private lateinit var switcher: View
    private lateinit var switcherList: RecyclerView
    private lateinit var switcherTitle: TextView
    private lateinit var startPage: StartPage
    private lateinit var siteIcon: ImageView
    private lateinit var btnStar: ImageButton
    private lateinit var tabCount: TextView
    private lateinit var btnReload: ImageButton

    private val tabs = mutableListOf<Tab>()
    private var current = -1
    private val ui = Handler(Looper.getMainLooper())

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra("url")?.let { newTab(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        Store.init(this)

        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        container = findViewById(R.id.container)
        switcher = findViewById(R.id.switcher)
        switcherList = findViewById(R.id.switcherList)
        switcherTitle = findViewById(R.id.switcherTitle)
        siteIcon = findViewById(R.id.siteIcon)
        btnStar = findViewById(R.id.btnStar)
        tabCount = findViewById(R.id.tabCount)
        btnReload = findViewById(R.id.btnReload)

        val startRoot = LayoutInflater.from(this).inflate(R.layout.start_page, container, false)
        startPage = StartPage(this, startRoot,
            open = { url, inNew -> if (inNew) newTab(url) else navigate(url) },
            openSettings = { startActivity(Intent(this, SettingsActivity::class.java)) })

        urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                navigate(urlBar.text.toString()); true
            } else false
        }
        btnReload.setOnClickListener {
            val t = cur()
            if (t != null && !t.home) { if (progress.visibility == View.VISIBLE) t.web.stopLoading() else t.web.reload() }
        }
        findViewById<View>(R.id.btnBack).setOnClickListener { goBack() }
        findViewById<View>(R.id.btnForward).setOnClickListener { cur()?.let { if (it.web.canGoForward()) it.web.goForward() } }
        btnStar.setOnClickListener { toggleBookmark() }
        findViewById<View>(R.id.btnTabs).setOnClickListener { if (switcher.visibility == View.VISIBLE) hideSwitcher() else showSwitcher() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }

        findViewById<View>(R.id.switcherNew).setOnClickListener { hideSwitcher(); newTab(null) }
        findViewById<View>(R.id.switcherCloseAll).setOnClickListener {
            while (tabs.size > 1) { tabs.removeAt(tabs.size - 1).web.destroy() }
            closeTab(0); switchTo(0); hideSwitcher()
        }
        findViewById<View>(R.id.switcherDevices).setOnClickListener {
            hideSwitcher()
            picker.launch(Intent(this, ItemsActivity::class.java).putExtra("mode", "devices"))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val t = cur()
                when {
                    switcher.visibility == View.VISIBLE -> hideSwitcher()
                    t != null && !t.home && t.web.canGoBack() -> t.web.goBack()
                    t != null && !t.home -> { t.home = true; t.url = ""; t.title = ""; render() }
                    tabs.size > 1 -> closeTab(current)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        newTab(null)
        handleIntent(intent)
        if (!prefs.firstRunDone) {
            prefs.firstRunDone = true
            if (!prefs.syncConfigured) startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val data = i?.data
        if (i?.action == Intent.ACTION_VIEW && data != null) newTab(data.toString())
    }

    override fun onStart() {
        super.onStart()
        applyWebSettings()
        render()
        if (prefs.autoSync) syncNow(false)
    }

    override fun onStop() {
        super.onStop()
        if (prefs.autoSync) syncNow(false)
    }

    override fun onDestroy() {
        tabs.forEach { it.web.destroy() }
        super.onDestroy()
    }

    // ---------- tabs ----------
    private fun cur(): Tab? = tabs.getOrNull(current)

    private fun applyWebSettings() {
        for (t in tabs) {
            t.web.settings.textZoom = prefs.textZoom
        }
    }

    private fun applyUa(tab: Tab) {
        val s = tab.web.settings
        if (tab.desktop) {
            s.userAgentString = DESKTOP_UA
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
        } else {
            // drop the "; wv" marker so sites (Google sign-in in particular) are less likely to treat this as an embedded view
            s.userAgentString = WebSettingsDefault.mobile(this).replace(MOBILE_FIX, "")
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
        }
    }

    private fun newTab(url: String?): Tab {
        val w = WebView(this)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.setSupportMultipleWindows(false)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.textZoom = prefs.textZoom
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        val tab = Tab(w)
        tab.desktop = prefs.desktopSite
        applyUa(tab)

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme ?: return false
                if (scheme == "http" || scheme == "https") return false
                return try { startActivity(Intent(Intent.ACTION_VIEW, u)); true } catch (_: Exception) { true }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (prefs.blockTrackers && !request.isForMainFrame &&
                    Blocker.shouldBlock(request.url.host, Thumbs.hostOf(tab.url))) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url.startsWith("about:")) return
                tab.url = url; tab.home = false; tab.icon = favicon ?: tab.icon
                if (tab === cur()) { urlBar.setText(url); progress.visibility = View.VISIBLE; updateChrome() }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith("about:")) return
                tab.url = url
                tab.title = view.title ?: ""
                Store.addVisit(url, tab.title)
                if (tab === cur()) { progress.visibility = View.INVISIBLE; updateChrome() }
                ui.postDelayed({ afterLoad(tab, url) }, 1500)
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (tab === cur()) progress.progress = newProgress
            }
            override fun onReceivedTitle(view: WebView, title: String?) { tab.title = title ?: "" }
            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                if (icon == null) return
                tab.icon = icon
                Thumbs.saveIcon(applicationContext, Thumbs.hostOf(tab.url), icon)
                if (tab === cur()) updateChrome()
            }
        }
        w.setDownloadListener { dl, ua, cd, mime, _ ->
            try {
                val req = DownloadManager.Request(Uri.parse(dl))
                req.setMimeType(mime)
                req.addRequestHeader("User-Agent", ua)
                CookieManager.getInstance().getCookie(dl)?.let { req.addRequestHeader("Cookie", it) }
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(dl, cd, mime))
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, "Downloading…", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        tabs.add(tab)
        switchTo(tabs.size - 1)
        if (url != null) navigate(url)
        return tab
    }

    /** Runs a moment after a page loads: keeps a tab preview and refreshes the speed dial thumbnail for known sites. */
    private fun afterLoad(tab: Tab, url: String) {
        if (tab !== cur() || tab.home || tab.url != url || switcher.visibility == View.VISIBLE) return
        val shot = capture(tab, 360) ?: return
        tab.thumb = shot
        val host = Thumbs.hostOf(url)
        val known = Store.bookmarkList().any { Thumbs.hostOf(it.url) == host }
        val root = Uri.parse(url).path.let { it.isNullOrEmpty() || it == "/" }
        if (known && (root || Thumbs.ageMs(this, host) == Long.MAX_VALUE) && Thumbs.ageMs(this, host) > 6 * 3600_000L) {
            Thumbs.saveThumb(this, host, shot)
        }
    }

    private fun capture(tab: Tab, outW: Int): Bitmap? {
        val w = tab.web.width
        val h = tab.web.height
        if (w <= 0 || h <= 0) return null
        val ch = minOf(h, (w * 0.62f).toInt())
        return try {
            val full = Bitmap.createBitmap(w, ch, Bitmap.Config.ARGB_8888)
            tab.web.draw(Canvas(full))
            val out = Bitmap.createScaledBitmap(full, outW, (outW * 0.62f).toInt(), true)
            if (out !== full) full.recycle()
            out
        } catch (_: Throwable) { null }
    }

    private fun switchTo(i: Int) {
        current = i.coerceIn(0, tabs.size - 1)
        render()
    }

    private fun closeTab(i: Int) {
        if (tabs.size <= 1) {
            val t = tabs[0]
            t.home = true; t.url = ""; t.title = ""; t.thumb = null; t.icon = null
            t.web.loadUrl("about:blank"); t.web.clearHistory(); render(); return
        }
        val t = tabs.removeAt(i)
        t.web.destroy()
        switchTo(if (current >= tabs.size) tabs.size - 1 else if (i < current) current - 1 else current)
    }

    private fun render() {
        container.removeAllViews()
        val t = cur() ?: return
        if (t.home) {
            (startPage.root.parent as? ViewGroup)?.removeView(startPage.root)
            container.addView(startPage.root, FrameLayout.LayoutParams(-1, -1))
            urlBar.setText("")
            progress.visibility = View.INVISIBLE
            startPage.refresh()
        } else {
            (t.web.parent as? ViewGroup)?.removeView(t.web)
            container.addView(t.web, FrameLayout.LayoutParams(-1, -1))
            urlBar.setText(t.url)
        }
        updateChrome()
    }

    private fun updateChrome() {
        val t = cur()
        tabCount.text = tabs.size.toString()
        val starred = t != null && !t.home && Store.isBookmarked(t.url)
        btnStar.setImageResource(if (starred) R.drawable.ic_star_filled else R.drawable.ic_star)
        btnStar.imageTintList = getColorStateList(if (starred) R.color.accent else R.color.text)
        if (t == null || t.home) {
            siteIcon.setImageResource(R.drawable.ic_search); siteIcon.imageTintList = getColorStateList(R.color.muted)
        } else {
            val ic = t.icon ?: Thumbs.icon(this, Thumbs.hostOf(t.url))
            if (ic != null) { siteIcon.setImageBitmap(ic); siteIcon.imageTintList = null }
            else { siteIcon.setImageResource(R.drawable.ic_globe); siteIcon.imageTintList = getColorStateList(R.color.muted) }
        }
    }

    // ---------- tab switcher ----------
    private fun showSwitcher() {
        cur()?.let { if (!it.home) capture(it, 360)?.let { b -> it.thumb = b } }
        switcher.visibility = View.VISIBLE
        refreshSwitcher()
    }

    private fun hideSwitcher() { switcher.visibility = View.GONE }

    private fun refreshSwitcher() {
        switcherTitle.text = if (tabs.size == 1) "1 tab" else "${tabs.size} tabs"
        switcherList.layoutManager = GridLayoutManager(this, 2)
        switcherList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = tabs.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(LayoutInflater.from(this@MainActivity).inflate(R.layout.item_tab, parent, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val t = tabs[pos]
                val v = h.itemView
                val label = if (t.home) "Speed Dial" else t.title.ifBlank { t.url }
                v.findViewById<TextView>(R.id.title).text = label
                val thumb = v.findViewById<ImageView>(R.id.thumb)
                val letter = v.findViewById<TextView>(R.id.letter)
                val icon = v.findViewById<ImageView>(R.id.icon)
                val shot = t.thumb
                if (shot != null && !t.home) {
                    thumb.setImageBitmap(shot); thumb.visibility = View.VISIBLE; letter.visibility = View.GONE
                } else {
                    thumb.visibility = View.GONE; letter.visibility = View.VISIBLE
                    letter.text = if (t.home) "⌂" else (label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '•').toString()
                    val g = GradientDrawable()
                    g.cornerRadius = dp(12).toFloat()
                    g.setColor(Color.HSVToColor(floatArrayOf(Math.floorMod(label.hashCode(), 360).toFloat(), 0.4f, 0.45f)))
                    letter.background = g
                }
                val ic = t.icon
                if (ic != null) { icon.setImageBitmap(ic); icon.imageTintList = null }
                else { icon.setImageResource(R.drawable.ic_globe); icon.imageTintList = getColorStateList(R.color.muted) }
                v.setOnClickListener { hideSwitcher(); switchTo(pos) }
                v.findViewById<View>(R.id.close).setOnClickListener {
                    closeTab(pos); refreshSwitcher()
                }
            }
        }
    }

    // ---------- navigation ----------
    private fun toUrl(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return ""
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s)) return s
        if (!s.contains(' ') && (s.contains('.') || s.startsWith("localhost"))) return "https://$s"
        return prefs.searchUrl + URLEncoder.encode(s, "UTF-8")
    }

    private fun navigate(input: String) {
        val url = toUrl(input)
        if (url.isEmpty()) return
        val t = cur() ?: return
        t.home = false
        t.url = url
        render()
        t.web.loadUrl(url)
        urlBar.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun goBack() {
        val t = cur() ?: return
        if (!t.home && t.web.canGoBack()) t.web.goBack()
    }

    // ---------- bookmarks / menu ----------
    private fun toggleBookmark() {
        val t = cur() ?: return
        if (t.home || t.url.isEmpty()) return
        val on = Store.toggleBookmark(t.url, t.title)
        Toast.makeText(this, if (on) "Added to ${prefs.defaultDialName}" else "Removed", Toast.LENGTH_SHORT).show()
        if (on) tab_thumbFor(t)
        updateChrome()
    }

    private fun tab_thumbFor(t: Tab) {
        val b = capture(t, 360) ?: return
        Thumbs.saveThumb(this, Thumbs.hostOf(t.url), b)
    }

    private fun showMenu(anchor: View) {
        val t = cur()
        val m = PopupMenu(this, anchor)
        val items = listOf(
            "New tab", "Bookmarks", "History", "Tabs from other devices", "Sync now",
            (if (t?.desktop == true) "✓ " else "") + "Desktop site", "Share page", "Settings",
        )
        items.forEachIndexed { i, s -> m.menu.add(0, i, i, s) }
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> newTab(null)
                1 -> picker.launch(Intent(this, ItemsActivity::class.java).putExtra("mode", "bookmarks"))
                2 -> picker.launch(Intent(this, ItemsActivity::class.java).putExtra("mode", "history"))
                3 -> picker.launch(Intent(this, ItemsActivity::class.java).putExtra("mode", "devices"))
                4 -> syncNow(true)
                5 -> if (t != null) { t.desktop = !t.desktop; applyUa(t); if (!t.home) t.web.reload() }
                6 -> if (t != null && !t.home) startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t.url), "Share"))
                7 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        m.show()
    }

    private fun syncNow(verbose: Boolean) {
        if (!prefs.syncConfigured) {
            if (verbose) Toast.makeText(this, "Set up a sync server in Settings first", Toast.LENGTH_LONG).show()
            return
        }
        val open = tabs.filter { !it.home }.map { it.url to it.title }
        Sync.runAsync(this, { open }) { msg ->
            ui.post {
                if (verbose || msg != "Synced") Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (cur()?.home == true) startPage.refresh()
                updateChrome()
            }
        }
    }
}

/** The WebView's stock user agent, read once without needing a live WebView on the UI path. */
object WebSettingsDefault {
    private var ua: String? = null
    fun mobile(ctx: android.content.Context): String =
        ua ?: android.webkit.WebSettings.getDefaultUserAgent(ctx).also { ua = it }
}
