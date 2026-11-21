package app.mbrowser

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocketFactory

/**
 * Home network mode. The WebView is pointed at a tiny proxy on the phone (127.0.0.1). Requests for private addresses
 * (192.168.x.x, 10.x.x.x, router.lan, a bare name like "nas") are sent through the sync server's tunnel so they reach
 * YOUR home network; everything else goes straight out as normal. The server must have ALLOW_TUNNEL=true and the
 * connection is authenticated with the sync key.
 */
object HomeNet {
    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    @Volatile private var prefsRef: Prefs? = null
    @Volatile var lastError: String = ""
        private set
    @Volatile var active = false
        private set

    val supported: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    /** A home-network server saved in THIS profile. [token] is the tunnel sign-in; [key] means "use the sync key instead". */
    data class Srv(val id: String, val name: String, val url: String, val key: Boolean, val user: String, val token: String)
    /** What the tunnel connects with right now. */
    data class Cfg(val id: String, val name: String, val server: String, val token: String, val key: Boolean)

    fun servers(prefs: Prefs): List<Srv> = try {
        val a = JSONArray(prefs.homeServers)
        (0 until a.length()).map { val o = a.getJSONObject(it); Srv(o.getString("id"), o.getString("name"), o.getString("url"), o.optBoolean("key"), o.optString("user"), o.optString("token")) }
    } catch (_: Exception) { emptyList() }

    private fun save(prefs: Prefs, list: List<Srv>) {
        val a = JSONArray(); for (s in list) a.put(JSONObject().put("id", s.id).put("name", s.name).put("url", s.url).put("key", s.key).put("user", s.user).put("token", s.token))
        prefs.homeServers = a.toString()
    }

    /** Active server's connection details, or null if there isn't a usable one (not signed in, or the sync key can't be used). */
    fun activeCfg(prefs: Prefs): Cfg? {
        val l = servers(prefs)
        val s = l.firstOrNull { it.id == prefs.homeActive } ?: l.firstOrNull() ?: return null
        if (s.key) return if (prefs.syncConfigured && prefs.server.trimEnd('/') == s.url) Cfg(s.id, s.name, s.url, prefs.token, true) else null
        return if (s.token.isNotEmpty()) Cfg(s.id, s.name, s.url, s.token, false) else null
    }

    fun add(prefs: Prefs, name: String, urlIn: String): String? {
        val url = urlIn.trim().trimEnd('/')
        if (!Regex("^https?://[^/\\s]+.*$", RegexOption.IGNORE_CASE).matches(url)) return "That is not a valid server address (it should start with https:// or http://)."
        val l = servers(prefs)
        if (l.any { it.url == url }) return "That server is already in the list."
        val n = name.trim().take(40).ifEmpty { try { URI(url).host ?: url } catch (_: Exception) { url } }
        save(prefs, l + Srv(java.util.UUID.randomUUID().toString().take(12), n, url, false, "", ""))
        if (prefs.homeActive.isEmpty()) prefs.homeActive = servers(prefs).first().id
        return null
    }

    fun remove(prefs: Prefs, id: String) {
        val l = servers(prefs)
        l.firstOrNull { it.id == id && it.token.isNotEmpty() }?.let { s -> Thread { logout(s.url, s.token) }.start() }
        save(prefs, l.filter { it.id != id })
        if (prefs.homeActive == id) prefs.homeActive = servers(prefs).firstOrNull()?.id ?: ""
    }

    fun update(prefs: Prefs, id: String, f: (Srv) -> Srv) = save(prefs, servers(prefs).map { if (it.id == id) f(it) else it })

    /** Seeds the list with the sync server the first time, so signing in is one step. */
    fun seed(prefs: Prefs) { if (servers(prefs).isEmpty() && prefs.syncConfigured) add(prefs, "My sync server", prefs.server) }

    // ---- account sign-in (password + one-time code), same rules as the server's web login ----
    class LoginResult(val token: String?, val user: String, val error: String?, val needTotp: Boolean)

    fun login(url: String, user: String, password: String, otp: String, device: String): LoginResult {
        return try {
            val c = URL("$url/api/tunnel/login").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 10000; c.readTimeout = 15000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            val b = JSONObject().put("username", user).put("password", password).put("device", device)
            val code = otp.trim()
            if (code.isNotEmpty()) { if (Regex("^\\d{6}$").matches(code)) b.put("totp", code) else b.put("recovery", code) }
            c.outputStream.use { it.write(b.toString().toByteArray()) }
            val st = c.responseCode
            val text = (if (st in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val j = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            when {
                st == 200 && j.has("token") -> LoginResult(j.getString("token"), j.optJSONObject("user")?.optString("username") ?: user, null, false)
                st == 404 -> LoginResult(null, user, "This server is too old for home network sign-in. Update the server image.", false)
                else -> LoginResult(null, user, j.optString("error", "The server answered HTTP $st."), j.optBoolean("needTotp"))
            }
        } catch (e: Exception) { LoginResult(null, user, "Could not reach the server (" + (e.message ?: e.javaClass.simpleName) + ").", false) }
    }

    fun logout(url: String, token: String) {
        try {
            val c = URL("$url/api/tunnel/logout").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Authorization", "Bearer $token"); c.responseCode
        } catch (_: Exception) { }
    }

    /** True for addresses that belong to a home network (and so go through the tunnel). */
    fun isHomeHost(hostIn: String): Boolean {
        val host = hostIn.trim('[', ']').lowercase()
        if (host.isEmpty() || host == "localhost" || host.startsWith("127.") || host == "::1") return false
        val v4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").find(host)
        if (v4 != null) {
            val a = v4.groupValues[1].toInt(); val b = v4.groupValues[2].toInt()
            return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
        }
        if (host.contains(':')) return host.startsWith("fc") || host.startsWith("fd")
        return !host.contains('.') || Regex("\\.(local|lan|home|home\\.arpa|internal)$").containsMatchIn(host)
    }

    /** Applies the current setting: starts the proxy and points the WebView at it, or switches it back off. */
    @Synchronized fun apply(ctx: Context, prefs: Prefs) {
        prefsRef = prefs
        val want = prefs.homeNetwork && activeCfg(prefs) != null && supported
        if (!want) {
            if (active) { try { ProxyController.getInstance().clearProxyOverride(java.util.concurrent.Executor { it.run() }, Runnable { }) } catch (_: Throwable) { } }
            active = false
            try { server?.close() } catch (_: Exception) { }; server = null
            return
        }
        if (server == null || server!!.isClosed) {
            val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")); server = s
            pool.execute { while (!s.isClosed) { try { val c = s.accept(); pool.execute { handle(c) } } catch (_: Exception) { } } }
        }
        try {
            ProxyController.getInstance().setProxyOverride(
                ProxyConfig.Builder().addProxyRule("127.0.0.1:" + server!!.localPort).build(),
                ContextCompat.getMainExecutor(ctx), Runnable { })
            active = true
        } catch (e: Throwable) { active = false; lastError = "Could not route the browser: " + (e.message ?: e.javaClass.simpleName) }
    }

    /** Asks the server whether the tunnel is ready for this sign-in. Returns null if it is, otherwise a readable problem. Blocking. */
    fun check(prefs: Prefs): String? {
        if (!supported) return "This phone's WebView can't be pointed at a proxy (needs a recent Android System WebView)."
        val cfg = activeCfg(prefs) ?: return "Add a server and sign in under Settings > Home network first."
        return try {
            val c = URL(cfg.server + "/api/tunnel/status").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Authorization", "Bearer " + cfg.token)
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401) (if (cfg.key) "The server rejected the sync key, or a proxy in front of it answered 401." else "Your home network sign-in expired. Sign in again under Settings > Home network.")
            else if (code == 403) (try { JSONObject(body).optString("error") } catch (_: Exception) { "" }).ifEmpty { "The server refused the sign-in." }
            else if (code == 404) "This server is too old for home network mode. Update the server image."
            else if (code != 200) "The server answered HTTP $code."
            else if (!body.contains("\"enabled\": true") && !body.contains("\"enabled\":true")) "The tunnel is switched off on this server. Set ALLOW_TUNNEL=true on it and restart it."
            else null
        } catch (e: Exception) { "Couldn't reach the server (" + (e.message ?: e.javaClass.simpleName) + ")." }
    }

    /** Turns the mode on after checking the server; [done] gets null on success or the reason it failed, on the main thread. */
    fun turnOn(ctx: Context, prefs: Prefs, done: (String?) -> Unit) {
        Thread {
            val problem = check(prefs)
            ContextCompat.getMainExecutor(ctx).execute {
                if (problem == null) { prefs.homeNetwork = true; apply(ctx, prefs) }
                done(problem)
            }
        }.start()
    }

    fun turnOff(ctx: Context, prefs: Prefs) { prefs.homeNetwork = false; apply(ctx, prefs) }

    // ---- the tunnel ----
    private fun openTunnel(host: String, port: Int): Socket {
        val pr = prefsRef ?: throw Exception("Home network is not configured.")
        val c = activeCfg(pr) ?: throw Exception("Home network: sign in under Settings > Home network.")
        val srv = c.server; val token = c.token; val device = pr.device
        val u = URI(srv)
        val secure = u.scheme.equals("https", true)
        val p = if (u.port > 0) u.port else if (secure) 443 else 80
        val raw = Socket(); raw.connect(InetSocketAddress(u.host, p), 10000); raw.soTimeout = 15000
        val sock: Socket = if (secure) (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, u.host, p, true) else raw
        val base = (u.rawPath ?: "").trimEnd('/')
        val target = (if (host.contains(':')) "[$host]" else host) + ":" + port
        sock.getOutputStream().write(("GET $base/api/tunnel HTTP/1.1\r\nHost: ${u.authority}\r\nAuthorization: Bearer $token\r\nX-Device: $device\r\n" +
            "X-Target: $target\r\nConnection: Upgrade\r\nUpgrade: mbtunnel\r\n\r\n").toByteArray())
        sock.getOutputStream().flush()
        val head = readHead(sock.getInputStream()) ?: throw Exception("The sync server closed the connection.")
        val code = Regex("^HTTP/1\\.[01] (\\d{3})").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (code != 101) {
            sock.close()
            throw Exception(if (code == 401) "The home network sign-in was rejected or has expired. Sign in again under Settings > Home network." else if (code == 403) "The server refused that (the address may not be allowed, or sign-in is required)." else "The sync server refused the tunnel (HTTP $code).")
        }
        sock.soTimeout = 0
        return sock
    }

    private fun readHead(i: InputStream): String? {
        val b = StringBuilder()
        while (b.length < 65536) {
            val c = i.read(); if (c < 0) return null
            b.append(c.toChar())
            if (b.length >= 4 && b.endsWith("\r\n\r\n")) return b.substring(0, b.length - 4)
        }
        return null
    }

    private fun connect(host: String, port: Int): Socket {
        if (isHomeHost(host)) return openTunnel(host, port)
        val s = Socket(); s.connect(InetSocketAddress(host, port), 10000); return s
    }

    private fun pipe(a: Socket, b: Socket) {
        fun copy(from: Socket, to: Socket) {
            try { val buf = ByteArray(16384); val i = from.getInputStream(); val o = to.getOutputStream()
                while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); o.flush() } } catch (_: Exception) { }
            try { a.close() } catch (_: Exception) { }; try { b.close() } catch (_: Exception) { }
        }
        pool.execute { copy(b, a) }
        copy(a, b)
    }

    private fun fail(o: OutputStream, msg: String) {
        lastError = msg
        val body = "Home network: $msg"
        try { o.write(("HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray()); o.flush() } catch (_: Exception) { }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 30000
            val cin = client.getInputStream(); val cout = client.getOutputStream()
            val head = readHead(cin) ?: return
            client.soTimeout = 0
            val lines = head.split("\r\n")
            val parts = lines[0].split(' ')
            if (parts.size < 3) return
            val method = parts[0]; val target = parts[1]
            if (method.equals("CONNECT", true)) {
                val m = Regex("^\\[?([^\\]]+?)\\]?:(\\d+)$").find(target) ?: run { fail(cout, "bad address"); return }
                val up = try { connect(m.groupValues[1], m.groupValues[2].toInt()) } catch (e: Exception) { fail(cout, e.message ?: "could not connect"); return }
                cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); cout.flush()
                pipe(client, up)
                return
            }
            val u = try { URI(target) } catch (_: Exception) { fail(cout, "bad request"); return }
            val host = u.host ?: run { fail(cout, "bad request"); return }
            val up = try { connect(host, if (u.port > 0) u.port else 80) } catch (e: Exception) { fail(cout, e.message ?: "could not connect"); return }
            val path = (u.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
            val keepUpgrade = lines.any { it.startsWith("Upgrade:", true) }
            val sb = StringBuilder("$method $path ${parts[2]}\r\n")
            for (l in lines.drop(1)) {
                val n = l.substringBefore(':').lowercase()
                if (n == "proxy-connection" || n == "proxy-authorization" || n == "connection" || n == "keep-alive") continue
                sb.append(l).append("\r\n")
            }
            sb.append(if (keepUpgrade) "Connection: Upgrade\r\n\r\n" else "Connection: close\r\n\r\n")
            up.getOutputStream().write(sb.toString().toByteArray()); up.getOutputStream().flush()
            pipe(client, up)
        } catch (e: Exception) {
            Log.w("HomeNet", "proxy: " + e.message)
            try { client.close() } catch (_: Exception) { }
        }
    }
}
