package app.mbrowser

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.util.concurrent.Executors

/** Connects to a sync server with an account (user name, password, optional code, profile) or with an API key. */
class ServerLoginActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var box: LinearLayout
    private val ui = Handler(Looper.getMainLooper())

    private var accountMode = true
    private var needCode = false
    private var useRecovery = false

    private lateinit var server: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var code: EditText
    private lateinit var key: EditText
    private lateinit var status: TextView
    private lateinit var submit: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        accountMode = intent.getStringExtra("mode") != "key"
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(getColor(R.color.bg))
        scroll.fitsSystemWindows = true
        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(32), dp(24), dp(24))
        scroll.addView(box, ViewGroup.LayoutParams(-1, -2))
        setContentView(scroll)
        build()
    }

    // ---------- UI ----------
    private fun text(t: String, size: Float, color: Int, bold: Boolean = false, top: Int = 8) = TextView(this).also {
        it.text = t; it.textSize = size; it.setTextColor(getColor(color))
        if (bold) it.typeface = Typeface.DEFAULT_BOLD
        box.addView(it, LinearLayout.LayoutParams(-1, -2).also { lp -> lp.topMargin = dp(top) })
    }

    private fun field(hint: String, value: String = "", password: Boolean = false, uri: Boolean = false): EditText = EditText(this).also {
        it.hint = hint; it.setText(value); it.setSingleLine()
        it.setTextColor(getColor(R.color.text)); it.setHintTextColor(getColor(R.color.muted))
        it.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        it.inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            uri -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            else -> InputType.TYPE_CLASS_TEXT
        }
        box.addView(it, LinearLayout.LayoutParams(-1, -2).also { lp -> lp.topMargin = dp(12) })
    }

    private fun link(label: String, onClick: () -> Unit) = TextView(this).also {
        it.text = label; it.textSize = 15f; it.setTextColor(getColor(R.color.accent)); it.gravity = Gravity.CENTER
        it.setPadding(0, dp(14), 0, dp(10)); it.setOnClickListener { onClick() }
        box.addView(it, LinearLayout.LayoutParams(-1, -2))
    }

    private fun build() {
        box.removeAllViews()
        text("Connect to your server", 24f, R.color.text, bold = true, top = 0)
        text(
            if (accountMode) "Sign in with the account you made on your server's web page. You can pick which profile this phone syncs with."
            else "Paste an API key from your server's web page (Profiles → API keys). It is tied to one profile.", 15f, R.color.muted
        )
        server = field("Server address, e.g. https://sync.example.com", prefs.server, uri = true)
        if (accountMode) {
            user = field("User name", prefs.accountUser)
            pass = field("Password", password = true)
            code = field(if (useRecovery) "Recovery code" else "Code from your authenticator app")
            code.visibility = if (needCode) View.VISIBLE else View.GONE
        } else {
            key = field("API key (starts with mbk_)", password = true)
        }
        status = text("", 14f, R.color.muted, top = 14)
        submit = MaterialButton(this).also {
            it.text = if (accountMode) "Sign in" else "Connect"; it.isAllCaps = false
            it.setOnClickListener { if (accountMode) signIn() else connectWithKey() }
            box.addView(it, LinearLayout.LayoutParams(-1, dp(52)).also { lp -> lp.topMargin = dp(16) })
        }
        if (accountMode && needCode) link(if (useRecovery) "Use an authenticator code instead" else "Use a recovery code instead") {
            useRecovery = !useRecovery; keepAndRebuild()
        }
        link(if (accountMode) "Use an API key instead" else "Sign in with an account instead") {
            accountMode = !accountMode; needCode = false; useRecovery = false; keepAndRebuild()
        }
        link("Cancel") { finish() }
    }

    /** Rebuilds the form but keeps what was typed in the address and user fields. */
    private fun keepAndRebuild() {
        prefs.server = server.text.toString()
        if (accountMode && this::user.isInitialized) prefs.accountUser = user.text.toString().trim()
        build()
    }

    private fun busy(b: Boolean, msg: String = "") {
        submit.isEnabled = !b
        status.text = msg
    }

    // ---------- account sign-in ----------
    private fun signIn() {
        val base = server.text.toString().trim().trimEnd('/')
        val name = user.text.toString().trim()
        if (base.isEmpty() || name.isEmpty() || pass.text.isEmpty()) { status.text = "Enter the server address, user name and password."; return }
        busy(true, "Signing in…")
        // list:true asks the server for the account's profiles and a short-lived ticket, so nothing is replaced yet
        val body = JSONObject().put("username", name).put("password", pass.text.toString()).put("device", prefs.device).put("list", true)
        if (needCode) {
            val c = code.text.toString().trim()
            if (useRecovery) body.put("recovery", c) else body.put("totp", c)
        }
        Executors.newSingleThreadExecutor().execute {
            var result: Login.Result? = null
            var err = ""
            try { result = Login.post(base, body) } catch (e: Exception) { err = e.message ?: "unknown error" }
            ui.post { onSignInResult(base, name, result, err) }
        }
    }

    private fun onSignInResult(base: String, name: String, r: Login.Result?, err: String) {
        busy(false)
        if (r == null) { status.text = "Cannot reach the server: $err"; return }
        val j = r.json
        when {
            r.code == 404 && j.optString("error").isEmpty() ->
                status.text = "This server is an older version without account sign-in. Use an API key instead."
            j.optBoolean("needTotp") -> {
                needCode = true
                status.text = j.optString("error", "Enter the code from your authenticator app.")
                rebuildKeeping(base, name)
            }
            r.code !in 200..299 -> status.text = j.optString("error", "Sign-in failed (HTTP ${r.code})")
            j.has("ticket") -> showProfiles(base, j.optJSONObject("user")?.optString("username") ?: name, j.optString("ticket"), j.optJSONArray("profiles"))
            j.has("token") -> {
                // an older server that signs straight in without listing profiles
                val p = j.optJSONObject("profile")
                finishConnect(base, j.optString("token"), name, p?.optString("id") ?: "", p?.optString("name") ?: "")
            }
            else -> status.text = "This server is an older version and can't offer a choice of profiles. Update the server, or use an API key."
        }
    }

    /** Rebuilds the form (to show the code field) while keeping what was typed. */
    private fun rebuildKeeping(base: String, name: String) {
        val pw = pass.text.toString(); val msg = status.text.toString()
        prefs.server = base
        build()
        user.setText(name); pass.setText(pw); status.text = msg
        code.requestFocus()
    }

    // ---------- choose or create a profile ----------
    private fun showProfiles(base: String, username: String, ticket: String, profiles: org.json.JSONArray?) {
        box.removeAllViews()
        text("Choose a profile", 24f, R.color.text, bold = true, top = 0)
        text("Signed in as $username. This phone will sync with the profile you pick. Each profile keeps its own bookmarks, history, speed dials and passwords.", 15f, R.color.muted)
        val msg = text("", 14f, R.color.muted, top = 10)
        val n = profiles?.length() ?: 0
        for (i in 0 until n) {
            val o = profiles!!.getJSONObject(i)
            val id = o.optString("id"); val pname = o.optString("name")
            val current = prefs.syncConfigured && prefs.profileId == id && prefs.server == base
            card(pname, if (current) "Currently used on this phone" else "Use this profile") {
                postPick(base, username, ticket, JSONObject().put("profile", id), msg)
            }
        }
        if (n == 0) text("This account has no profiles yet. Create the first one below.", 14f, R.color.muted, top = 12)
        card("＋  Create a new profile", "Starts empty. Your bookmarks here can then be added to it.") {
            promptFields(this, "New profile", listOf("Profile name, e.g. Phone"), listOf(""), "Create") { v ->
                if (v[0].isNotEmpty()) postPick(base, username, ticket, JSONObject().put("create", v[0]), msg)
            }
        }
        link("Back") { build() }
    }

    private fun card(head: String, sub: String, onClick: () -> Unit) {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setBackgroundResource(R.drawable.bg_card)
        c.setPadding(dp(18), dp(16), dp(18), dp(16))
        val h = TextView(this); h.text = head; h.textSize = 17f; h.typeface = Typeface.DEFAULT_BOLD; h.setTextColor(getColor(R.color.text))
        val s = TextView(this); s.text = sub; s.textSize = 13f; s.setTextColor(getColor(R.color.muted))
        c.addView(h); c.addView(s)
        c.setOnClickListener { onClick() }
        box.addView(c, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(12) })
    }

    private fun postPick(base: String, username: String, ticket: String, what: JSONObject, msg: TextView) {
        msg.text = "Working…"
        val body = what.put("ticket", ticket).put("device", prefs.device)
        Executors.newSingleThreadExecutor().execute {
            var result: Login.Result? = null
            var err = ""
            try { result = Login.post(base, body) } catch (e: Exception) { err = e.message ?: "unknown error" }
            ui.post {
                val r = result
                if (r == null) { msg.text = "Cannot reach the server: $err"; return@post }
                val j = r.json
                val p = j.optJSONObject("profile")
                if (r.code in 200..299 && p != null && j.optString("token").isNotEmpty())
                    finishConnect(base, j.optString("token"), username, p.optString("id"), p.optString("name"))
                else if (r.code == 401) { build(); this.status.text = j.optString("error", "The sign-in expired. Please sign in again.") }
                else msg.text = j.optString("error", "That did not work (HTTP ${r.code})")
            }
        }
    }

    // ---------- API key ----------
    private fun connectWithKey() {
        val base = server.text.toString().trim().trimEnd('/')
        val k = key.text.toString().trim()
        if (base.isEmpty() || k.isEmpty()) { status.text = "Enter the server address and the key."; return }
        busy(true, "Checking…")
        val oldServer = prefs.server; val oldToken = prefs.token
        prefs.server = base; prefs.token = k
        Executors.newSingleThreadExecutor().execute {
            val r = Sync.test(applicationContext)
            ui.post {
                busy(false)
                if (r.startsWith("Connected")) finishConnect(base, k, "", "", "")
                else { prefs.server = oldServer; prefs.token = oldToken; status.text = r }
            }
        }
    }

    // ---------- done ----------
    private fun finishConnect(base: String, token: String, username: String, profileId: String, profileName: String) {
        val changedProfile = prefs.profileId.isNotEmpty() && profileId.isNotEmpty() && prefs.profileId != profileId
        if (changedProfile) {
            // a different profile has different data: start from its data instead of mixing the two
            Store.resetAll(); Settings.reset()
            prefs.bmRev = 0; prefs.histRev = 0; prefs.otherTabs = "[]"
        }
        prefs.server = base; prefs.token = token
        prefs.accountUser = username; prefs.profileId = profileId; prefs.profileName = profileName
        setResult(Activity.RESULT_OK)
        finish()
    }
}
