package app.mbrowser

import android.content.Context
import android.util.Base64
import org.bouncycastle.crypto.generators.SCrypt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The encrypted password vault, in the same format as the desktop browser (AES-256-GCM, key from scrypt). */
object Vault {
    private val DEFAULT_KDF = JSONObject().put("N", 65536).put("r", 8).put("p", 1)
    private const val TOMBSTONE_MS = 90L * 86_400_000L

    private var key: ByteArray? = null
    private var entries = mutableListOf<JSONObject>()
    private var folders = mutableListOf<String>()
    private var kdf: JSONObject = DEFAULT_KDF
    private var salt = ""
    private var device = false   // device mode: no master password, the key is kept on the device

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "vault.json")
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.DEFAULT)

    fun blobText(ctx: Context): String? = try { file(ctx).readText() } catch (_: Exception) { null }
    fun exists(ctx: Context): Boolean = try { JSONObject(blobText(ctx) ?: "").has("data") } catch (_: Exception) { false }
    fun unlocked() = key != null
    fun isDevice(ctx: Context): Boolean = try { JSONObject(blobText(ctx) ?: "").optBoolean("device") } catch (_: Exception) { false }

    /** Opens a device-mode vault with the raw 32-byte key (no master password). */
    fun unlockWithKey(ctx: Context, k: ByteArray): Boolean {
        val blob = try { JSONObject(blobText(ctx) ?: return false) } catch (_: Exception) { return false }
        if (!blob.optBoolean("device") || k.size != 32) return false
        return try {
            val (es, fs) = decrypt(blob, k)
            key = k.copyOf(); entries = es; folders = fs
            kdf = blob.getJSONObject("kdf"); salt = blob.optString("salt"); device = true
            true
        } catch (_: Exception) { false }
    }

    /** Opens a device-mode vault with the key remembered on this phone, if there is one. */
    fun tryAutoUnlock(ctx: Context) {
        if (key != null || !exists(ctx) || !isDevice(ctx)) return
        DeviceKey.load(ctx)?.let { unlockWithKey(ctx, it) }
    }
    fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun derive(master: String, saltBytes: ByteArray, k: JSONObject): ByteArray =
        SCrypt.generate(master.toByteArray(Charsets.UTF_8), saltBytes, k.optInt("N", 65536), k.optInt("r", 8), k.optInt("p", 1), 32)

    private fun decrypt(blob: JSONObject, k: ByteArray): Pair<MutableList<JSONObject>, MutableList<String>> {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, unb64(blob.getString("iv"))))
        val plain = c.doFinal(unb64(blob.getString("data")) + unb64(blob.getString("tag")))
        val j = JSONObject(String(plain, Charsets.UTF_8))
        val es = mutableListOf<JSONObject>()
        val a = j.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until a.length()) es.add(a.getJSONObject(i))
        val fs = mutableListOf<String>()
        val fa = j.optJSONArray("folders") ?: JSONArray()
        for (i in 0 until fa.length()) fs.add(fa.optString(i))
        return es to fs
    }

    private fun persist(ctx: Context) {
        val k = key ?: throw IllegalStateException("locked")
        val cutoff = System.currentTimeMillis() - TOMBSTONE_MS
        entries = entries.filter { !(it.optBoolean("deleted") && it.optLong("updated") < cutoff) }.toMutableList()
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, iv))
        val plain = JSONObject().put("entries", JSONArray(entries)).put("folders", JSONArray(folders)).toString()
        val out = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val data = out.copyOfRange(0, out.size - 16); val tag = out.copyOfRange(out.size - 16, out.size)
        val blob = JSONObject().put("v", 1).put("kdf", kdf).put("salt", salt)
            .put("iv", b64(iv)).put("tag", b64(tag)).put("data", b64(data))
        if (device) blob.put("device", true)
        val f = file(ctx); val tmp = File(f.path + ".tmp")
        tmp.writeText(blob.toString()); tmp.renameTo(f)
    }

    fun create(ctx: Context, master: String) {
        device = false
        val s = ByteArray(16).also { SecureRandom().nextBytes(it) }
        kdf = JSONObject(DEFAULT_KDF.toString()); salt = b64(s)
        key = derive(master, s, kdf)
        entries = mutableListOf(); folders = mutableListOf()
        persist(ctx)
    }

    /** Takes a few seconds on a phone: call off the main thread. */
    fun unlock(ctx: Context, master: String): Boolean {
        val blob = try { JSONObject(blobText(ctx) ?: return false) } catch (_: Exception) { return false }
        return try {
            val k = derive(master, unb64(blob.getString("salt")), blob.getJSONObject("kdf"))
            val (es, fs) = decrypt(blob, k)
            key = k; entries = es; folders = fs
            kdf = blob.getJSONObject("kdf"); salt = blob.getString("salt"); device = false
            true
        } catch (_: Exception) { false }
    }

    fun lock() { key?.fill(0); key = null; entries = mutableListOf(); folders = mutableListOf() }

    private fun live() = entries.filter { !it.optBoolean("deleted") }
    fun list(): List<JSONObject> = live().sortedBy { displayName(it).lowercase() }
    fun displayName(e: JSONObject): String = e.optString("name").ifBlank { hostOf(e.optString("origin")) }
    fun hostOf(origin: String): String = try { java.net.URI(origin).host?.removePrefix("www.") ?: origin } catch (_: Exception) { origin }
    fun findByOrigin(origin: String): List<JSONObject> = live().filter { it.optString("origin") == origin }

    fun originOf(url: String): String? = try {
        val u = java.net.URI(url.trim())
        if (u.scheme == "http" || u.scheme == "https") "${u.scheme}://${u.authority}" else null
    } catch (_: Exception) { null }

    fun upsert(ctx: Context, id: String?, url: String, name: String, username: String, password: String, note: String) {
        val o = originOf(if (url.contains("://")) url else "https://$url") ?: throw IllegalArgumentException("Enter a website address.")
        if (password.isEmpty()) throw IllegalArgumentException("Enter a password.")
        val now = System.currentTimeMillis()
        var e = (if (id != null) live().find { it.optString("id") == id } else null)
            ?: live().find { it.optString("origin") == o && it.optString("username") == username }
        if (e == null) {
            val rnd = ByteArray(8).also { SecureRandom().nextBytes(it) }
            e = JSONObject().put("id", "p" + rnd.joinToString("") { "%02x".format(it) }).put("created", now).put("folder", "")
            entries.add(e)
        }
        e!!.put("origin", o).put("username", username.take(512)).put("password", password).put("note", note.take(2000))
            .put("name", name.trim().take(120)).put("updated", now)
        persist(ctx)
    }

    fun remove(ctx: Context, id: String) {
        val e = entries.find { it.optString("id") == id } ?: return
        e.put("deleted", true).put("updated", System.currentTimeMillis())
            .put("username", "").put("password", "").put("note", "").put("name", "").put("folder", "")
        persist(ctx)
    }

    /** Replaces the local file with the server's; stays unlocked only if the current key still opens it. */
    fun adopt(ctx: Context, blob: JSONObject) {
        val clean = JSONObject().put("v", blob.optInt("v", 1)).put("kdf", blob.getJSONObject("kdf"))
        for (k in listOf("salt", "iv", "tag", "data")) clean.put(k, blob.optString(k))
        if (blob.optBoolean("device")) clean.put("device", true)
        val f = file(ctx); val tmp = File(f.path + ".tmp")
        tmp.writeText(clean.toString()); tmp.renameTo(f)
        val k = key ?: return
        try { val (es, fs) = decrypt(clean, k); entries = es; folders = fs; kdf = clean.getJSONObject("kdf"); salt = clean.optString("salt"); device = clean.optBoolean("device") }
        catch (_: Exception) { lock() }
    }

    /** Merges the server's vault into the open one. Returns null if it was made with a different key. */
    fun mergeRemote(ctx: Context, blob: JSONObject): Boolean? {
        val k = key ?: throw IllegalStateException("locked")
        val (remote, rfolders) = try { decrypt(blob, k) } catch (_: Exception) { return null }
        val before = JSONArray(entries).toString() + folders.toString()
        val byId = LinkedHashMap<String, JSONObject>()
        for (e in entries) byId[e.optString("id")] = e
        for (r in remote) {
            val l = byId[r.optString("id")]
            if (l == null || r.optLong("updated") > l.optLong("updated")) byId[r.optString("id")] = r
        }
        val seen = HashSet<String>()
        val merged = mutableListOf<JSONObject>()
        for (e in byId.values.sortedByDescending { it.optLong("updated") }) {
            if (!e.optBoolean("deleted")) {
                val kk = e.optString("origin") + "\n" + e.optString("username")
                if (!seen.add(kk)) continue
            }
            merged.add(e)
        }
        entries = merged
        folders = (folders + rfolders).distinct().toMutableList()
        val changed = (JSONArray(entries).toString() + folders.toString()) != before
        if (changed) persist(ctx)
        return changed
    }
}

/** Keeps the vault key on this phone, wrapped by a key that never leaves the Android Keystore. */
object DeviceKey {
    private const val ALIAS = "mbrowser_vault_wrap"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("vaultkey", Context.MODE_PRIVATE)

    private fun wrapKey(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val g = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    fun save(ctx: Context, k: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrapKey())
        val ct = c.doFinal(k)
        prefs(ctx).edit().putString("k", Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)).apply()
    }

    fun load(ctx: Context): ByteArray? {
        return try {
            val v = prefs(ctx).getString("k", null)?.split(":") ?: return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, Base64.decode(v[0], Base64.DEFAULT)))
            c.doFinal(Base64.decode(v[1], Base64.DEFAULT))
        } catch (_: Exception) { null }
    }

    fun clear(ctx: Context) { prefs(ctx).edit().clear().apply() }
}

/** Keeps the vault in step with the server (the server only ever sees ciphertext). Same rules as the desktop. */
object VaultSync {
    private fun same(a: JSONObject?, b: JSONObject?) = a != null && b != null &&
        listOf("salt", "iv", "tag", "data").all { a.optString(it) == b.optString(it) }

    /** Returns a short status message. */
    fun run(ctx: Context, api: Api): String {
        val prefs = Prefs(ctx)
        Vault.tryAutoUnlock(ctx)
        val localText = Vault.blobText(ctx)
        val local = try { JSONObject(localText ?: "") } catch (_: Exception) { null }
        val remote = api.request("GET", "/api/vault")
        val rExists = remote.optBoolean("exists")
        val rVer = remote.optLong("version")
        val rBlob = remote.optJSONObject("blob")

        fun upload(base: Long): Boolean {
            val text = Vault.blobText(ctx) ?: return true
            return try {
                val r = api.request("PUT", "/api/vault", JSONObject().put("base", base).put("blob", JSONObject(text)))
                prefs.vaultVer = r.optLong("version"); prefs.vaultHash = Vault.sha(text); true
            } catch (e: ApiException) { if (e.code == 409) false else throw e }
        }
        fun take(b: JSONObject, ver: Long) { Vault.adopt(ctx, b); prefs.vaultVer = ver; prefs.vaultHash = Vault.sha(Vault.blobText(ctx) ?: "") }

        val msg: String = when {
            !rExists && local == null -> "No password vault yet"
            !rExists -> if (upload(0)) "Passwords uploaded (encrypted)" else "The server changed, will retry"
            rBlob == null -> "The server vault is unreadable"
            local == null -> { take(rBlob, rVer); "Passwords downloaded." }
            same(local, rBlob) -> { prefs.vaultVer = rVer; prefs.vaultHash = Vault.sha(localText!!); "Passwords are up to date" }
            else -> {
                val localChanged = Vault.sha(localText!!) != prefs.vaultHash
                val remoteChanged = rVer != prefs.vaultVer
                when {
                    !localChanged && !remoteChanged -> "Passwords are up to date"
                    remoteChanged && !localChanged -> { take(rBlob, rVer); "Passwords updated from the server" }
                    !remoteChanged && localChanged -> if (upload(rVer)) "Password changes uploaded (encrypted)" else "The server changed, will retry"
                    !Vault.unlocked() -> "Unlock the passwords to finish syncing"
                    else -> {
                        val m = Vault.mergeRemote(ctx, rBlob)
                        when {
                            m == null -> if (prefs.vaultVer != 0L) "The server vault now uses a different master password." else "The server already has a different password vault."
                            upload(rVer) -> "Passwords merged and uploaded (encrypted)"
                            else -> "The server changed, will retry"
                        }
                    }
                }
            }
        }
        prefs.vaultStatus = msg
        return msg
    }

    fun runAsync(ctx: Context, onDone: (String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val prefs = Prefs(app)
            val msg = if (!prefs.syncConfigured) "Connect to a server in Settings to sync passwords."
            else try { synchronized(Sync) { run(app, Api(prefs.server, prefs.token, prefs.device)) } } catch (e: Exception) { "Sync failed: ${e.message}" }
            onDone(msg)
        }.start()
    }
}
