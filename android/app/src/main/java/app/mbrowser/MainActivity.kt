package app.mbrowser

import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.LinearLayout
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private class Tab(val web: WebView) {
        var title = ""
        @Volatile var url = ""
        var pending: String? = null     // restored tab: loads when first shown
        var home = true                 // true until something has been loaded: shows the speed dial
        var thumb: Bitmap? = null
        var icon: Bitmap? = null
        var desktop = false
        var priv = false                // private tab: no history, thumbnails or syncing
        var isolated = false            // private tab with its own separate cookies and storage
    }

    private val PRIVATE_PROFILE = "private"
    private val ACTION_CLOSE_PRIVATE = "app.mbrowser.CLOSE_PRIVATE"
    private val NOTICE_ID = 42
    private val MOBILE_FIX = "; wv"
    private val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private lateinit var prefs: Prefs
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var container: FrameLayout
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var switcher: View
    private lateinit var switcherList: RecyclerView
    private lateinit var switcherTitle: TextView
    private lateinit var startPage: StartPage
    private lateinit var siteIcon: ImageView
    private lateinit var btnStar: ImageButton
    private lateinit var tabCount: TextView
    private lateinit var btnReload: ImageButton

    private var engineOverride: Engine? = null
    private var switcherMode = 0          // 0 tabs, 1 private, 2 other devices, 3 recently closed
    private var switcherQuery = ""
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
        swipe = findViewById(R.id.swipe)
        swipe.setColorSchemeResources(R.color.accent)
        swipe.setProgressBackgroundColorSchemeResource(R.color.card)
        // only a web page that is scrolled to its top can be pulled down; the speed dial never refreshes
        swipe.setOnChildScrollUpCallback { _, _ -> val t = cur(); t == null || t.home || t.web.scrollY > 0 }
        swipe.setOnRefreshListener {
            val t = cur()
            if (t != null && !t.home) t.web.reload() else swipe.isRefreshing = false
        }
        findViewById<View>(R.id.appLogo).setOnClickListener { showMenu(it) }
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
        urlBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: Editable?) { if (urlBar.hasFocus()) showSuggestions(e?.toString() ?: "") }
        })
        urlBar.setOnFocusChangeListener { _, focused ->
            if (focused) showEngines() else { engineOverride = null; findViewById<View>(R.id.engineStrip).visibility = View.GONE; findViewById<View>(R.id.suggestScroll).visibility = View.GONE }
        }
        btnReload.setOnClickListener {
            val t = cur()
            if (t != null && !t.home) { if (progress.visibility == View.VISIBLE) t.web.stopLoading() else t.web.reload() }
        }
        findViewById<View>(R.id.btnBack).setOnClickListener { goBack() }
        findViewById<View>(R.id.btnHome).setOnClickListener { goHome() }
        findViewById<View>(R.id.btnDownloads).setOnClickListener {
            startActivity(Intent(this, ItemsActivity::class.java).putExtra("mode", "downloads"))
        }
        findViewById<View>(R.id.btnForward).setOnClickListener { cur()?.let { if (it.web.canGoForward()) it.web.goForward() } }
        btnStar.setOnClickListener { toggleBookmark() }
        findViewById<View>(R.id.btnTabs).setOnClickListener { if (switcher.visibility == View.VISIBLE) hideSwitcher() else showSwitcher() }
        findViewById<View>(R.id.btnTabs).setOnLongClickListener { v ->
            val m = PopupMenu(this, v)
            m.menu.add(0, 1, 0, "New tab"); m.menu.add(0, 2, 1, "New private tab"); m.menu.add(0, 3, 2, "Close this tab")
            m.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> { hideSwitcher(); newTab(null) }
                    2 -> { hideSwitcher(); newTab(null, true) }
                    3 -> closeTab(current)
                }
                true
            }
            m.show(); true
        }

        findViewById<View>(R.id.switcherNew).setOnClickListener { hideSwitcher(); newTab(null, switcherMode == 1) }
        findViewById<View>(R.id.switcherClose).setOnClickListener { hideSwitcher() }
        findViewById<View>(R.id.modeTabs).setOnClickListener { switcherMode = 0; refreshSwitcher() }
        findViewById<View>(R.id.modePrivate).setOnClickListener { switcherMode = 1; refreshSwitcher() }
        findViewById<View>(R.id.modeSynced).setOnClickListener { switcherMode = 2; refreshSwitcher() }
        findViewById<View>(R.id.modeClosed).setOnClickListener { switcherMode = 3; refreshSwitcher() }
        val filter = findViewById<EditText>(R.id.switcherFilter)
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: Editable?) { switcherQuery = e?.toString()?.trim()?.lowercase() ?: ""; refreshSwitcher() }
        })
        findViewById<View>(R.id.switcherSearch).setOnClickListener {
            if (filter.visibility == View.VISIBLE) { filter.setText(""); filter.visibility = View.GONE }
            else { filter.visibility = View.VISIBLE; filter.requestFocus() }
        }
        findViewById<View>(R.id.switcherMore).setOnClickListener { v ->
            val m = PopupMenu(this, v)
            m.menu.add(0, 1, 0, "Close all tabs"); m.menu.add(0, 2, 1, "Close private tabs"); m.menu.add(0, 3, 2, "Clear recently closed")
            m.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> {
                        tabs.filter { t -> !t.priv }.forEach { t -> rememberClosed(t) }
                        val keepPriv = tabs.filter { t -> t.priv }
                        tabs.filter { t -> !t.priv }.forEach { t -> t.web.destroy() }
                        tabs.removeAll { t -> !t.priv }
                        newTab(null); tabs.removeAll(keepPriv); tabs.addAll(keepPriv)
                        switchTo(0)
                    }
                    2 -> closePrivateTabs()
                    3 -> sessionPrefs().edit().remove("closed").apply()
                }
                refreshSwitcher(); true
            }
            m.show()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val t = cur()
                when {
                    switcher.visibility == View.VISIBLE -> hideSwitcher()
                    findViewById<View>(R.id.findBar).visibility == View.VISIBLE -> closeFind()
                    t != null && t.home && startPage.up() -> Unit
                    t != null && !t.home && t.web.canGoBack() -> t.web.goBack()
                    t != null && !t.home -> { t.home = true; t.url = ""; t.title = ""; render() }
                    tabs.size > 1 -> closeTab(current)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        cleanupPrivate()   // drop private storage left over if the app was killed with private tabs open
        if (!restoreTabs()) newTab(null)
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 && prefs.firstRunDone && !sessionPrefs().getBoolean("askedNotif", false) &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            sessionPrefs().edit().putBoolean("askedNotif", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 8)
        }
        if (!prefs.firstRunDone) {
            prefs.firstRunDone = true
            startActivity(Intent(this, WelcomeActivity::class.java))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val data = i?.data
        if (i?.action == Intent.ACTION_VIEW && data != null) newTab(data.toString())
        if (i?.action == ACTION_CLOSE_PRIVATE) closePrivateTabs()
        if (i?.action == Updater.ACTION_UPDATE) Updater.checkAndPrompt(this, true)
    }

    // ---------- search engine strip under the address bar ----------
    private fun showEngines() {
        val strip = findViewById<View>(R.id.engineStrip)
        val row = findViewById<LinearLayout>(R.id.engineRow)
        row.removeAllViews()
        val defId = Settings.defaultEngine().id
        val engines = Settings.engines().sortedBy { if (it.id == defId) 0 else 1 }
        row.isBaselineAligned = false
        if (engines.size < 2) { strip.visibility = View.GONE; return }
        val chosen = engineOverride ?: Settings.defaultEngine()
        val d = resources.displayMetrics.density
        for (e in engines) {
            val tv = TextView(this)
            val host = Thumbs.hostOf(e.url.replace("%s", "x"))
            val ic = Thumbs.icon(this, host)
            if (ic != null) {
                val dr = android.graphics.drawable.BitmapDrawable(resources, ic)
                tv.setCompoundDrawablesWithIntrinsicBounds(null, dr, null, null)
                tv.setPadding(0, (12 * d).toInt(), 0, 0)
                dr.setBounds(0, 0, (24 * d).toInt(), (24 * d).toInt())
                tv.setCompoundDrawables(null, dr, null, null)
            } else {
                tv.text = e.name.take(1).uppercase()
                fetchEngineIcon(host)
            }
            tv.gravity = Gravity.CENTER
            tv.textSize = 18f
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.setTextColor(getColor(R.color.text))
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(getColor(R.color.card))
            if (e.id == chosen.id) bg.setStroke((3 * d).toInt(), getColor(R.color.accent))
            tv.background = bg
            tv.contentDescription = "Search with ${e.name}"
            val lp = LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt())
            lp.marginEnd = (10 * d).toInt()
            tv.setOnClickListener {
                engineOverride = e
                urlBar.hint = "Search with ${e.name}"
                showEngines()
                val text = urlBar.text.toString().trim()
                if (text.isNotEmpty() && !text.contains("://") && (text.contains(' ') || !text.contains('.'))) navigate(text)
            }
            row.addView(tv, lp)
        }
        strip.visibility = View.VISIBLE
    }

    private val iconFetching = HashSet<String>()
    /** Downloads an engine's own favicon once (straight from its site) and redraws the strip. */
    private fun fetchEngineIcon(host: String) {
        if (host.isEmpty() || !iconFetching.add(host)) return
        Thread {
            var bmp: Bitmap? = null
            for (path in listOf("/apple-touch-icon.png", "/favicon.ico")) {
                try {
                    val c = java.net.URL("https://$host$path").openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 5000; c.readTimeout = 5000; c.instanceFollowRedirects = true
                    c.setRequestProperty("User-Agent", "Mozilla/5.0")
                    if (c.responseCode in 200..299) bmp = android.graphics.BitmapFactory.decodeStream(c.inputStream)
                    c.disconnect()
                } catch (_: Exception) { }
                if (bmp != null) break
            }
            val b = bmp
            if (b != null) { Thumbs.saveIcon(applicationContext, host, b); ui.post { if (urlBar.hasFocus()) showEngines() } }
        }.start()
    }

    // ---------- predictive suggestions from bookmarks and history ----------
    private fun showSuggestions(q0: String) {
        val scroll = findViewById<View>(R.id.suggestScroll)
        val list = findViewById<LinearLayout>(R.id.suggestList)
        val q = q0.trim().lowercase()
        if (q.isEmpty() || cur()?.priv == true) { scroll.visibility = View.GONE; return }
        class Hit(val url: String, val title: String, var score: Int, val star: Boolean)
        val hits = LinkedHashMap<String, Hit>()
        fun bare(u: String) = u.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        fun consider(url: String, title: String, star: Boolean, recency: Int) {
            val b = bare(url).lowercase(); val t = title.lowercase()
            val score = when {
                b.startsWith(q) -> 100
                b.contains(q) -> 60
                t.startsWith(q) -> 50
                t.contains(q) -> 40
                else -> return
            } + (if (star) 25 else 0) + recency
            val key = bare(url).trimEnd('/')
            val h = hits[key]
            if (h == null) hits[key] = Hit(url, title, score, star) else h.score += 4   // visited again: more likely
        }
        Store.bookmarkList().filter { !it.deleted }.forEach { consider(it.url, it.title, true, 0) }
        Store.historyList().take(3000).forEachIndexed { i, v -> consider(v.url, v.title, false, if (i < 100) 10 else 0) }
        val top = hits.values.sortedByDescending { it.score }.take(6)
        list.removeAllViews()
        if (top.isEmpty()) { scroll.visibility = View.GONE; return }
        val d = resources.displayMetrics.density
        for (h in top) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            row.setBackgroundResource(android.R.drawable.list_selector_background)
            val t1 = TextView(this); t1.text = (if (h.star) "★ " else "") + h.title.ifBlank { bare(h.url) }
            t1.setTextColor(getColor(R.color.text)); t1.textSize = 16f; t1.maxLines = 1; t1.ellipsize = android.text.TextUtils.TruncateAt.END
            val t2 = TextView(this); t2.text = bare(h.url)
            t2.setTextColor(getColor(R.color.muted)); t2.textSize = 13f; t2.maxLines = 1; t2.ellipsize = android.text.TextUtils.TruncateAt.END
            row.addView(t1); row.addView(t2)
            row.setOnClickListener { navigate(h.url) }
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        scroll.visibility = View.VISIBLE
    }

    // ---------- private tab notification ----------
    private fun updatePrivateNotice() {
        val n = tabs.count { it.priv }
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (n == 0) { nm.cancel(NOTICE_ID); return }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7); return
        }
        nm.createNotificationChannel(android.app.NotificationChannel("private", "Private tabs", android.app.NotificationManager.IMPORTANCE_LOW))
        val open = android.app.PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val close = android.app.PendingIntent.getActivity(this, 1,
            Intent(this, MainActivity::class.java).setAction(ACTION_CLOSE_PRIVATE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val b = android.app.Notification.Builder(this, "private")
            .setSmallIcon(R.drawable.ic_stat_m)
            .setContentTitle(if (n == 1) "1 private tab open" else "$n private tabs open")
            .setContentText("Tap to return, or close them all")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.app.Notification.Action.Builder(null, "Close private tabs", close).build())
        nm.notify(NOTICE_ID, b.build())
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) updatePrivateNotice()
    }

    private fun closePrivateTabs() {
        if (tabs.none { it.priv }) return
        val wasHome = cur()?.priv == true
        tabs.filter { it.priv }.forEach { it.web.destroy() }
        tabs.removeAll { it.priv }
        if (tabs.isEmpty()) newTab(null)
        if (current >= tabs.size || wasHome) current = tabs.size - 1
        hideSwitcher(); render(); cleanupPrivate()
    }



    override fun onStart() {
        super.onStart()
        applyWebSettings()
        render()
        if (prefs.autoSync) syncNow(false)
        if (prefs.updateAuto && prefs.firstRunDone && System.currentTimeMillis() - prefs.updateLast > 6 * 3600_000L) Updater.checkAndPrompt(this, false)
    }

    // ---------- remember open tabs across restarts and updates ----------
    private fun sessionPrefs() = getSharedPreferences("session", MODE_PRIVATE)

    private fun saveTabs() {
        if (tabs.isEmpty()) return
        val a = org.json.JSONArray()
        var idx = 0
        tabs.forEachIndexed { i, t ->
            if (t.priv) return@forEachIndexed
            if (i == current) idx = a.length()
            a.put(org.json.JSONObject().put("url", if (t.home) "" else t.url).put("title", t.title).put("desktop", t.desktop))
        }
        sessionPrefs().edit().putString("tabs", a.toString()).putInt("current", idx).apply()
    }

    private fun restoreTabs(): Boolean {
        val raw = sessionPrefs().getString("tabs", null) ?: return false
        val a = try { org.json.JSONArray(raw) } catch (_: Exception) { return false }
        if (a.length() == 0) return false
        for (i in 0 until minOf(a.length(), 40)) {
            val o = a.optJSONObject(i) ?: continue
            val t = newTab(null)
            val u = o.optString("url")
            if (u.isNotEmpty()) { t.home = false; t.url = u; t.title = o.optString("title"); t.pending = u }
        }
        switchTo(sessionPrefs().getInt("current", 0))
        return true
    }

    override fun onPause() {
        saveTabs()
        super.onPause()
    }

    override fun onStop() {
        saveTabs()
        super.onStop()
        if (prefs.autoSync) syncNow(false)
    }

    override fun onDestroy() {
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(NOTICE_ID)
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

    private fun newTab(url: String?, priv: Boolean = false): Tab {
        val w = WebView(this)
        var isolated = false
        if (priv) {
            // a separate cookie jar and storage for private tabs, where this device's WebView supports it
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    WebViewCompat.setProfile(w, PRIVATE_PROFILE); isolated = true
                }
            } catch (_: Throwable) { isolated = false }
        }
        val s = w.settings
        if (priv) { s.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE; s.setGeolocationEnabled(false) }
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.setSupportMultipleWindows(false)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.textZoom = prefs.textZoom
        if (!priv) CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        else if (isolated) try { ProfileStore.getInstance().getProfile(PRIVATE_PROFILE)?.cookieManager?.setAcceptThirdPartyCookies(w, true) } catch (_: Throwable) { }
        val tab = Tab(w)
        tab.priv = priv; tab.isolated = isolated
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
                if (!tab.priv) Store.addVisit(url, tab.title)
                if (tab === cur()) { progress.visibility = View.INVISIBLE; swipe.isRefreshing = false; updateChrome() }
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
                if (!tab.priv) Thumbs.saveIcon(applicationContext, Thumbs.hostOf(tab.url), icon)
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
                val id = (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Downloads.add(this, id)
                Toast.makeText(this, "Downloading… see the Downloads button", Toast.LENGTH_SHORT).show()
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
        if (tab.priv) return
        val host = Thumbs.hostOf(url)
        val known = host in Settings.dialHosts() || Store.bookmarkList().any { Thumbs.hostOf(it.url) == host }
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
        closeFind()
        current = i.coerceIn(0, tabs.size - 1)
        render()
    }

    private fun closeTab(i: Int) {
        if (tabs.size <= 1 && tabs[0].priv) {
            // closing the only tab, and it is private: swap in a normal tab and discard the private one
            val old = tabs[0]
            val fresh = newTab(null)
            tabs.remove(old); old.web.destroy()
            current = tabs.indexOf(fresh); render(); cleanupPrivate(); return
        }
        if (tabs.size <= 1) {
            val t = tabs[0]
            rememberClosed(t)
            t.home = true; t.url = ""; t.title = ""; t.thumb = null; t.icon = null
            t.web.loadUrl("about:blank"); t.web.clearHistory(); render(); return
        }
        val t = tabs.removeAt(i)
        rememberClosed(t)
        t.web.destroy()
        switchTo(if (current >= tabs.size) tabs.size - 1 else if (i < current) current - 1 else current)
        if (t.priv) cleanupPrivate()
    }

    /** When the last private tab is gone, throw away the separate private storage. */
    private fun cleanupPrivate() {
        if (tabs.any { it.priv }) return
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE)
        } catch (_: Throwable) { }
    }

    private val privateInfo: View by lazy {
        TextView(this).also {
            it.gravity = android.view.Gravity.CENTER
            it.setPadding(dp(32), dp(32), dp(32), dp(32))
            it.setTextColor(getColor(R.color.text))
            it.textSize = 15f
        }
    }

    private fun render() {
        swipe.isRefreshing = false
        container.removeAllViews()
        val t = cur() ?: return
        if (t.home && t.priv) {
            (privateInfo.parent as? ViewGroup)?.removeView(privateInfo)
            (privateInfo as TextView).text = "Private tab\n\nHistory, thumbnails and tab syncing are off for this tab. " +
                (if (t.isolated) "Cookies and site data are kept apart from your normal tabs and are deleted when you close your private tabs."
                else "This phone's browser engine can't keep private cookies separate, so sites you sign in to here share cookies with your normal tabs.")
            container.addView(privateInfo, FrameLayout.LayoutParams(-1, -1))
            urlBar.setText("")
            progress.visibility = View.INVISIBLE
        } else if (t.home) {
            (startPage.root.parent as? ViewGroup)?.removeView(startPage.root)
            container.addView(startPage.root, FrameLayout.LayoutParams(-1, -1))
            urlBar.setText("")
            progress.visibility = View.INVISIBLE
            startPage.refresh()
        } else {
            (t.web.parent as? ViewGroup)?.removeView(t.web)
            container.addView(t.web, FrameLayout.LayoutParams(-1, -1))
            urlBar.setText(t.url)
            t.pending?.let { t.pending = null; t.web.loadUrl(it) }
        }
        updateChrome()
    }

    private fun updateChrome() {
        val t = cur()
        tabCount.text = tabs.size.toString()
        updatePrivateNotice()
        val isPriv = t?.priv == true
        findViewById<View>(R.id.addressPill).setBackgroundResource(if (isPriv) R.drawable.bg_pill_private else R.drawable.bg_pill)
        urlBar.hint = if (isPriv) "Private: search or type URL" else "Search or type URL"
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
        switcherMode = if (cur()?.priv == true) 1 else 0
        switcherQuery = ""
        findViewById<EditText>(R.id.switcherFilter).setText("")
        findViewById<View>(R.id.switcherFilter).visibility = View.GONE
        switcher.visibility = View.VISIBLE
        refreshSwitcher()
    }

    private fun hideSwitcher() { switcher.visibility = View.GONE }

    // ---- recently closed tabs ----
    private fun rememberClosed(t: Tab) {
        if (t.priv || t.home || t.url.isEmpty()) return
        val old = try { org.json.JSONArray(sessionPrefs().getString("closed", "[]")) } catch (_: Exception) { org.json.JSONArray() }
        val a = org.json.JSONArray()
        a.put(org.json.JSONObject().put("url", t.url).put("title", t.title))
        for (i in 0 until minOf(old.length(), 29)) a.put(old.get(i))
        sessionPrefs().edit().putString("closed", a.toString()).apply()
    }

    private class Entry(val title: String, val sub: String, val url: String, val onOpen: () -> Unit)

    private fun refreshSwitcher() {
        val modes = listOf(R.id.modeTabs, R.id.modePrivate, R.id.modeSynced, R.id.modeClosed)
        modes.forEachIndexed { i, id ->
            val b = findViewById<View>(id)
            if (i == switcherMode) {
                val g = GradientDrawable(); g.cornerRadius = dp(22).toFloat(); g.setColor(0x33808080); b.background = g
            } else b.background = null
        }
        val empty = findViewById<TextView>(R.id.switcherEmpty)
        val q = switcherQuery
        fun match(vararg x: String) = q.isEmpty() || x.any { it.lowercase().contains(q) }

        if (switcherMode <= 1) {
            val wantPriv = switcherMode == 1
            val shownIdx = tabs.indices.filter { tabs[it].priv == wantPriv && match(tabs[it].title, tabs[it].url) }
            switcherTitle.text = (if (wantPriv) "Private tabs" else "Tabs") + " (${tabs.count { it.priv == wantPriv }})"
            empty.visibility = if (shownIdx.isEmpty()) View.VISIBLE else View.GONE
            empty.text = if (wantPriv) "No private tabs open. Tap + to open one." else "No tabs match."
            switcherList.layoutManager = GridLayoutManager(this, 2)
            switcherList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = shownIdx.size
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                    object : RecyclerView.ViewHolder(LayoutInflater.from(this@MainActivity).inflate(R.layout.item_tab, parent, false)) {}
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                    val real = shownIdx[pos]
                    val t = tabs[real]
                    val v = h.itemView
                    val label = if (t.home) (if (t.priv) "New private tab" else "Speed Dial") else t.title.ifBlank { t.url }
                    v.findViewById<TextView>(R.id.title).text = label
                    val thumb = v.findViewById<ImageView>(R.id.thumb)
                    val letter = v.findViewById<TextView>(R.id.letter)
                    val icon = v.findViewById<ImageView>(R.id.icon)
                    val shot = t.thumb
                    if (shot != null && !t.home) {
                        thumb.setImageBitmap(shot); thumb.visibility = View.VISIBLE; letter.visibility = View.GONE
                    } else {
                        thumb.visibility = View.GONE; letter.visibility = View.VISIBLE
                        letter.text = if (t.home) (if (t.priv) "👻" else "⌂") else (label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '•').toString()
                        val g = GradientDrawable()
                        g.cornerRadius = dp(12).toFloat()
                        g.setColor(Color.HSVToColor(floatArrayOf(Math.floorMod(label.hashCode(), 360).toFloat(), 0.4f, 0.45f)))
                        letter.background = g
                    }
                    val ic = t.icon
                    if (ic != null) { icon.setImageBitmap(ic); icon.imageTintList = null }
                    else { icon.setImageResource(R.drawable.ic_globe); icon.imageTintList = getColorStateList(R.color.muted) }
                    v.setOnClickListener { hideSwitcher(); switchTo(real) }
                    v.findViewById<View>(R.id.close).setOnClickListener {
                        val wasLast = tabs.size <= 1
                        closeTab(real)
                        if (wasLast) hideSwitcher() else refreshSwitcher()
                    }
                }
            }
            return
        }

        val rows = mutableListOf<Entry>()
        if (switcherMode == 2) {
            switcherTitle.text = "Other devices"
            val devices = try { org.json.JSONArray(prefs.otherTabs) } catch (_: Exception) { org.json.JSONArray() }
            for (i in 0 until devices.length()) {
                val d = devices.getJSONObject(i); val name = d.optString("device")
                val ts = d.optJSONArray("tabs") ?: continue
                for (j in 0 until ts.length()) {
                    val t = ts.getJSONObject(j); val u = t.optString("url"); val title = t.optString("title").ifBlank { u }
                    if (match(title, u, name)) rows.add(Entry(title, "$name  ·  $u", u) { hideSwitcher(); newTab(u) })
                }
            }
            empty.text = if (prefs.syncConfigured) "No tabs from other devices yet. They appear after each sync." else "Connect to your server in Settings to see tabs from your other devices."
        } else {
            switcherTitle.text = "Recently closed"
            val a = try { org.json.JSONArray(sessionPrefs().getString("closed", "[]")) } catch (_: Exception) { org.json.JSONArray() }
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i); val u = o.optString("url"); val title = o.optString("title").ifBlank { u }
                if (match(title, u)) rows.add(Entry(title, u, u) {
                    // reopen it and take it off the list
                    val rest = org.json.JSONArray(); for (k in 0 until a.length()) if (k != i) rest.put(a.get(k))
                    sessionPrefs().edit().putString("closed", rest.toString()).apply()
                    hideSwitcher(); newTab(u)
                })
            }
            empty.text = "Nothing was closed recently."
        }
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        switcherList.layoutManager = LinearLayoutManager(this)
        switcherList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = rows.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(LayoutInflater.from(this@MainActivity).inflate(R.layout.item_row, parent, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val r = rows[pos]; val v = h.itemView
                v.findViewById<TextView>(R.id.title).text = r.title
                v.findViewById<TextView>(R.id.sub).text = r.sub
                val icon = v.findViewById<ImageView>(R.id.icon)
                val ic = Thumbs.icon(this@MainActivity, Thumbs.hostOf(r.url))
                if (ic != null) { icon.setImageBitmap(ic); icon.imageTintList = null }
                else { icon.setImageResource(R.drawable.ic_globe); icon.imageTintList = getColorStateList(R.color.muted) }
                v.setOnClickListener { r.onOpen() }
            }
        }
    }

    // ---------- navigation ----------
    private fun toUrl(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return ""
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s)) return s
        if (!s.contains(' ') && (s.contains('.') || s.startsWith("localhost"))) return "https://$s"
        val e = engineOverride ?: Settings.defaultEngine()
        return e.url.replace("%s", java.net.URLEncoder.encode(s, "UTF-8"))
    }

    private fun navigate(input: String) {
        val url = toUrl(input)
        if (url.isEmpty()) return
        val t = cur() ?: return
        t.home = false
        t.url = url
        render()
        t.pending = null
        t.web.loadUrl(url)
        urlBar.clearFocus()
        // the page view is added while the search lists are still on screen; attach it again once the layout has settled
        ui.postDelayed({ if (cur() === t && !t.home && !urlBar.hasFocus()) render() }, 250)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun goHome() {
        val t = cur() ?: return
        hideSwitcher()
        t.home = true; t.url = ""; t.title = ""
        startPage.resetPath()
        render()
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
        Toast.makeText(this, if (on) "Bookmarked" else "Bookmark removed", Toast.LENGTH_SHORT).show()
        if (on) tab_thumbFor(t)
        updateChrome()
    }

    private fun tab_thumbFor(t: Tab) {
        val b = capture(t, 360) ?: return
        Thumbs.saveThumb(this, Thumbs.hostOf(t.url), b)
    }

    // ---------- find on this page ----------
    private fun openFind() {
        val bar = findViewById<View>(R.id.findBar)
        val input = findViewById<EditText>(R.id.findInput)
        val count = findViewById<TextView>(R.id.findCount)
        bar.visibility = View.VISIBLE
        fun web() = cur()?.takeIf { !it.home }?.web
        fun listen() = web()?.setFindListener { active, total, _ ->
            count.text = if (input.text.isEmpty()) "" else if (total == 0) "0" else "${active + 1}/$total"
        }
        listen()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: Editable?) {
                val q = e?.toString() ?: ""
                if (q.isEmpty()) { web()?.clearMatches(); count.text = "" } else web()?.findAllAsync(q)
            }
        })
        input.setOnEditorActionListener { _, _, _ -> web()?.findNext(true); true }
        findViewById<View>(R.id.findNext).setOnClickListener { web()?.findNext(true) }
        findViewById<View>(R.id.findPrev).setOnClickListener { web()?.findNext(false) }
        findViewById<View>(R.id.findClose).setOnClickListener { closeFind() }
        input.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0)
    }

    private fun closeFind() {
        val bar = findViewById<View>(R.id.findBar)
        if (bar.visibility != View.VISIBLE) return
        cur()?.web?.clearMatches()
        findViewById<EditText>(R.id.findInput).setText("")
        bar.visibility = View.GONE
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(bar.windowToken, 0)
    }

    // ---------- fill a saved login into the page ----------
    private fun fillLogin() {
        val t = cur()
        if (t == null || t.home) { Toast.makeText(this, "Open a login page first", Toast.LENGTH_SHORT).show(); return }
        if (!Vault.unlocked()) {
            Toast.makeText(this, "Unlock your passwords first", Toast.LENGTH_SHORT).show()
            picker.launch(Intent(this, PasswordsActivity::class.java)); return
        }
        val origin = Vault.originOf(t.url) ?: return
        val found = Vault.findByOrigin(origin)
        when {
            found.isEmpty() -> Toast.makeText(this, "No saved login for ${Vault.hostOf(origin)}", Toast.LENGTH_LONG).show()
            found.size == 1 -> injectLogin(t, found[0])
            else -> AlertDialog.Builder(this).setTitle("Choose a login")
                .setItems(found.map { it.optString("username").ifBlank { "(no user name)" } }.toTypedArray()) { _, i -> injectLogin(t, found[i]) }.show()
        }
    }

    private fun injectLogin(t: Tab, e: org.json.JSONObject) {
        val u = org.json.JSONObject.quote(e.optString("username")); val p = org.json.JSONObject.quote(e.optString("password"))
        val js = """(function(u,p){
            function set(el,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(el,v);
              el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}
            var all=[].slice.call(document.querySelectorAll('input')).filter(function(i){return i.offsetParent!==null||i.type==='password';});
            var pw=all.filter(function(i){return i.type==='password';})[0];
            var user=null;
            if(pw){for(var k=all.indexOf(pw)-1;k>=0;k--){var ty=(all[k].type||'text').toLowerCase();if(['text','email','tel'].indexOf(ty)>-1){user=all[k];break;}}}
            else{user=all.filter(function(i){return ['text','email','tel'].indexOf((i.type||'text').toLowerCase())>-1;})[0];}
            if(user&&u)set(user,u); if(pw)set(pw,p);
            return pw?'ok':(user?'user':'none');})($u,$p);"""
        t.web.evaluateJavascript(js) { r ->
            Toast.makeText(this, if (r.contains("none")) "No login fields found on this page" else "Filled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMenu(anchor: View) {
        val t = cur()
        val m = PopupMenu(this, anchor)
        val items = listOf(
            0 to "New tab", 9 to "New private tab", 1 to "Bookmarks", 2 to "History", 3 to "Tabs from other devices", 4 to "Sync now",
            5 to ((if (t?.desktop == true) "✓ " else "") + "Desktop site"), 10 to "Find on this page", 11 to "Passwords", 12 to "Fill saved login", 6 to "Share page", 7 to "Add to speed dial", 8 to "Settings",
        )
        items.forEachIndexed { i, (id, label) -> m.menu.add(0, id, i, label) }
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> newTab(null)
                9 -> newTab(null, true)
                1 -> picker.launch(Intent(this, ItemsActivity::class.java).putExtra("mode", "bookmarks"))
                2 -> picker.launch(Intent(this, ItemsActivity::class.java).putExtra("mode", "history"))
                3 -> picker.launch(Intent(this, ItemsActivity::class.java).putExtra("mode", "devices"))
                4 -> syncNow(true)
                5 -> if (t != null) { t.desktop = !t.desktop; applyUa(t); if (!t.home) t.web.reload() }
                6 -> if (t != null && !t.home) startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t.url), "Share"))
                7 -> if (t != null && !t.home) {
                    Settings.addToGroup(prefs.currentDial, t.title.ifBlank { Thumbs.hostOf(t.url) }, t.url)
                    tab_thumbFor(t)
                    Toast.makeText(this, "Added to speed dial", Toast.LENGTH_SHORT).show()
                }
                8 -> startActivity(Intent(this, SettingsActivity::class.java))
                11 -> picker.launch(Intent(this, PasswordsActivity::class.java))
                12 -> fillLogin()
                10 -> if (t != null && !t.home) openFind() else Toast.makeText(this, "Open a web page first", Toast.LENGTH_SHORT).show()
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
        val open = tabs.filter { !it.home && !it.priv }.map { it.url to it.title }
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
