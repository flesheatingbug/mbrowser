package app.mbrowser

import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private class Tab(val web: WebView) {
        var title = ""
        var url = ""
        var home = true          // true until something has been loaded: shows the speed dial
    }

    private lateinit var prefs: Prefs
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var container: FrameLayout
    private lateinit var startPage: View
    private lateinit var grid: GridView
    private lateinit var emptyHint: TextView
    private lateinit var btnStar: Button
    private lateinit var btnTabs: Button

    private val tabs = mutableListOf<Tab>()
    private var current = -1
    private val ui = Handler(Looper.getMainLooper())

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra("url")?.let { openInNewTab(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        Store.init(this)

        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        container = findViewById(R.id.container)
        btnStar = findViewById(R.id.btnStar)
        btnTabs = findViewById(R.id.btnTabs)
        startPage = LayoutInflater.from(this).inflate(R.layout.start_page, container, false)
        grid = startPage.findViewById(R.id.grid)
        emptyHint = startPage.findViewById(R.id.emptyHint)

        urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                navigate(urlBar.text.toString()); true
            } else false
        }
        findViewById<Button>(R.id.btnReload).setOnClickListener {
            val t = cur()
            if (t != null && !t.home) t.web.reload()
        }
        findViewById<Button>(R.id.btnBack).setOnClickListener { goBack() }
        findViewById<Button>(R.id.btnForward).setOnClickListener { cur()?.let { if (it.web.canGoForward()) it.web.goForward() } }
        btnStar.setOnClickListener { toggleBookmark() }
        btnTabs.setOnClickListener { showTabs() }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { showMenu(it) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val t = cur()
                when {
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
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val data = i?.data
        if (i?.action == Intent.ACTION_VIEW && data != null) openInNewTab(data.toString())
    }

    override fun onStart() {
        super.onStart()
        refreshStart()
        syncNow(false)
    }

    override fun onStop() {
        super.onStop()
        syncNow(false)
    }

    override fun onDestroy() {
        tabs.forEach { it.web.destroy() }
        super.onDestroy()
    }

    // ---------- tabs ----------
    private fun cur(): Tab? = tabs.getOrNull(current)

    private fun newTab(url: String?): Tab {
        val w = WebView(this)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.setSupportMultipleWindows(false)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        // Drop the "; wv" marker so sites (Google sign-in in particular) are less likely to treat this as an embedded view
        s.userAgentString = s.userAgentString.replace("; wv", "")
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        val tab = Tab(w)

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme ?: return false
                if (scheme == "http" || scheme == "https") return false
                return try { startActivity(Intent(Intent.ACTION_VIEW, u)); true } catch (_: Exception) { true }
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url.startsWith("about:")) return
                tab.url = url; tab.home = false
                if (tab === cur()) { urlBar.setText(url); progress.visibility = View.VISIBLE; updateStar() }
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith("about:")) return
                tab.url = url
                tab.title = view.title ?: ""
                Store.addVisit(url, tab.title)
                if (tab === cur()) { progress.visibility = View.INVISIBLE; updateStar() }
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (tab === cur()) progress.progress = newProgress
            }
            override fun onReceivedTitle(view: WebView, title: String?) { tab.title = title ?: "" }
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

    private fun openInNewTab(url: String) { newTab(url) }

    private fun switchTo(i: Int) {
        current = i.coerceIn(0, tabs.size - 1)
        render()
    }

    private fun closeTab(i: Int) {
        if (tabs.size <= 1) {
            tabs[0].home = true; tabs[0].url = ""; tabs[0].title = ""; tabs[0].web.loadUrl("about:blank")
            tabs[0].web.clearHistory(); render(); return
        }
        val t = tabs.removeAt(i)
        t.web.destroy()
        switchTo(if (current >= tabs.size) tabs.size - 1 else if (i < current) current - 1 else current)
    }

    private fun render() {
        container.removeAllViews()
        val t = cur() ?: return
        if (t.home) {
            container.addView(startPage)
            urlBar.setText("")
            progress.visibility = View.INVISIBLE
            refreshStart()
        } else {
            (t.web.parent as? ViewGroup)?.removeView(t.web)
            container.addView(t.web, FrameLayout.LayoutParams(-1, -1))
            urlBar.setText(t.url)
        }
        btnTabs.text = tabs.size.toString()
        updateStar()
    }

    private fun showTabs() {
        val names = tabs.mapIndexed { i, t ->
            (if (i == current) "● " else "") + (if (t.home) "Speed dial" else t.title.ifBlank { t.url })
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Tabs")
            .setItems(names) { _, i -> switchTo(i) }
            .setPositiveButton("New tab") { _, _ -> newTab(null) }
            .setNegativeButton("Close this tab") { _, _ -> closeTab(current) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    // ---------- navigation ----------
    private fun toUrl(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return ""
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(s)) return s
        if (!s.contains(' ') && (s.contains('.') || s.startsWith("localhost")) ) return "https://$s"
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
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun goBack() {
        val t = cur() ?: return
        if (!t.home && t.web.canGoBack()) t.web.goBack()
    }

    // ---------- bookmarks / menu ----------
    private fun updateStar() {
        val t = cur()
        btnStar.text = if (t != null && !t.home && Store.isBookmarked(t.url)) "★" else "☆"
    }

    private fun toggleBookmark() {
        val t = cur() ?: return
        if (t.home || t.url.isEmpty()) return
        val on = Store.toggleBookmark(t.url, t.title)
        Toast.makeText(this, if (on) "Bookmarked" else "Bookmark removed", Toast.LENGTH_SHORT).show()
        updateStar()
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        val items = listOf("New tab", "Bookmarks", "History", "Tabs from other devices", "Sync now", "Settings")
        items.forEachIndexed { i, s -> m.menu.add(0, i, i, s) }
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> newTab(null)
                1 -> picker.launch(Intent(this, ItemsActivity::class.java).putExtra("mode", "bookmarks"))
                2 -> picker.launch(Intent(this, ItemsActivity::class.java).putExtra("mode", "history"))
                3 -> picker.launch(Intent(this, ItemsActivity::class.java).putExtra("mode", "devices"))
                4 -> syncNow(true)
                5 -> startActivity(Intent(this, SettingsActivity::class.java))
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
                refreshStart()
                updateStar()
            }
        }
    }

    // ---------- speed dial ----------
    private fun refreshStart() {
        val bms = Store.bookmarkList()
        emptyHint.visibility = if (bms.isEmpty()) View.VISIBLE else View.GONE
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = bms.size
            override fun getItem(p: Int) = bms[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val b = bms[p]
                val tv = (convert as? TextView) ?: TextView(this@MainActivity)
                val label = b.title.ifBlank { b.url }
                tv.text = (label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '•').toString() + "\n" + label
                tv.maxLines = 3
                tv.gravity = Gravity.CENTER
                tv.setPadding(12, 24, 12, 24)
                tv.textSize = 12f
                tv.setTextColor(Color.WHITE)
                val g = GradientDrawable()
                g.cornerRadius = 24f
                g.setColor(Color.HSVToColor(floatArrayOf(Math.floorMod(label.hashCode(), 360).toFloat(), 0.45f, 0.55f)))
                tv.background = g
                return tv
            }
        }
        grid.setOnItemClickListener { _, _, pos, _ -> navigate(bms[pos].url) }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            AlertDialog.Builder(this).setMessage("Remove “${bms[pos].title}” from bookmarks?")
                .setPositiveButton("Remove") { _, _ -> Store.removeBookmark(bms[pos].url); refreshStart() }
                .setNegativeButton("Cancel", null).show()
            true
        }
    }
}
