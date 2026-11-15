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
import android.view.MotionEvent
import android.webkit.JavascriptInterface
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

class MainActivity : BaseActivity() {

    private class Tab(val web: WebView) {
        var title = ""
        @Volatile var url = ""
        var fresh = false               // the next page loaded is the first one after the speed dial: it starts a new history
        var pending: String? = null     // restored tab: loads when first shown
        var home = true                 // true until something has been loaded: shows the speed dial
        var thumb: Bitmap? = null
        var icon: Bitmap? = null
        var desktop = false
        var priv = false                // private tab: no history, thumbnails or syncing
        var isolated = false            // private tab with its own separate cookies and storage
    }

    // Tells the app when a touch starts on a video, player, slider or scrolled inner list, so pulling down there does not refresh the page.
    private val DRAG_GUARD_JS = """(function(){if(window.__mbRef)return;window.__mbRef=1;
      function bad(e){for(var n=e;n&&n.nodeType===1;n=n.parentElement){var t=n.tagName;
        if(t==='VIDEO'||t==='AUDIO'||t==='CANVAS'||n.draggable)return true;
        var c=(typeof n.className==='string'?n.className:'')+' '+(n.id||'');
        if(/player|drag|slider|carousel|swipe|map|sheet|drawer/i.test(c))return true;
        var cs=getComputedStyle(n);if(cs.touchAction==='none')return true;
        if((cs.overflowY==='auto'||cs.overflowY==='scroll')&&n.scrollHeight>n.clientHeight&&n.scrollTop>0)return true;}
        return false;}
      document.addEventListener('touchstart',function(e){try{mbNative.blockRefresh(bad(e.target));}catch(x){}},{passive:true,capture:true});
      document.addEventListener('touchend',function(){try{mbNative.blockRefresh(false);}catch(x){}},{passive:true,capture:true});
    })();"""
    // Hides the page's ad elements using the filter lists (asked for in small batches through the bridge).
    private val HIDE_JS = """(function(){if(window.__mbCos)return;window.__mbCos=1;
      var st=document.createElement('style');(document.head||document.documentElement).appendChild(st);
      var sI={},sC={};
      function run(first){var ids=[],cls=[];var els=document.querySelectorAll('[id],[class]');
        for(var i=0;i<els.length&&i<6000;i++){var e=els[i];if(e.id&&!sI[e.id]){sI[e.id]=1;ids.push(e.id);}
          var c=e.classList;for(var j=0;c&&j<c.length;j++){if(!sC[c[j]]){sC[c[j]]=1;cls.push(c[j]);}}}
        if(!first&&!ids.length&&!cls.length)return;
        try{var css=mbNative.cosmetic(location.hostname,JSON.stringify(ids),JSON.stringify(cls),first?1:0);
          if(css)st.appendChild(document.createTextNode(css));}catch(x){}}
      run(true);var t=null;
      try{new MutationObserver(function(){clearTimeout(t);t=setTimeout(function(){run(false)},400);}).observe(document.documentElement,{childList:true,subtree:true});}catch(x){}
    })();"""
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

    @Volatile private var refreshBlocked = false   // the finger went down on something the page wants to drag itself
    private var engineOverride: Engine? = null
    private var switcherMode = 0          // 0 tabs, 1 private, 2 other devices, 3 recently closed
    private var switcherQuery = ""
    private val tabs = mutableListOf<Tab>()
    private var current = -1
    private val ui = Handler(Looper.getMainLooper())

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra("url")?.let { newTab(it) }
    }

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val s = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == RESULT_OK && !s.isNullOrBlank()) { urlBar.setText(s); navigate(s) }
    }
    private val qrScan = registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { r ->
        r.contents?.takeIf { it.isNotBlank() }?.let { urlBar.setText(it); navigate(it) }
    }

    private fun startVoice() {
        try {
            voice.launch(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search or say a web address"))
        } catch (e: Exception) { Toast.makeText(this, "No voice search app found on this phone", Toast.LENGTH_LONG).show() }
    }

    private fun startQr() {
        qrScan.launch(com.journeyapps.barcodescanner.ScanOptions().setBeepEnabled(false).setOrientationLocked(false)
            .setCaptureActivity(PortraitCapture::class.java)
            .setPrompt("Scan a QR code").setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        Store.init(this)
        setupEdgeToEdge()

        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        container = findViewById(R.id.container)
        swipe = findViewById(R.id.swipe)
        swipe.setColorSchemeColors(tc(R.attr.mbAccent))
        swipe.setProgressBackgroundColorSchemeColor(tc(R.attr.mbCard))
        // only a web page that is scrolled to its top can be pulled down; the speed dial never refreshes
        swipe.setOnChildScrollUpCallback { _, _ -> val t = cur(); t == null || t.home || t.web.scrollY > 0 || refreshBlocked }
        swipe.setOnRefreshListener {
            val t = cur()
            if (t != null && !t.home) t.web.reload() else swipe.isRefreshing = false
        }
        findViewById<View>(R.id.appLogo).setOnClickListener { showMenu(it) }
        applyLogo()
        switcher = findViewById(R.id.switcher)
        switcherList = findViewById(R.id.switcherList)
        switcherTitle = findViewById(R.id.switcherTitle)
        siteIcon = findViewById(R.id.siteIcon)
        btnStar = findViewById(R.id.btnStar)
        tabCount = TextView(this)
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
        findViewById<View>(R.id.btnMic).setOnClickListener { startVoice() }
        findViewById<View>(R.id.btnQr).setOnClickListener { startQr() }
        urlBar.setOnFocusChangeListener { _, focused ->
            // while typing the right-hand side offers voice and QR search instead of bookmark and reload
            findViewById<View>(R.id.btnMic).visibility = if (focused) View.VISIBLE else View.GONE
            findViewById<View>(R.id.btnQr).visibility = if (focused) View.VISIBLE else View.GONE
            btnStar.visibility = if (focused) View.GONE else View.VISIBLE
            btnReload.visibility = if (focused) View.GONE else View.VISIBLE
            if (focused) { showEngines(); showSuggestions(""); pullHistoryForSuggestions() } else { engineOverride = null; findViewById<View>(R.id.engineStrip).visibility = View.GONE; findViewById<View>(R.id.suggestScroll).visibility = View.GONE }
        }
        btnReload.setOnClickListener {
            val t = cur()
            if (t != null && !t.home) { if (progress.visibility == View.VISIBLE) t.web.stopLoading() else t.web.reload() }
        }
        btnStar.setOnClickListener { toggleBookmark() }
        buildBottomBar()

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
                    t != null && !t.home -> { leaveToHome(t); render() }
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
            tv.setTextColor(tc(R.attr.mbText))
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(tc(R.attr.mbCard))
            if (e.id == chosen.id) bg.setStroke((3 * d).toInt(), tc(R.attr.mbAccent))
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

    private var lastFocusSync = 0L

    /** Fetch what your other devices visited, so the suggestions include it (at most every two minutes). */
    private fun pullHistoryForSuggestions() {
        if (!prefs.syncConfigured || !prefs.syncHistory || System.currentTimeMillis() - lastFocusSync < 120_000) return
        lastFocusSync = System.currentTimeMillis()
        val open = tabs.filter { !it.home && !it.priv }.map { it.url to it.title }
        Sync.runAsync(this, { open }) { _ -> ui.post { if (urlBar.hasFocus()) showSuggestions(urlBar.text.toString()) } }
    }

    // ---------- predictive suggestions from bookmarks and history ----------
    private fun showSuggestions(q0: String) {
        val scroll = findViewById<View>(R.id.suggestScroll)
        val list = findViewById<LinearLayout>(R.id.suggestList)
        val q = q0.trim().lowercase()
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
        if (q.isNotEmpty()) {
            Store.bookmarkList().filter { !it.deleted }.forEach { consider(it.url, it.title, true, 0) }
            Store.historyList().take(8000).forEachIndexed { i, v -> consider(v.url, v.title, false, if (i < 100) 10 else 0) }
        }
        if (q.isEmpty()) {
            // nothing typed yet: the most recent pages, from this phone and from your other devices
            val seen = HashSet<String>()
            for (v in Store.historyList().take(400)) {
                val key = bare(v.url).trimEnd('/')
                if (seen.add(key)) hits[key] = Hit(v.url, v.title, 1000 - seen.size, false)
                if (seen.size >= 6) break
            }
        }
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
            t1.setTextColor(tc(R.attr.mbText)); t1.textSize = 16f; t1.maxLines = 1; t1.ellipsize = android.text.TextUtils.TruncateAt.END
            val t2 = TextView(this); t2.text = bare(h.url)
            t2.setTextColor(tc(R.attr.mbMuted)); t2.textSize = 13f; t2.maxLines = 1; t2.ellipsize = android.text.TextUtils.TruncateAt.END
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

    /** Guesses what kind of resource a request is (the filter rules often depend on it); 0 when unknown. */
    private fun requestType(r: WebResourceRequest): Int {
        val ext = (r.url.path ?: "").lowercase().substringAfterLast('.', "")
        return when {
            ext == "js" || ext == "mjs" -> AdBlock.T_SCRIPT
            ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp") -> AdBlock.T_IMAGE
            ext == "css" -> AdBlock.T_STYLE
            ext in setOf("woff", "woff2", "ttf", "otf") -> AdBlock.T_FONT
            ext in setOf("mp4", "webm", "mp3", "m4a", "ogg", "m3u8", "ts") -> AdBlock.T_MEDIA
            else -> {
                val acc = r.requestHeaders["Accept"] ?: ""
                when {
                    acc.contains("text/html") -> AdBlock.T_SUBDOC
                    acc.startsWith("image/") -> AdBlock.T_IMAGE
                    acc.contains("text/css") -> AdBlock.T_STYLE
                    r.requestHeaders["X-Requested-With"] == "XMLHttpRequest" -> AdBlock.T_XHR
                    else -> 0
                }
            }
        }
    }

    private fun applyWebSettings() {
        swipe.isEnabled = prefs.pullRefresh
        for (t in tabs) {
            t.web.settings.textZoom = prefs.textZoom
        }
    }

    private fun applyUa(tab: Tab) {
        val s = tab.web.settings
        if (tab.desktop) {
            s.userAgentString = UserAgents.desktop(prefs.uaMode)
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
        } else {
            // drop the "; wv" marker so sites (Google sign-in in particular) are less likely to treat this as an embedded view
            s.userAgentString = UserAgents.mobile(prefs.uaMode, WebSettingsDefault.mobile(this))
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
                    AdBlock.shouldBlock(request.url.toString(), Thumbs.hostOf(tab.url), requestType(request))) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url.startsWith("about:")) return
                tab.url = url; tab.home = false; tab.icon = favicon ?: tab.icon
                if (tab === cur()) { urlBar.setText(url); progress.visibility = View.VISIBLE; updateChrome() }
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                if (prefs.blockTrackers && !url.startsWith("about:")) view.evaluateJavascript(HIDE_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith("about:")) return
                if (tab.fresh) { tab.fresh = false; view.clearHistory() }
                view.evaluateJavascript(DRAG_GUARD_JS, null)
                if (prefs.blockTrackers) view.evaluateJavascript(HIDE_JS, null)
                tab.url = url
                tab.title = view.title ?: ""
                if (!tab.priv) Store.addVisit(url, tab.title)
                if (tab === cur()) { progress.visibility = View.INVISIBLE; swipe.isRefreshing = false; updateChrome() }
                ui.postDelayed({ afterLoad(tab, url) }, 1500)
            }
        }
        w.addJavascriptInterface(object {
            @JavascriptInterface fun blockRefresh(b: Boolean) { refreshBlocked = b }
            @JavascriptInterface fun cosmetic(host: String, ids: String, classes: String, first: Int): String {
                if (!prefs.blockTrackers) return ""
                return try {
                    fun list(j: String): List<String> { val a = org.json.JSONArray(j); return (0 until a.length()).map { a.getString(it) } }
                    AdBlock.cosmeticCss(host, list(ids), list(classes), first == 1)
                } catch (_: Exception) { "" }
            }
        }, "mbNative")
        w.setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_DOWN) refreshBlocked = false; false }
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

    /** Keeps a preview for a speed dial site while its page is still on screen (so quick visits are not missed). */
    private fun saveThumbIfWanted(t: Tab) {
        val host = Thumbs.hostOf(t.url)
        if (host.isEmpty() || t.web.progress < 100 || Thumbs.thumb(this, host) != null) return
        if (host !in Settings.dialHosts()) return
        capture(t, 360)?.let { Thumbs.saveThumb(this, host, it) }
    }

    // ---- pictures for speed dials that have none yet: load them one by one in a hidden page view ----
    private val thumbTried = HashSet<String>()
    private var thumbBusy = false

    private fun fetchMissingThumbs() {
        if (thumbBusy || !Settings.thumbnails()) return
        val queue = ArrayDeque(Settings.dialUrls().filter { u ->
            val h = Thumbs.hostOf(u); h.isNotEmpty() && Thumbs.thumb(this, h) == null && thumbTried.add(h)
        }.take(10))
        if (queue.isEmpty()) return
        thumbBusy = true
        val root = findViewById<ViewGroup>(android.R.id.content)
        val w = resources.displayMetrics.widthPixels
        val hv = WebView(this)
        hv.alpha = 0.01f
        hv.settings.javaScriptEnabled = true; hv.settings.domStorageEnabled = true
        hv.settings.userAgentString = hv.settings.userAgentString.replace("; wv", "")
        root.addView(hv, 0, FrameLayout.LayoutParams(w, (w * 0.62f).toInt()))
        lateinit var next: () -> Unit
        var current = ""
        hv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith("about:") || current.isEmpty()) return
                val host = Thumbs.hostOf(current); val at = current
                ui.postDelayed({
                    if (at != current) return@postDelayed
                    try {
                        val full = Bitmap.createBitmap(w, (w * 0.62f).toInt(), Bitmap.Config.ARGB_8888)
                        hv.draw(Canvas(full))
                        Thumbs.saveThumb(this@MainActivity, host, Bitmap.createScaledBitmap(full, 360, (360 * 0.62f).toInt(), true))
                    } catch (_: Throwable) { }
                    next()
                }, 2500)
            }
        }
        next = {
            val u = queue.removeFirstOrNull()
            if (u == null || isFinishing) {
                current = ""; root.removeView(hv); hv.destroy(); thumbBusy = false
                if (cur()?.home == true) startPage.refresh()
            } else {
                current = u; hv.loadUrl(u)
                val started = u
                ui.postDelayed({ if (current == started) next() }, 15000)   // give up on pages that never finish
            }
        }
        next()
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
            it.setTextColor(tc(R.attr.mbText))
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
            ui.postDelayed({ if (cur()?.home == true) fetchMissingThumbs() }, 1500)
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
        btnStar.imageTintList = tcl(if (starred) R.attr.mbAccent else R.attr.mbText)
        if (t == null || t.home) {
            siteIcon.setImageResource(R.drawable.ic_search); siteIcon.imageTintList = tcl(R.attr.mbMuted)
        } else {
            val ic = t.icon ?: Thumbs.icon(this, Thumbs.hostOf(t.url))
            if (ic != null) { siteIcon.setImageBitmap(ic); siteIcon.imageTintList = null }
            else { siteIcon.setImageResource(R.drawable.ic_globe); siteIcon.imageTintList = tcl(R.attr.mbMuted) }
        }
        applyWallpaper()
    }

    // ---------- tab switcher ----------
    private fun showSwitcher() {
        cur()?.let { if (!it.home) capture(it, 360)?.let { b -> it.thumb = b } }
        switcherMode = if (cur()?.priv == true) 1 else 0
        switcherQuery = ""
        findViewById<EditText>(R.id.switcherFilter).setText("")
        findViewById<View>(R.id.switcherFilter).visibility = View.GONE
        switcher.visibility = View.VISIBLE
        applyWallpaper()
        refreshSwitcher()
    }

    private fun hideSwitcher() { switcher.visibility = View.GONE; applyWallpaper() }

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

    // swipe left/right on the tab switcher to move between Tabs, Private, Other devices and Recently closed
    private val switcherSwipes by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(a: android.view.MotionEvent?, b: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (a == null) return false
                val dx = b.x - a.x; val dy = b.y - a.y
                if (Math.abs(dx) < dp(70) || Math.abs(dx) < Math.abs(dy) * 1.8f || Math.abs(vx) < 600) return false
                val n = switcherMode + if (dx < 0) 1 else -1
                if (n !in 0..3) return false
                switcherMode = n; refreshSwitcher()
                switcherList.translationX = if (dx < 0) dp(60).toFloat() else -dp(60).toFloat(); switcherList.alpha = 0.3f
                switcherList.animate().translationX(0f).alpha(1f).setDuration(160).start()
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (::switcher.isInitialized && switcher.visibility == View.VISIBLE) switcherSwipes.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

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
                    else { icon.setImageResource(R.drawable.ic_globe); icon.imageTintList = tcl(R.attr.mbMuted) }
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
                else { icon.setImageResource(R.drawable.ic_globe); icon.imageTintList = tcl(R.attr.mbMuted) }
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
        // typed while the tab switcher is open: leave it and show the page. In Private it is browsed privately,
        // in the other sections normally
        if (switcher.visibility == View.VISIBLE) {
            val wantPriv = switcherMode == 1
            val here = cur()
            hideSwitcher()
            if (here == null || here.priv != wantPriv) {
                newTab(url, wantPriv)
                urlBar.clearFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBar.windowToken, 0)
                return
            }
        }
        val t = cur() ?: return
        if (t.home) t.fresh = true
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
        leaveToHome(t)
        startPage.resetPath()
        render()
    }

    /** Back to the speed dial: the page view is emptied so the old pages don't show up or stay in its history. */
    private fun leaveToHome(t: Tab) {
        if (!t.priv && !t.home) saveThumbIfWanted(t)
        t.home = true; t.url = ""; t.title = ""; t.pending = null
        t.web.loadUrl("about:blank")
        t.fresh = true
    }

    private fun goBack() {
        val t = cur() ?: return
        if (t.home) return
        if (t.web.canGoBack()) t.web.goBack() else { leaveToHome(t); render() }
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
        Vault.tryAutoUnlock(this)
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

    private var lastSelection = ""

    // ---------- edge to edge, wallpaper and the bottom bar ----------
    private var onWallpaper = false
    private var barSignature = ""

    private fun setupEdgeToEdge() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        val content = findViewById<View>(R.id.content)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootFrame)) { _, ins ->
            val bars = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val ime = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            content.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            ins
        }
    }

    /** Shows the wallpaper behind the whole screen (status bar to navigation bar) while the speed dial is open. */
    private fun applyWallpaper() {
        val t = cur()
        val wp = if (t?.home == true && !t.priv && switcher.visibility != View.VISIBLE) Wallpapers.drawable(this) else null
        val on = wp != null
        val iv = findViewById<ImageView>(R.id.wallpaper)
        if (on) { if (iv.drawable !== wp) iv.setImageDrawable(wp); iv.visibility = View.VISIBLE } else iv.visibility = View.GONE
        findViewById<View>(R.id.content).setBackgroundColor(if (on) android.graphics.Color.TRANSPARENT else tc(R.attr.mbBg))
        val barBg = if (on) android.graphics.Color.TRANSPARENT else tc(R.attr.mbBar)
        // the top bar and the engine strip share the page's own colour in every theme, so the top looks seamless
        findViewById<View>(R.id.topBar).setBackgroundColor(android.graphics.Color.TRANSPARENT)
        findViewById<View>(R.id.bottomBar).setBackgroundColor(barBg)
        findViewById<View>(R.id.engineStrip).setBackgroundColor(android.graphics.Color.TRANSPARENT)   // the search engine strip: no dark band over the wallpaper
        if (on) {
            val g = android.graphics.drawable.GradientDrawable(); g.cornerRadius = dp(26).toFloat(); g.setColor(android.graphics.Color.argb(0xCC, 0x1C, 0x1A, 0x1A))
            findViewById<View>(R.id.addressPill).background = g
        }
        val fg = if (on) android.graphics.Color.WHITE else tc(R.attr.mbText)
        val hint = if (on) android.graphics.Color.argb(0xB3, 255, 255, 255) else tc(R.attr.mbMuted)
        urlBar.setTextColor(fg); urlBar.setHintTextColor(hint)
        if (on) {
            siteIcon.imageTintList = android.content.res.ColorStateList.valueOf(hint)
            btnReload.imageTintList = android.content.res.ColorStateList.valueOf(fg)
            btnStar.imageTintList = android.content.res.ColorStateList.valueOf(fg)
            findViewById<ImageButton>(R.id.btnMic).imageTintList = android.content.res.ColorStateList.valueOf(fg)
            findViewById<ImageButton>(R.id.btnQr).imageTintList = android.content.res.ColorStateList.valueOf(fg)
        } else {
            btnReload.imageTintList = tcl(R.attr.mbText)
            findViewById<ImageButton>(R.id.btnMic).imageTintList = tcl(R.attr.mbText)
            findViewById<ImageButton>(R.id.btnQr).imageTintList = tcl(R.attr.mbText)
        }
        onWallpaper = on
        startPage.setOnWallpaper(on)
        IconStyle.apply(this, on)
        val lightBars = !on && androidx.core.graphics.ColorUtils.calculateLuminance(tc(R.attr.mbBg)) > 0.5
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).let {
            it.isAppearanceLightStatusBars = lightBars; it.isAppearanceLightNavigationBars = lightBars
        }
    }

    private fun barButton(key: String): View {
        val lp = LinearLayout.LayoutParams(0, -1, 1f)
        if (key == "tabs") {
            val f = FrameLayout(this)
            f.id = R.id.btnTabs; f.tag = key; f.contentDescription = "Tabs"
            val ic = ImageView(this); ic.id = R.id.tabsIcon; ic.layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), android.view.Gravity.CENTER)
            val n = TextView(this); n.id = R.id.tabCount; n.text = tabs.size.toString(); n.textSize = 12f; n.typeface = Typeface.DEFAULT_BOLD
            n.layoutParams = FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER)
            f.addView(ic); f.addView(n)
            tabCount = n
            f.setOnClickListener { if (switcher.visibility == View.VISIBLE) hideSwitcher() else showSwitcher() }
            f.setOnLongClickListener { v ->
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
            f.layoutParams = lp
            return f
        }
        val b = android.widget.ImageButton(this)
        b.tag = key
        b.id = when (key) {
            "downloads" -> R.id.btnDownloads; "back" -> R.id.btnBack; "forward" -> R.id.btnForward; "home" -> R.id.btnHome
            "search" -> R.id.btnSearchBar; "newtab" -> R.id.btnNewTab; "reload" -> R.id.btnReloadBar; "bookmarks" -> R.id.btnBookmarks
            else -> R.id.btnMenuBar
        }
        b.scaleType = ImageView.ScaleType.CENTER
        b.contentDescription = Toolbars.NAMES[key]
        b.layoutParams = lp
        b.setOnClickListener {
            when (key) {
                "downloads" -> startActivity(Intent(this, ItemsActivity::class.java).putExtra("mode", "downloads"))
                "back" -> goBack()
                "forward" -> cur()?.let { t -> if (!t.home && t.web.canGoForward()) t.web.goForward() }
                "home" -> goHome()
                "search" -> { hideSwitcher(); urlBar.requestFocus(); urlBar.selectAll()
                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(urlBar, 0) }
                "newtab" -> { hideSwitcher(); newTab(null) }
                "reload" -> cur()?.let { t -> if (!t.home) { if (progress.visibility == View.VISIBLE) t.web.stopLoading() else t.web.reload() } }
                "bookmarks" -> picker.launch(Intent(this, ItemsActivity::class.java).putExtra("mode", "bookmarks"))
                "menu" -> showMenu(it)
            }
        }
        return b
    }

    private fun buildBottomBar() {
        val bar = findViewById<LinearLayout>(R.id.bottomBar)
        val keys = Toolbars.keys(prefs)
        barSignature = keys.joinToString(",")
        bar.removeAllViews()
        for (k in keys) bar.addView(barButton(k))
        IconStyle.apply(this, onWallpaper)
    }

    private fun showMenu(anchor: View) {
        val t = cur()
        // remember what is selected on the page now: opening the menu can clear the selection
        lastSelection = ""
        if (t != null && !t.home) t.web.evaluateJavascript("(function(){return String(window.getSelection())})()") {
            lastSelection = try { org.json.JSONArray("[" + (it ?: "\"\"") + "]").getString(0).trim() } catch (e: Exception) { "" }
        }
        val pop = android.widget.PopupWindow(this)
        fun go(action: () -> Unit): () -> Unit = { pop.dismiss(); action() }
        fun need(action: (Tab) -> Unit): () -> Unit = go {
            if (t != null && !t.home) action(t) else Toast.makeText(this, "Open a web page first", Toast.LENGTH_SHORT).show()
        }

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val bg = android.graphics.drawable.GradientDrawable()
        bg.cornerRadius = dp(22).toFloat(); bg.setColor(tc(R.attr.mbCard)); bg.setStroke(dp(1), tc(R.attr.mbLine))
        card.background = bg
        card.setPadding(dp(8), dp(10), dp(8), dp(10))

        // quick row: back, home, forward, bookmark, reload
        val quick = LinearLayout(this)
        quick.orientation = LinearLayout.HORIZONTAL
        quick.gravity = Gravity.CENTER
        fun circle(icon: Int, desc: String, on: Boolean, action: () -> Unit) {
            val b = ImageButton(this)
            val g = android.graphics.drawable.GradientDrawable(); g.shape = android.graphics.drawable.GradientDrawable.OVAL; g.setColor(tc(R.attr.mbPill))
            b.background = g; b.setImageResource(icon); b.imageTintList = tcl(R.attr.mbText); b.contentDescription = desc
            b.alpha = if (on) 1f else 0.4f
            b.setOnClickListener { if (on) go(action)() }
            val lp = LinearLayout.LayoutParams(dp(44), dp(44)); lp.setMargins(dp(4), 0, dp(4), 0)
            quick.addView(b, lp)
        }
        val onPage = t != null && !t.home
        circle(R.drawable.ic_back, "Back", t != null && !t.home) { goBack() }
        circle(R.drawable.ic_home, "Home", true) { goHome() }
        circle(R.drawable.ic_forward, "Forward", onPage && t!!.web.canGoForward()) { t?.let { if (it.web.canGoForward()) it.web.goForward() } }
        circle(R.drawable.ic_star, "Bookmark this page", onPage) { toggleBookmark() }
        circle(R.drawable.ic_reload, "Reload", onPage) { t?.web?.reload() }
        card.addView(quick, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(6) })

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        fun row(icon: Int, label: String, action: () -> Unit) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL; r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(12), 0, dp(12), 0)
            val tv = TypedValueHolder.selectable(this); r.foreground = tv
            val ic = ImageView(this); ic.setImageResource(icon); ic.imageTintList = tcl(R.attr.mbMuted)
            r.addView(ic, LinearLayout.LayoutParams(dp(24), dp(24)))
            val l = TextView(this); l.text = label; l.textSize = 16f; l.setTextColor(tc(R.attr.mbText)); l.setPadding(dp(16), 0, 0, 0)
            r.addView(l)
            r.setOnClickListener { action() }
            list.addView(r, LinearLayout.LayoutParams(-1, dp(48)))
        }
        fun divider() {
            val d = View(this); d.setBackgroundColor(tc(R.attr.mbLine))
            list.addView(d, LinearLayout.LayoutParams(-1, dp(1)).also { it.setMargins(dp(12), dp(6), dp(12), dp(6)) })
        }
        row(R.drawable.ic_add, "New tab", go { newTab(null) })
        row(R.drawable.ic_ghost, "New private tab", go { newTab(null, true) })
        divider()
        row(R.drawable.ic_bookmarks, "Bookmarks", go { picker.launch(Intent(this, ItemsActivity::class.java).putExtra("mode", "bookmarks")) })
        row(R.drawable.ic_history, "History", go { picker.launch(Intent(this, ItemsActivity::class.java).putExtra("mode", "history")) })
        row(R.drawable.ic_download, "Downloads", go { startActivity(Intent(this, ItemsActivity::class.java).putExtra("mode", "downloads")) })
        row(R.drawable.ic_devices, "Tabs from other devices", go { picker.launch(Intent(this, ItemsActivity::class.java).putExtra("mode", "devices")) })
        row(R.drawable.ic_key, "Passwords", go { picker.launch(Intent(this, PasswordsActivity::class.java)) })
        divider()
        row(R.drawable.ic_desktop, (if (t?.desktop == true) "✓ " else "") + "Desktop site", need { tab -> tab.desktop = !tab.desktop; applyUa(tab); tab.web.reload() })
        row(R.drawable.ic_search, "Find on this page", need { openFind() })
        row(R.drawable.ic_translate, "Translate page", need { tab -> newTab(Translate.pageUrl(tab.url, prefs.translateLang)) })
        row(R.drawable.ic_translate, "Translate selected text", go {
            if (lastSelection.isNotEmpty()) newTab(Translate.textUrl(lastSelection, prefs.translateLang))
            else promptFields(this, "Translate text", listOf("Type or paste text (or select some on a page first)"), listOf(""), "Translate") { v ->
                if (v[0].isNotBlank()) newTab(Translate.textUrl(v[0], prefs.translateLang))
            }
        })
        row(R.drawable.ic_key, "Fill saved login", go { fillLogin() })
        row(R.drawable.ic_share, "Share page", need { tab -> startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tab.url), "Share")) })
        row(R.drawable.ic_add, "Add to speed dial", need { tab ->
            Settings.addToGroup(prefs.currentDial, tab.title.ifBlank { Thumbs.hostOf(tab.url) }, tab.url)
            tab_thumbFor(tab)
            Toast.makeText(this, "Added to speed dial", Toast.LENGTH_SHORT).show()
        })
        divider()
        row(R.drawable.ic_sync, "Sync now", go {
            // not signed in to a server yet: go to where that's set up
            if (prefs.syncConfigured) syncNow(true) else startActivity(Intent(this, SettingsActivity::class.java).putExtra("page", "data"))
        })
        row(R.drawable.ic_settings, "Settings", go { startActivity(Intent(this, SettingsActivity::class.java)) })

        val top = findViewById<View>(R.id.topBar)
        val loc = IntArray(2); top.getLocationOnScreen(loc)
        val y = loc[1] + top.height
        val maxH = resources.displayMetrics.heightPixels - y - dp(80)
        val scroll = object : android.widget.ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, android.view.View.MeasureSpec.makeMeasureSpec(maxH, android.view.View.MeasureSpec.AT_MOST))
        }
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(list)
        card.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        pop.contentView = card
        pop.width = minOf(dp(300), resources.displayMetrics.widthPixels - dp(16))
        pop.height = ViewGroup.LayoutParams.WRAP_CONTENT
        pop.isFocusable = true; pop.isOutsideTouchable = true
        pop.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        pop.elevation = dp(10).toFloat()
        pop.showAtLocation(window.decorView, Gravity.TOP or Gravity.END, dp(8), y)
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
                if (prefs.themeId != appliedTheme) { recreate(); return@post }   // a theme that came down from the server
                applyLogo()
                if (barSignature != Toolbars.keys(prefs).joinToString(",")) buildBottomBar()
                applyWallpaper()
                if (cur()?.home == true) startPage.refresh()
                updateChrome()
            }
        }
    }

    private fun applyLogo() {
        val v = findViewById<ImageView>(R.id.appLogo)
        val r = AppIcon.current(this).logo
        if (r != null) v.setImageResource(r) else v.setImageResource(R.mipmap.ic_launcher)
    }

    private var appliedUa = ""

    override fun onResume() {
        super.onResume()
        applyLogo()
        if (barSignature != Toolbars.keys(prefs).joinToString(",")) buildBottomBar()
        applyWallpaper()
        if (appliedUa != prefs.uaMode) {
            val first = appliedUa.isEmpty()
            appliedUa = prefs.uaMode
            if (!first) { tabs.forEach { applyUa(it) }; cur()?.let { if (!it.home) it.web.reload() } }
        }
    }
}

/** The WebView's stock user agent, read once without needing a live WebView on the UI path. */
object WebSettingsDefault {
    private var ua: String? = null
    fun mobile(ctx: android.content.Context): String =
        ua ?: android.webkit.WebSettings.getDefaultUserAgent(ctx).also { ua = it }
}

/** The system's ripple for a tappable row. */
object TypedValueHolder {
    fun selectable(ctx: android.content.Context): android.graphics.drawable.Drawable? {
        val tv = android.util.TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return androidx.core.content.ContextCompat.getDrawable(ctx, tv.resourceId)
    }
}

/** The QR scanner screen, kept upright (the library's own screen forces landscape). */
class PortraitCapture : com.journeyapps.barcodescanner.CaptureActivity()
