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
    private lateinit var profile: EditText
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
            profile = field("Profile (leave blank if you only have one)", prefs.profileName)
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
        val body = JSONObject().put("username", name).put("password", pass.text.toString()).put("device", prefs.device)
        val wantProfile = profile.text.toString().trim()
        if (wantProfile.isNotEmpty()) body.put("profile", wantProfile)
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
                needCode = true; code.visibility = View.VISIBLE; code.setText("")
                status.text = j.optString("error", "Enter the code from your authenticator app.")
                if (!this::submit.isInitialized) return
                build_keepFields(base, name)
            }
            r.code !in 200..299 -> status.text = j.optString("error", "Sign-in failed (HTTP ${r.code})")
            j.optBoolean("needProfile") -> chooseProfile(j)
            else -> {
                val p = j.optJSONObject("profile")
                val token = j.optString("token")
                if (token.isEmpty() || p == null) { status.text = "The server's answer was not understood."; return }
                finishConnect(base, token, j.optJSONObject("user")?.optString("username") ?: name, p.optString("id"), p.optString("name"))
            }
        }
    }

    /** Shows the code field (and its recovery-code link) while keeping everything typed so far. */
    private fun build_keepFields(base: String, name: String) {
        val pw = pass.text.toString(); val prof = profile.text.toString(); val msg = status.text.toString()
        prefs.server = base
        build()
        user.setText(name); pass.setText(pw); profile.setText(prof); status.text = msg
        code.requestFocus()
    }

    private fun chooseProfile(j: JSONObject) {
        val a = j.optJSONArray("profiles") ?: return
        val names = (0 until a.length()).map { a.getJSONObject(it).optString("name") }
        AlertDialog.Builder(this).setTitle("Which profile should this phone use?")
            .setItems(names.toTypedArray()) { _, i ->
                profile.setText(names[i])
                if (needCode) {
                    // the code was already used by the first request, so a fresh one is needed
                    code.setText("")
                    status.text = "Profile chosen. Enter the next code from your authenticator app and press Sign in."
                } else signIn()
            }.setCancelable(true).show()
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
