package app.mbrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

/** The saved logins: unlock with the master password, then view, copy, add, edit and sync them. */
class PasswordsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var main: Button
    private lateinit var filter: EditText
    private lateinit var list: RecyclerView
    private lateinit var bar: View
    private val ui = Handler(Looper.getMainLooper())
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_passwords)
        prefs = Prefs(this)
        status = findViewById(R.id.pwStatus); main = findViewById(R.id.pwMain)
        filter = findViewById(R.id.pwFilter); list = findViewById(R.id.pwList); bar = findViewById(R.id.pwBar)
        list.layoutManager = LinearLayoutManager(this)
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: Editable?) { query = e?.toString()?.trim()?.lowercase() ?: ""; showList() }
        })
        findViewById<View>(R.id.pwAdd).setOnClickListener { edit(null) }
        findViewById<View>(R.id.pwSync).setOnClickListener { sync(true) }
        findViewById<View>(R.id.pwLock).setOnClickListener { Vault.lock(); refresh() }
        refresh()
        // fetch the server's vault (or changes) in the background when opening
        if (prefs.syncConfigured && prefs.syncVault) sync(false)
    }

    private fun refresh() {
        main.visibility = View.GONE; filter.visibility = View.GONE; bar.visibility = View.GONE
        list.adapter = null
        when {
            Vault.unlocked() -> {
                status.text = prefs.vaultStatus.ifBlank { "Unlocked" }
                filter.visibility = View.VISIBLE; bar.visibility = View.VISIBLE
                showList()
            }
            Vault.exists(this) -> {
                status.text = "Your passwords are locked." + (if (prefs.vaultStatus.isNotBlank()) "\n" + prefs.vaultStatus else "")
                main.text = "Unlock"; main.visibility = View.VISIBLE
                main.setOnClickListener { askUnlock() }
            }
            else -> {
                status.text = if (prefs.syncConfigured) "No password vault found yet. Checking your server…\nIf you have none, you can make a new one."
                else "No password vault yet. Make one here, or connect to your server in Settings to get the one from your desktop browser."
                main.text = "Create a vault"; main.visibility = View.VISIBLE
                main.setOnClickListener { askCreate() }
            }
        }
    }

    private fun askUnlock() {
        promptFields(this, "Unlock passwords", listOf("Master password"), listOf(""), "Unlock", password = true) { v ->
            status.text = "Unlocking… this can take a few seconds."
            Thread {
                val ok = Vault.unlock(this, v[0])
                ui.post {
                    if (!ok) { Toast.makeText(this, "Wrong master password", Toast.LENGTH_LONG).show(); refresh() }
                    else { refresh(); if (prefs.syncConfigured && prefs.syncVault) sync(false) }
                }
            }.start()
        }
    }

    private fun askCreate() {
        promptFields(this, "New master password", listOf("Master password", "Repeat it"), listOf("", ""), "Create", password = true) { v ->
            if (v[0].length < 8) { Toast.makeText(this, "Use at least 8 characters", Toast.LENGTH_LONG).show(); return@promptFields }
            if (v[0] != v[1]) { Toast.makeText(this, "The two passwords differ", Toast.LENGTH_LONG).show(); return@promptFields }
            status.text = "Creating…"
            Thread { Vault.create(this, v[0]); ui.post { refresh(); if (prefs.syncConfigured && prefs.syncVault) sync(false) } }.start()
        }
    }

    private fun sync(verbose: Boolean) {
        if (!prefs.syncConfigured) { if (verbose) Toast.makeText(this, "Connect to your server in Settings first", Toast.LENGTH_LONG).show(); return }
        VaultSync.runAsync(this) { msg ->
            ui.post {
                if (isFinishing) return@post
                if (verbose) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refresh()
            }
        }
    }

    private fun showList() {
        val all = Vault.list().filter {
            query.isEmpty() || Vault.displayName(it).lowercase().contains(query) ||
                it.optString("username").lowercase().contains(query) || it.optString("origin").lowercase().contains(query)
        }
        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = if (all.isEmpty()) 1 else all.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(LayoutInflater.from(this@PasswordsActivity).inflate(R.layout.item_row, parent, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val v = h.itemView
                val title = v.findViewById<TextView>(R.id.title); val sub = v.findViewById<TextView>(R.id.sub)
                val icon = v.findViewById<ImageView>(R.id.icon)
                if (all.isEmpty()) { title.text = "No saved logins"; sub.text = ""; v.setOnClickListener(null); return }
                val e = all[pos]
                title.text = Vault.displayName(e)
                sub.text = e.optString("username").ifBlank { "(no user name)" } + "  ·  " + Vault.hostOf(e.optString("origin"))
                val ic = Thumbs.icon(this@PasswordsActivity, Thumbs.hostOf(e.optString("origin")))
                if (ic != null) { icon.setImageBitmap(ic); icon.imageTintList = null }
                else { icon.setImageResource(R.drawable.ic_globe); icon.imageTintList = getColorStateList(R.color.muted) }
                v.setOnClickListener { actions(e) }
            }
        }
    }

    private fun actions(e: JSONObject) {
        AlertDialog.Builder(this).setTitle(Vault.displayName(e))
            .setItems(arrayOf("Copy password", "Copy user name", "Show password", "Open website", "Edit", "Delete")) { _, w ->
                when (w) {
                    0 -> copy("Password", e.optString("password"), true)
                    1 -> copy("User name", e.optString("username"), false)
                    2 -> AlertDialog.Builder(this).setTitle(e.optString("username").ifBlank { Vault.displayName(e) })
                        .setMessage(e.optString("password")).setPositiveButton("Close", null).show()
                    3 -> { setResult(RESULT_OK, Intent().putExtra("url", e.optString("origin"))); finish() }
                    4 -> edit(e)
                    5 -> AlertDialog.Builder(this).setMessage("Delete the login for ${Vault.displayName(e)}?")
                        .setPositiveButton("Delete") { _, _ -> Vault.remove(this, e.optString("id")); showList(); sync(false) }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun copy(label: String, text: String, secret: Boolean) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        if (secret && android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        cm.setPrimaryClip(clip)
        Toast.makeText(this, "$label copied" + if (secret) " (clears in 60 s)" else "", Toast.LENGTH_SHORT).show()
        if (secret) ui.postDelayed({
            try { if (cm.primaryClip?.getItemAt(0)?.text?.toString() == text) cm.setPrimaryClip(ClipData.newPlainText("", "")) } catch (_: Exception) { }
        }, 60_000)
    }

    private fun edit(e: JSONObject?) {
        promptFields(this, if (e == null) "Add login" else "Edit login",
            listOf("Name (optional)", "Website, e.g. https://example.com", "User name", "Password", "Note (optional)"),
            listOf(e?.optString("name") ?: "", e?.optString("origin") ?: "", e?.optString("username") ?: "",
                e?.optString("password") ?: "", e?.optString("note") ?: ""), "Save") { v ->
            try {
                Vault.upsert(this, e?.optString("id"), v[1], v[0], v[2], v[3], v[4])
                showList(); sync(false)
            } catch (x: IllegalArgumentException) { Toast.makeText(this, x.message, Toast.LENGTH_LONG).show() }
        }
    }
}
