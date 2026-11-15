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

/** The saved logins, cards and addresses: unlock, view, copy, add, edit, sync. */
class PasswordsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var main: Button
    private lateinit var filter: EditText
    private lateinit var list: RecyclerView
    private lateinit var bar: View
    private lateinit var tabLogins: TextView
    private lateinit var tabCards: TextView
    private lateinit var tabAddresses: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var query = ""
    private var tab = 0  // 0=logins 1=cards 2=addresses

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_passwords)
        prefs = Prefs(this)
        Vault.tryAutoUnlock(this)
        status = findViewById(R.id.pwStatus); main = findViewById(R.id.pwMain)
        filter = findViewById(R.id.pwFilter); list = findViewById(R.id.pwList); bar = findViewById(R.id.pwBar)
        tabLogins = findViewById(R.id.tabLogins); tabCards = findViewById(R.id.tabCards); tabAddresses = findViewById(R.id.tabAddresses)
        list.layoutManager = LinearLayoutManager(this)
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: Editable?) { query = e?.toString()?.trim()?.lowercase() ?: ""; showList() }
        })
        tabLogins.setOnClickListener { selectTab(0) }
        tabCards.setOnClickListener { selectTab(1) }
        tabAddresses.setOnClickListener { selectTab(2) }
        findViewById<View>(R.id.pwAdd).setOnClickListener { when (tab) { 1 -> editCard(null); 2 -> editAddress(null); else -> editLogin(null) } }
        findViewById<View>(R.id.pwSync).setOnClickListener { sync(true) }
        findViewById<View>(R.id.pwLock).setOnClickListener {
            if (Vault.isDevice(this)) DeviceKey.clear(this)
            Vault.lock(); refresh()
        }
        refresh()
        // fetch the server's vault (or changes) in the background when opening
        if (prefs.syncConfigured && prefs.syncVault) sync(false)
    }

    private fun selectTab(t: Int) {
        tab = t
        val accent = getColorStateList(R.color.accent); val muted = getColorStateList(R.color.muted)
        tabLogins.setTextColor(if (t == 0) accent else muted); tabLogins.setTypeface(null, if (t == 0) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        tabCards.setTextColor(if (t == 1) accent else muted); tabCards.setTypeface(null, if (t == 1) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        tabAddresses.setTextColor(if (t == 2) accent else muted); tabAddresses.setTypeface(null, if (t == 2) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        query = ""; filter.setText("")
        showList()
    }

    private fun refresh() {
        main.visibility = View.GONE; filter.visibility = View.GONE; bar.visibility = View.GONE
        list.adapter = null
        val device = Vault.exists(this) && Vault.isDevice(this)
        findViewById<TextView>(R.id.pwLock).text = if (device) "Forget key" else "Lock"
        when {
            Vault.unlocked() -> {
                status.text = prefs.vaultStatus.ifBlank { "Unlocked" }
                filter.visibility = View.VISIBLE; bar.visibility = View.VISIBLE
                showList()
            }
            device -> {
                status.text = "Your passwords were downloaded from your server. Give this phone the vault key once: on your desktop browser open Passwords and press “📱 Phone key”, then paste it here." +
                    (if (prefs.vaultStatus.isNotBlank()) "\n\n" + prefs.vaultStatus else "")
                main.text = "Enter vault key"; main.visibility = View.VISIBLE
                main.setOnClickListener { askKey() }
            }
            Vault.exists(this) -> {
                status.text = "Your passwords are locked." + (if (prefs.vaultStatus.isNotBlank()) "\n" + prefs.vaultStatus else "")
                main.text = "Unlock"; main.visibility = View.VISIBLE
                main.setOnClickListener { askUnlock() }
            }
            else -> {
                status.text = if (prefs.syncConfigured) "No passwords found on your server yet. Open Passwords in your desktop browser and let it sync, then tap Download."
                else "Connect to your server in Settings to get the passwords from your desktop browser."
                if (prefs.syncConfigured) { main.text = "Download"; main.visibility = View.VISIBLE; main.setOnClickListener { sync(true) } }
            }
        }
    }

    private fun askKey() {
        promptFields(this, "Vault key", listOf("Paste the key from your desktop"), listOf(""), "Unlock") { v ->
            val k = try { android.util.Base64.decode(v[0].trim(), android.util.Base64.DEFAULT) } catch (_: Exception) { ByteArray(0) }
            if (Vault.unlockWithKey(this, k)) {
                DeviceKey.save(this, k)
                refresh(); if (prefs.syncConfigured && prefs.syncVault) sync(false)
            } else Toast.makeText(this, "That key does not open this vault", Toast.LENGTH_LONG).show()
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
        when (tab) {
            1 -> showCards()
            2 -> showAddresses()
            else -> showLogins()
        }
    }

    private fun showLogins() {
        val q = query
        val all = Vault.list().filter {
            q.isEmpty() || Vault.displayName(it).lowercase().contains(q) ||
                it.optString("username").lowercase().contains(q) || it.optString("origin").lowercase().contains(q)
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
                v.setOnClickListener { actionsLogin(e) }
            }
        }
    }

    private fun showCards() {
        val all = Vault.listCards().filter { query.isEmpty() || it.optString("holder").lowercase().contains(query) }
        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = if (all.isEmpty()) 1 else all.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(LayoutInflater.from(this@PasswordsActivity).inflate(R.layout.item_row, parent, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val v = h.itemView
                val title = v.findViewById<TextView>(R.id.title); val sub = v.findViewById<TextView>(R.id.sub)
                val icon = v.findViewById<ImageView>(R.id.icon)
                icon.setImageResource(R.drawable.ic_card); icon.imageTintList = getColorStateList(R.color.muted)
                if (all.isEmpty()) { title.text = "No saved cards"; sub.text = ""; v.setOnClickListener(null); return }
                val e = all[pos]
                val n = e.optString("number").takeLast(4)
                title.text = e.optString("holder").ifBlank { "Card" }
                sub.text = "•••• $n  exp ${e.optString("expMonth")}/${e.optString("expYear")}"
                v.setOnClickListener { actionsCard(e) }
            }
        }
    }

    private fun showAddresses() {
        val all = Vault.listAddresses().filter { query.isEmpty() || it.optString("name").lowercase().contains(query) || it.optString("city").lowercase().contains(query) }
        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = if (all.isEmpty()) 1 else all.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(LayoutInflater.from(this@PasswordsActivity).inflate(R.layout.item_row, parent, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val v = h.itemView
                val title = v.findViewById<TextView>(R.id.title); val sub = v.findViewById<TextView>(R.id.sub)
                val icon = v.findViewById<ImageView>(R.id.icon)
                icon.setImageResource(R.drawable.ic_location); icon.imageTintList = getColorStateList(R.color.muted)
                if (all.isEmpty()) { title.text = "No saved addresses"; sub.text = ""; v.setOnClickListener(null); return }
                val e = all[pos]
                title.text = e.optString("name").ifBlank { "Address" }
                sub.text = listOf(e.optString("address1"), e.optString("city"), e.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
                v.setOnClickListener { actionsAddress(e) }
            }
        }
    }

    private fun actionsLogin(e: JSONObject) {
        AlertDialog.Builder(this).setTitle(Vault.displayName(e))
            .setItems(arrayOf("Copy password", "Copy user name", "Show password", "Open website", "Edit", "Delete")) { _, w ->
                when (w) {
                    0 -> copy("Password", e.optString("password"), true)
                    1 -> copy("User name", e.optString("username"), false)
                    2 -> AlertDialog.Builder(this).setTitle(e.optString("username").ifBlank { Vault.displayName(e) })
                        .setMessage(e.optString("password")).setPositiveButton("Close", null).show()
                    3 -> { setResult(RESULT_OK, Intent().putExtra("url", e.optString("origin"))); finish() }
                    4 -> editLogin(e)
                    5 -> AlertDialog.Builder(this).setMessage("Delete the login for ${Vault.displayName(e)}?")
                        .setPositiveButton("Delete") { _, _ -> Vault.remove(this, e.optString("id")); showList(); sync(false) }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun actionsCard(e: JSONObject) {
        AlertDialog.Builder(this).setTitle(e.optString("holder").ifBlank { "Card" })
            .setItems(arrayOf("Copy number", "Copy CVV", "Show details", "Edit", "Delete")) { _, w ->
                when (w) {
                    0 -> copy("Card number", e.optString("number"), true)
                    1 -> copy("CVV", e.optString("cvv"), true)
                    2 -> AlertDialog.Builder(this).setTitle("Card details")
                        .setMessage("Holder: ${e.optString("holder")}\nNumber: ${e.optString("number")}\nExpiry: ${e.optString("expMonth")}/${e.optString("expYear")}\nCVV: ${e.optString("cvv")}")
                        .setPositiveButton("Close", null).show()
                    3 -> editCard(e)
                    4 -> AlertDialog.Builder(this).setMessage("Delete this card?")
                        .setPositiveButton("Delete") { _, _ -> Vault.remove(this, e.optString("id")); showList(); sync(false) }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun actionsAddress(e: JSONObject) {
        AlertDialog.Builder(this).setTitle(e.optString("name").ifBlank { "Address" })
            .setItems(arrayOf("Copy address", "Edit", "Delete")) { _, w ->
                when (w) {
                    0 -> copy("Address", listOf(e.optString("address1"), e.optString("city"), e.optString("state"), e.optString("zip"), e.optString("country")).filter { it.isNotBlank() }.joinToString(", "), false)
                    1 -> editAddress(e)
                    2 -> AlertDialog.Builder(this).setMessage("Delete this address?")
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

    private fun editLogin(e: JSONObject?) {
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

    private fun editCard(e: JSONObject?) {
        promptFields(this, if (e == null) "Add card" else "Edit card",
            listOf("Cardholder name", "Card number", "Expiry month (MM)", "Expiry year (YYYY)", "CVV"),
            listOf(e?.optString("holder") ?: "", e?.optString("number") ?: "",
                e?.optString("expMonth") ?: "", e?.optString("expYear") ?: "", e?.optString("cvv") ?: ""), "Save") { v ->
            try {
                Vault.saveCard(this, e?.optString("id"), v[0], v[1], v[2], v[3], v[4])
                showList(); sync(false)
            } catch (x: IllegalArgumentException) { Toast.makeText(this, x.message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun editAddress(e: JSONObject?) {
        promptFields(this, if (e == null) "Add address" else "Edit address",
            listOf("Full name", "Address line 1", "City", "State / Province", "Postcode / ZIP", "Country", "Phone", "Email"),
            listOf(e?.optString("name") ?: "", e?.optString("address1") ?: "", e?.optString("city") ?: "",
                e?.optString("state") ?: "", e?.optString("zip") ?: "", e?.optString("country") ?: "",
                e?.optString("phone") ?: "", e?.optString("email") ?: ""), "Save") { v ->
            try {
                Vault.saveAddress(this, e?.optString("id"), v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7])
                showList(); sync(false)
            } catch (x: IllegalArgumentException) { Toast.makeText(this, x.message, Toast.LENGTH_LONG).show() }
        }
    }
}
