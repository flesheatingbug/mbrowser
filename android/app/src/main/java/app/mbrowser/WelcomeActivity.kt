package app.mbrowser

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/** First run. Two clear choices: browse on this phone only, or connect to your own server. */
class WelcomeActivity : BaseActivity() {
    private lateinit var prefs: Prefs
    private lateinit var box: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var step = "start"
    private val scan = registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { r ->
        val code = r.contents ?: return@registerForActivityResult
        val p = Pairing.parse(code)
        if (p == null) android.widget.Toast.makeText(this, "That is not an mbrowser code", android.widget.Toast.LENGTH_LONG).show()
        else { Pairing.apply(this, p); show("done-server") }
    }
    private val login = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) show("done-server")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(tc(R.attr.mbBg))
        scroll.fitsSystemWindows = true
        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(40), dp(24), dp(24))
        scroll.addView(box, ViewGroup.LayoutParams(-1, -2))
        setContentView(scroll)
        show(savedInstanceState?.getString("step") ?: "start")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (step == "start") finishSetup() else show("start")
            }
        })
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("step", step) }

    /** One list of themes: the default (follows the system light/dark setting) first, then every other colour theme. */
    private fun themePicker() {
        body("Theme", 22).also { it.setTextColor(tc(R.attr.mbText)) }
        var rowv: LinearLayout? = null
        Themes.LIST.forEachIndexed { n, (id, name) ->
            if (n % 3 == 0) {
                rowv = LinearLayout(this).also { it.orientation = LinearLayout.HORIZONTAL; it.setPadding(0, dp(8), 0, 0) }
                box.addView(rowv, LinearLayout.LayoutParams(-1, -2))
            }
            val w = android.view.ContextThemeWrapper(this, Themes.styleId(this, id))
            val on = prefs.themeId == id
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            col.addView(ThemePreview(this, w.tc(R.attr.mbBg), w.tc(R.attr.mbBar), w.tc(R.attr.mbPill), w.tc(R.attr.mbText), w.tc(R.attr.mbAccent),
                if (on) tc(R.attr.mbAccent) else w.tc(R.attr.mbLine), on), LinearLayout.LayoutParams(dp(92), dp(128)))
            val l = TextView(this)
            l.text = (if (id == "system") "Default" else name.substringBefore(" ("))
            l.textSize = 12f; l.maxLines = 1; l.gravity = Gravity.CENTER
            l.setTextColor(tc(if (on) R.attr.mbText else R.attr.mbMuted))
            col.addView(l, LinearLayout.LayoutParams(-2, -2))
            col.setOnClickListener {
                prefs.themeId = id
                if (id == "system") { prefs.themeMode = 0; App.applyTheme(0) }
                recreate()
            }
            rowv!!.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        // pad the last row so its tiles keep their width
        val rem = Themes.LIST.size % 3
        if (rem != 0) for (k in rem until 3) rowv!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    }

    private fun finishSetup() {
        // a fresh, standalone install starts with a few sample speed dials (not when joining a server that has its own)
        if (!prefs.syncConfigured && Store.bookmarkList().isEmpty()) {
            for ((u, t) in listOf("https://duckduckgo.com/" to "DuckDuckGo", "https://en.wikipedia.org/" to "Wikipedia",
                "https://www.youtube.com/" to "YouTube", "https://github.com/" to "GitHub", "https://www.openstreetmap.org/" to "OpenStreetMap",
                "https://news.ycombinator.com/" to "Hacker News")) Store.upsert(u, t, "")
        }
        prefs.firstRunDone = true
        finish()
    }

    // ---------- small view builders ----------
    private fun title(t: String) = TextView(this).also {
        it.text = t; it.textSize = 26f; it.typeface = Typeface.DEFAULT_BOLD; it.setTextColor(tc(R.attr.mbText)); box.addView(it)
    }

    private fun body(t: String, topMargin: Int = 8) = TextView(this).also {
        it.text = t; it.textSize = 15f; it.setTextColor(tc(R.attr.mbMuted))
        box.addView(it, LinearLayout.LayoutParams(-1, -2).also { lp -> lp.topMargin = dp(topMargin) })
    }

    private fun choiceCard(head: String, sub: String, onClick: () -> Unit) {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setBackgroundResource(R.drawable.bg_card)
        c.setPadding(dp(18), dp(18), dp(18), dp(18))
        val h = TextView(this); h.text = head; h.textSize = 18f; h.typeface = Typeface.DEFAULT_BOLD; h.setTextColor(tc(R.attr.mbText))
        val s = TextView(this); s.text = sub; s.textSize = 14f; s.setTextColor(tc(R.attr.mbMuted))
        c.addView(h); c.addView(s, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(4) })
        c.setOnClickListener { onClick() }
        box.addView(c, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(16) })
    }

    private fun primary(label: String, onClick: () -> Unit) = MaterialButton(this).also {
        it.text = label; it.isAllCaps = false; it.setOnClickListener { onClick() }
        box.addView(it, LinearLayout.LayoutParams(-1, dp(52)).also { lp -> lp.topMargin = dp(20) })
    }

    private fun link(label: String, onClick: () -> Unit) = TextView(this).also {
        it.text = label; it.textSize = 15f; it.setTextColor(tc(R.attr.mbAccent)); it.gravity = Gravity.CENTER
        it.setPadding(0, dp(14), 0, dp(14)); it.setOnClickListener { onClick() }
        box.addView(it, LinearLayout.LayoutParams(-1, -2))
    }

    private fun field(hint: String, password: Boolean = false): EditText = EditText(this).also {
        it.hint = hint; it.setSingleLine(); it.setTextColor(tc(R.attr.mbText)); it.setHintTextColor(tc(R.attr.mbMuted))
        it.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        it.inputType = if (password) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        box.addView(it, LinearLayout.LayoutParams(-1, -2).also { lp -> lp.topMargin = dp(14) })
    }

    // ---------- steps ----------
    private fun show(s: String) {
        step = s
        box.removeAllViews()
        when (s) {
            "start" -> {
                title("Welcome to mbrowser")
                body("How do you want to use it? You can change this any time in Settings.", 10)
                choiceCard("Just browse", "Use mbrowser on this phone only. Nothing is sent anywhere.") { show("local") }
                choiceCard("Connect to my server", "Sync bookmarks, history, tabs, speed dials and settings with your own mbrowser server.") { show("server") }
            }
            "server" -> {
                title("Connect to your server")
                body("Sign in with your account, or use an API key from your server's web page.", 10)
                choiceCard("Scan a QR code", "On your computer's mbrowser open Passwords and press “Add phone (QR)”. Connects this phone and unlocks your passwords.") {
                    scan.launch(Pairing.options())
                }
                choiceCard("Sign in with an account", "User name and password. You can choose which profile to sync with.") {
                    login.launch(android.content.Intent(this, ServerLoginActivity::class.java))
                }
                choiceCard("Use an API key", "Paste a key for one profile (Profiles → API keys on your server).") {
                    login.launch(android.content.Intent(this, ServerLoginActivity::class.java).putExtra("mode", "key"))
                }
                link("I don't have a server, just browse") { prefs.server = ""; prefs.token = ""; show("local") }
                link("Back") { show("start") }
            }
            "local" -> {
                title("Make it yours")
                body("Pick a search engine and a look. Both can be changed later in Settings.", 10)
                body("Search engine", 22).also { it.setTextColor(tc(R.attr.mbText)) }
                val engines = Settings.engines().take(4)
                val current = Settings.defaultEngine().id
                val chips = LinkedHashMap<String, TextView>()
                fun paintEngines() {
                    for ((id, tv) in chips) {
                        val on = id == Settings.defaultEngine().id
                        tv.setTextColor(tc(if (on) R.attr.mbAccent else R.attr.mbText))
                        tv.text = (if (on) "●  " else "○  ") + engines.first { it.id == id }.name
                    }
                }
                for (e in engines) {
                    val tv = TextView(this)
                    tv.textSize = 16f; tv.setPadding(dp(4), dp(12), dp(4), dp(12))
                    tv.setOnClickListener { Settings.setDefaultEngine(e.id); paintEngines() }
                    chips[e.id] = tv; box.addView(tv)
                }
                if (current.isEmpty()) Settings.setDefaultEngine(engines[0].id)
                paintEngines()
                themePicker()
                primary("Start browsing") { finishSetup() }
                link("Back") { show("start") }
            }
            "done-server" -> {
                title("Connected")
                body("Your bookmarks, history, tabs, speed dials and search engines will sync from your server. Passwords can be unlocked from the menu once you've set up a vault.", 10)
                themePicker()
                primary("Start browsing") { finishSetup() }
            }
        }
    }
}
