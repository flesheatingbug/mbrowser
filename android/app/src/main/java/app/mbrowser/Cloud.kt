package app.mbrowser

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Base64
import org.bouncycastle.crypto.generators.SCrypt
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sync through your own Google Drive or OneDrive instead of the mbrowser server. Same design and file format as the
 * desktop browser (lib/cloudsync.js): the server API is rebuilt on a few AES-256-GCM encrypted files in the app's
 * private folder of the drive, with a key derived (scrypt) from a sync passphrase. The rest of the sync code only ever
 * talks to an [Api], so it does not care which backend is behind it.
 */
object Cloud {
    const val GOOGLE_CLIENT_ID = "661210334078-8k8nr9rlctlkiu228uc1abhj4dn4qjbj.apps.googleusercontent.com"
    const val MS_CLIENT_ID = "eab26192-63ec-4e18-8baa-8feb4fbf469f"
    private const val MAX_HISTORY = 20000
    private const val PAGE = 5000
    val lock = Any()
    private val driveIds = HashMap<String, String>()

    /** The Api the sync code should use for the backend chosen in settings. */
    fun api(ctx: Context, prefs: Prefs): Api =
        if (prefs.cloudBackend.isNotEmpty()) CloudApi(ctx.applicationContext, prefs) else Api(prefs.server, prefs.token, prefs.device)

    fun label(provider: String) = if (provider == "gdrive") "Google Drive" else "OneDrive"

    /** The secret Google wants for a desktop client: built into release builds, or pasted once in settings. */
    fun googleSecret(prefs: Prefs): String = BuildConfig.GOOGLE_CLIENT_SECRET.ifEmpty { prefs.cloudGSecret }

    /** Switching backend: everything must go to the new place, and the old revision counters mean nothing there. */
    fun selectBackend(prefs: Prefs, b: String) {
        val next = if (b == "gdrive" || b == "onedrive") b else "server"
        if (next == prefs.syncBackend) return
        prefs.syncBackend = next
        prefs.bmRev = 0; prefs.histRev = 0; prefs.otherTabs = "[]"; prefs.lookHash = ""
        prefs.vaultVer = 0; prefs.vaultHash = ""
        Store.markAllDirty()
    }

    fun disconnect(prefs: Prefs) {
        prefs.cloudProvider = ""; prefs.cloudAccess = ""; prefs.cloudRefresh = ""; prefs.cloudExpires = 0L; prefs.cloudKey = ""
        prefs.syncBackend = "server"
        synchronized(lock) { driveIds.clear() }
    }

    // ---------------- crypto (same format as the desktop) ----------------
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.DEFAULT)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16) }
    private fun scrypt(pass: String, salt: ByteArray) = SCrypt.generate(pass.toByteArray(Charsets.UTF_8), salt, 1 shl 15, 8, 1, 32)

    private fun seal(key: ByteArray, obj: JSONObject): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val out = c.doFinal(obj.toString().toByteArray(Charsets.UTF_8))
        val ct = out.copyOfRange(0, out.size - 16)
        val tag = out.copyOfRange(out.size - 16, out.size)
        return JSONObject().put("v", 1).put("iv", b64(iv)).put("tag", b64(tag)).put("data", b64(ct)).toString()
    }

    private fun open(key: ByteArray, text: String): JSONObject {
        val j = JSONObject(text)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, unb64(j.getString("iv"))))
        val plain = c.doFinal(unb64(j.getString("data")) + unb64(j.getString("tag")))
        return JSONObject(String(plain, Charsets.UTF_8))
    }

    // ---------------- http ----------------
    private class Resp(val code: Int, val text: String)

    private fun http(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 40_000
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return Resp(code, text)
        } finally { c.disconnect() }
    }

    private fun form(vararg kv: Pair<String, String>) = kv.joinToString("&") { URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second, "UTF-8") }

    private fun tokenUrl(provider: String) =
        if (provider == "gdrive") "https://oauth2.googleapis.com/token" else "https://login.microsoftonline.com/common/oauth2/v2.0/token"

    private fun clientId(provider: String) = if (provider == "gdrive") GOOGLE_CLIENT_ID else MS_CLIENT_ID

    private fun refresh(prefs: Prefs) {
        val provider = prefs.cloudProvider
        if (prefs.cloudRefresh.isEmpty()) throw ApiException(401, "Signed out of ${label(provider)}. Sign in again in Settings.")
        val kv = mutableListOf("client_id" to clientId(provider), "grant_type" to "refresh_token", "refresh_token" to prefs.cloudRefresh)
        if (provider == "gdrive" && googleSecret(prefs).isNotEmpty()) kv.add("client_secret" to googleSecret(prefs))
        val r = http("POST", tokenUrl(provider), mapOf("Content-Type" to "application/x-www-form-urlencoded"), form(*kv.toTypedArray()).toByteArray())
        val j = try { JSONObject(r.text) } catch (_: Exception) { JSONObject() }
        if (r.code !in 200..299 || j.optString("access_token").isEmpty())
            throw ApiException(401, "Could not renew the ${label(provider)} sign-in (${j.optString("error_description", j.optString("error", "HTTP ${r.code}"))}). Sign in again in Settings.")
        prefs.cloudAccess = j.getString("access_token")
        if (j.optString("refresh_token").isNotEmpty()) prefs.cloudRefresh = j.getString("refresh_token")
        prefs.cloudExpires = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000
    }

    private fun authed(prefs: Prefs, method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): Resp {
        if (prefs.cloudAccess.isEmpty() || System.currentTimeMillis() > prefs.cloudExpires - 60_000) refresh(prefs)
        fun go() = http(method, url, headers + ("Authorization" to "Bearer ${prefs.cloudAccess}"), body)
        var r = go()
        if (r.code == 401) { refresh(prefs); r = go() }
        return r
    }

    // ---------------- the two drives ----------------
    private fun gFind(prefs: Prefs, name: String): String? {
        driveIds[name]?.let { return it }
        val q = URLEncoder.encode("name='$name' and trashed=false", "UTF-8")
        val r = authed(prefs, "GET", "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&fields=files(id)&q=$q")
        if (r.code !in 200..299) throw ApiException(r.code, "Google Drive: HTTP ${r.code}")
        val f = JSONObject(r.text).optJSONArray("files") ?: JSONArray()
        if (f.length() == 0) return null
        val id = f.getJSONObject(0).getString("id")
        driveIds[name] = id
        return id
    }

    private fun driveRead(prefs: Prefs, name: String): String? {
        if (prefs.cloudProvider == "gdrive") {
            val id = gFind(prefs, name) ?: return null
            val r = authed(prefs, "GET", "https://www.googleapis.com/drive/v3/files/$id?alt=media")
            if (r.code == 404) { driveIds.remove(name); return null }
            if (r.code !in 200..299) throw ApiException(r.code, "Google Drive: HTTP ${r.code}")
            return r.text
        }
        val r = authed(prefs, "GET", "https://graph.microsoft.com/v1.0/me/drive/special/approot:/" + URLEncoder.encode(name, "UTF-8") + ":/content")
        if (r.code == 404) return null
        if (r.code !in 200..299) throw ApiException(r.code, "OneDrive: HTTP ${r.code}")
        return r.text
    }

    private fun driveWrite(prefs: Prefs, name: String, text: String) {
        if (prefs.cloudProvider == "gdrive") {
            val id = gFind(prefs, name)
            val r = if (id != null) {
                authed(prefs, "PATCH", "https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media", mapOf("Content-Type" to "application/json"), text.toByteArray(Charsets.UTF_8))
            } else {
                val b = "mb" + hex(ByteArray(8).also { SecureRandom().nextBytes(it) })
                val body = "--$b\r\ncontent-type: application/json; charset=UTF-8\r\n\r\n" + JSONObject().put("name", name).put("parents", JSONArray().put("appDataFolder")).toString() +
                    "\r\n--$b\r\ncontent-type: application/json\r\n\r\n$text\r\n--$b--"
                val res = authed(prefs, "POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", mapOf("Content-Type" to "multipart/related; boundary=$b"), body.toByteArray(Charsets.UTF_8))
                if (res.code in 200..299) try { driveIds[name] = JSONObject(res.text).getString("id") } catch (_: Exception) { }
                res
            }
            if (r.code !in 200..299) throw ApiException(r.code, "Google Drive: could not save $name (HTTP ${r.code})")
            return
        }
        val r = authed(prefs, "PUT", "https://graph.microsoft.com/v1.0/me/drive/special/approot:/" + URLEncoder.encode(name, "UTF-8") + ":/content", mapOf("Content-Type" to "text/plain"), text.toByteArray(Charsets.UTF_8))
        if (r.code !in 200..299) throw ApiException(r.code, "OneDrive: could not save $name (HTTP ${r.code})")
    }

    // ---------------- the server API, rebuilt on encrypted files ----------------
    private val FILES = mapOf("bookmarks" to "bookmarks.enc", "history" to "history.enc", "tabs" to "tabs.enc", "vault" to "vault.enc", "settings" to "settings.enc", "wallpaper" to "wallpaper.enc")

    private fun empty(kind: String): JSONObject = when (kind) {
        "bookmarks" -> JSONObject().put("rev", 0).put("items", JSONObject())
        "history" -> JSONObject().put("rev", 0).put("items", JSONArray())
        "tabs" -> JSONObject().put("devices", JSONObject())
        "vault" -> JSONObject().put("version", 0).put("blob", JSONObject.NULL)
        "wallpaper" -> JSONObject().put("updated", 0).put("data", "")
        else -> JSONObject().put("version", 0).put("doc", JSONObject.NULL)
    }

    private fun key(prefs: Prefs): ByteArray {
        if (prefs.cloudKey.isEmpty()) throw ApiException(401, "Cloud sync needs the sync passphrase (Settings > Sync).")
        return unhex(prefs.cloudKey)
    }

    private fun readFile(prefs: Prefs, kind: String): JSONObject {
        val text = driveRead(prefs, FILES.getValue(kind)) ?: return empty(kind)
        return try { open(key(prefs), text) } catch (e: ApiException) { throw e } catch (_: Exception) {
            throw ApiException(400, "Could not decrypt ${FILES[kind]}: the sync passphrase on this phone does not match the one used by your other devices.")
        }
    }

    private fun writeFile(prefs: Prefs, kind: String, d: JSONObject) = driveWrite(prefs, FILES.getValue(kind), seal(key(prefs), d))

    private fun good(u: String) = u.length <= 8000 && (u.startsWith("http://", true) || u.startsWith("https://", true))
    private fun clip(s: String?, n: Int = 900) = (s ?: "").take(n)

    private fun bookmarks(d: JSONObject, body: JSONObject): JSONObject {
        val since = body.optLong("since")
        val items = d.getJSONObject("items")
        var rev = d.optLong("rev")
        val arr = body.optJSONArray("items") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val url = it.optString("url").trim()
            if (!good(url) || !it.has("updated")) continue
            val cur = items.optJSONObject(url)
            if (cur != null && it.optLong("updated") <= cur.optLong("updated")) continue
            rev++
            items.put(url, JSONObject().put("title", clip(it.optString("title"))).put("folder", clip(it.optString("folder"), 300))
                .put("added", it.optLong("added")).put("updated", it.optLong("updated")).put("deleted", it.optBoolean("deleted")).put("rev", rev))
        }
        d.put("rev", rev)
        val out = JSONArray()
        for (k in items.keys()) {
            val v = items.getJSONObject(k)
            if (v.optLong("rev") > since) out.put(JSONObject().put("url", k).put("title", v.optString("title")).put("folder", v.optString("folder"))
                .put("added", v.optLong("added")).put("updated", v.optLong("updated")).put("deleted", v.optBoolean("deleted")))
        }
        return JSONObject().put("rev", rev).put("items", out)
    }

    private fun history(d: JSONObject, body: JSONObject): JSONObject {
        val since = body.optLong("since")
        var rev = d.optLong("rev")
        var items = d.getJSONArray("items")
        val seen = HashSet<String>()
        for (i in 0 until items.length()) { val h = items.getJSONObject(i); seen.add(h.optString("url") + "|" + h.optLong("time")) }
        val arr = body.optJSONArray("items") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val url = it.optString("url").trim()
            if (!good(url) || !it.has("time")) continue
            val k = url + "|" + it.optLong("time")
            if (!seen.add(k)) continue
            rev++
            items.put(JSONObject().put("url", url).put("title", clip(it.optString("title"))).put("time", it.optLong("time")).put("rev", rev))
        }
        if (items.length() > MAX_HISTORY) {
            val cut = JSONArray()
            for (i in items.length() - MAX_HISTORY until items.length()) cut.put(items.get(i))
            items = cut; d.put("items", cut)
        }
        d.put("rev", rev)
        val rows = ArrayList<JSONObject>()
        for (i in 0 until items.length()) { val h = items.getJSONObject(i); if (h.optLong("rev") > since && rows.size < PAGE) rows.add(h) }
        val outRev = if (rows.size == PAGE) rows.last().optLong("rev") else rev
        val out = JSONArray()
        for (h in rows) out.put(JSONObject().put("url", h.optString("url")).put("title", h.optString("title")).put("time", h.optLong("time")))
        return JSONObject().put("rev", outRev).put("items", out)
    }

    private fun tabsPut(d: JSONObject, body: JSONObject): JSONObject {
        val device = clip(body.optString("device"), 64).trim()
        if (device.isEmpty()) throw ApiException(400, "device is required")
        val tabs = JSONArray()
        val arr = body.optJSONArray("tabs") ?: JSONArray()
        for (i in 0 until minOf(arr.length(), 200)) {
            val t = arr.optJSONObject(i) ?: continue
            val u = t.optString("url").trim()
            if (good(u)) tabs.put(JSONObject().put("url", u).put("title", clip(t.optString("title"))))
        }
        d.getJSONObject("devices").put(device, JSONObject().put("updated", System.currentTimeMillis()).put("tabs", tabs))
        return JSONObject().put("ok", true)
    }

    private fun tabsGet(d: JSONObject, exclude: String): JSONObject {
        val list = ArrayList<JSONObject>()
        val devs = d.getJSONObject("devices")
        for (n in devs.keys()) if (n != exclude) { val v = devs.getJSONObject(n); list.add(JSONObject().put("device", n).put("updated", v.optLong("updated")).put("tabs", v.optJSONArray("tabs") ?: JSONArray())) }
        list.sortByDescending { it.optLong("updated") }
        return JSONObject().put("devices", JSONArray(list))
    }

    /** One request, like the server: [method] [path] with an optional JSON body. Throws ApiException(409) on a stale base, like the server. */
    fun run(prefs: Prefs, method: String, path: String, body: JSONObject?): JSONObject {
        val p = path.substringBefore('?')
        val q = if (path.contains('?')) path.substringAfter('?') else ""
        val exclude = q.split('&').firstOrNull { it.startsWith("exclude=") }?.substringAfter('=')?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
        val b = body ?: JSONObject()
        fun mutate(kind: String, fn: (JSONObject) -> JSONObject): JSONObject { val d = readFile(prefs, kind); val r = fn(d); writeFile(prefs, kind, d); return r }
        fun versioned(kind: String, field: String, validate: (JSONObject) -> Unit): JSONObject = mutate(kind) { d ->
            validate(b)
            if (b.optLong("base") != d.optLong("version")) throw ApiException(409, "$kind changed")
            d.put("version", d.optLong("version") + 1).put(field, b.get(if (field == "blob") "blob" else "doc"))
            JSONObject().put("version", d.optLong("version"))
        }
        return when {
            p == "/api/bookmarks" && method == "POST" -> mutate("bookmarks") { bookmarks(it, b) }
            p == "/api/history" && method == "POST" -> mutate("history") { history(it, b) }
            p == "/api/tabs" && method == "PUT" -> mutate("tabs") { tabsPut(it, b) }
            p == "/api/tabs" && method == "GET" -> tabsGet(readFile(prefs, "tabs"), exclude)
            p == "/api/vault" && method == "GET" -> readFile(prefs, "vault").let { d ->
                if (d.isNull("blob")) JSONObject().put("exists", false).put("version", 0).put("blob", JSONObject.NULL)
                else JSONObject().put("exists", true).put("version", d.optLong("version")).put("blob", d.getJSONObject("blob"))
            }
            p == "/api/vault" && method == "PUT" -> versioned("vault", "blob") { if (it.optJSONObject("blob") == null) throw ApiException(400, "invalid vault") }
            p == "/api/settings" && method == "GET" -> readFile(prefs, "settings")
            p == "/api/settings" && method == "PUT" -> versioned("settings", "doc") { if (it.optJSONObject("doc") == null) throw ApiException(400, "invalid settings document") }
            p == "/api/wallpaper" && method == "GET" -> readFile(prefs, "wallpaper")
            p == "/api/wallpaper" && method == "PUT" -> mutate("wallpaper") { d -> d.put("updated", b.optLong("updated")).put("data", b.optString("data")); JSONObject().put("ok", true) }
            else -> throw ApiException(404, "$path is not available with ${label(prefs.cloudProvider)} sync")
        }
    }

    // ---------------- passphrase ----------------
    /** First device: creates the passphrase check in the drive. Other devices: verifies against it. Returns an error message, or null when done. Call off the main thread. */
    fun setPassphrase(ctx: Context, pass: String): String? {
        val prefs = Prefs(ctx)
        if (prefs.cloudRefresh.isEmpty()) return "Sign in first."
        if (pass.length < 8) return "Use at least 8 characters for the sync passphrase."
        return try {
            synchronized(lock) {
                val text = driveRead(prefs, "mbrowser-meta.json")
                if (text != null) {
                    val meta = JSONObject(text)
                    val key = scrypt(pass, unb64(meta.getString("salt")))
                    val ok = try { open(key, meta.getJSONObject("check").toString()).optString("ok") == "mbrowser" } catch (_: Exception) { false }
                    if (!ok) return@synchronized "That is not the sync passphrase used by your other devices."
                    prefs.cloudKey = hex(key)
                } else {
                    val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
                    val key = scrypt(pass, salt)
                    driveWrite(prefs, "mbrowser-meta.json", JSONObject().put("v", 1).put("kdf", "scrypt-N32768-r8-p1").put("salt", b64(salt))
                        .put("check", JSONObject(seal(key, JSONObject().put("ok", "mbrowser")))).toString())
                    prefs.cloudKey = hex(key)
                }
                prefs.bmRev = 0; prefs.histRev = 0
                Store.markAllDirty()
                null
            }
        } catch (e: Exception) { "Could not reach ${label(prefs.cloudProvider)}: ${e.message}" }
    }

    // ---------------- sign in (OAuth with PKCE, answered on a loopback address) ----------------
    /**
     * Opens the sign-in page in another browser app (never in mbrowser: Google refuses embedded browsers) and waits for the
     * redirect on 127.0.0.1. [done] gets null on success or an error message, on a background thread.
     */
    fun beginAuth(ctx: Context, provider: String, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        if (provider == "gdrive" && googleSecret(prefs).isEmpty()) { done("Paste the Google client secret first."); return }
        val rnd = SecureRandom()
        val verifier = Base64.encodeToString(ByteArray(48).also { rnd.nextBytes(it) }, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        val state = Base64.encodeToString(ByteArray(16).also { rnd.nextBytes(it) }, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        val server = try { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) } catch (e: Exception) { done("Could not start the sign-in listener: ${e.message}"); return }
        val host = if (provider == "gdrive") "127.0.0.1" else "localhost"
        val redirect = "http://$host:${server.localPort}"
        val auth = if (provider == "gdrive") "https://accounts.google.com/o/oauth2/v2/auth" else "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
        val scope = if (provider == "gdrive") "https://www.googleapis.com/auth/drive.appdata" else "Files.ReadWrite.AppFolder offline_access"
        val kv = mutableListOf("client_id" to clientId(provider), "redirect_uri" to redirect, "response_type" to "code", "scope" to scope,
            "code_challenge" to challenge, "code_challenge_method" to "S256", "state" to state)
        if (provider == "gdrive") { kv.add("access_type" to "offline"); kv.add("prompt" to "consent") }
        val url = "$auth?" + form(*kv.toTypedArray())

        Thread {
            try {
                server.soTimeout = 5 * 60 * 1000
                val s = server.accept()
                val line = s.getInputStream().bufferedReader().readLine() ?: ""
                val query = line.substringAfter("GET /?", "").substringBefore(" ")
                val params = query.split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                fun reply(msg: String) {
                    val html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><body style='font:18px system-ui;padding:40px'>$msg</body>"
                    val bytes = html.toByteArray()
                    s.getOutputStream().use { o -> o.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()); o.write(bytes) }
                    s.close()
                }
                when {
                    params["state"] != state -> { reply("Sign-in failed. You can close this tab."); done("Sign-in failed (state mismatch).") }
                    params["error"] != null -> { reply("Sign-in was cancelled. You can close this tab."); done("Sign-in was cancelled (${params["error"]}).") }
                    else -> {
                        val tk = mutableListOf("client_id" to clientId(provider), "code" to (params["code"] ?: ""), "code_verifier" to verifier,
                            "grant_type" to "authorization_code", "redirect_uri" to redirect)
                        if (provider == "gdrive") tk.add("client_secret" to googleSecret(prefs))
                        val r = http("POST", tokenUrl(provider), mapOf("Content-Type" to "application/x-www-form-urlencoded"), form(*tk.toTypedArray()).toByteArray())
                        val j = try { JSONObject(r.text) } catch (_: Exception) { JSONObject() }
                        if (r.code !in 200..299 || j.optString("access_token").isEmpty()) {
                            reply("Sign-in failed. You can close this tab.")
                            done("Sign-in failed: " + j.optString("error_description", j.optString("error", "HTTP ${r.code}")))
                        } else if (j.optString("refresh_token").isEmpty()) {
                            reply("Sign-in failed. You can close this tab.")
                            done("The provider did not return a refresh token, so sync could not stay signed in. Try again.")
                        } else {
                            prefs.cloudProvider = provider; prefs.cloudAccess = j.getString("access_token"); prefs.cloudRefresh = j.getString("refresh_token")
                            prefs.cloudExpires = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000
                            prefs.cloudKey = ""
                            synchronized(lock) { driveIds.clear() }
                            prefs.syncBackend = provider
                            reply("Signed in. You can close this tab and go back to mbrowser.")
                            done(null)
                        }
                    }
                }
            } catch (e: java.net.SocketTimeoutException) { done("Sign-in timed out. Try again.") }
            catch (e: Exception) { done("Sign-in failed: ${e.message}") }
            finally { try { server.close() } catch (_: Exception) { } }
        }.start()

        // another browser app than ourselves: if mbrowser is the default browser, pick the first other one
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = app.packageManager
        val def = pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (def == null || def == app.packageName || def == "android") {
            val other = pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), PackageManager.MATCH_ALL)
                .firstOrNull { it.activityInfo.packageName != app.packageName }
            if (other != null) view.setPackage(other.activityInfo.packageName)
        }
        try { app.startActivity(view) } catch (e: Exception) { done("No other browser app is installed to sign in with (${e.message}).") }
    }
}

/** An [Api] that stores everything in the user's cloud drive instead of on the mbrowser server. */
class CloudApi(ctx: Context, private val prefs: Prefs) : Api("", "", prefs.device) {
    override fun request(method: String, path: String, body: JSONObject?): JSONObject =
        synchronized(Cloud.lock) { Cloud.run(prefs, method, path, body) }
}
