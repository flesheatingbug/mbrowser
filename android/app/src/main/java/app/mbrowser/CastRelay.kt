package app.mbrowser

import android.webkit.CookieManager
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * A tiny web server on the phone. A Chromecast can't fetch many streams itself (no login cookies, wrong referer,
 * no cross-origin permission), so it fetches them from here and the phone fetches them from the site the way the
 * browser would. HLS playlists are rewritten so every segment also comes through here.
 */
class CastRelay(@Volatile var userAgent: String) {
    @Volatile var referer: String = ""
    private var server: ServerSocket? = null
    private val events = java.util.concurrent.ConcurrentLinkedDeque<String>()
    /** The most recent things that happened, newest last, for the "Show what was sent" screen. */
    fun log(): String = events.joinToString("\n").ifEmpty { "The TV hasn't asked the phone for anything yet." }
    private fun note(s: String) { events.add(s); while (events.size > 12) events.pollFirst() }
    private fun short(u: String) = try { val x = URI(u); x.host + x.path.takeLast(28) } catch (_: Exception) { u.take(40) }
    private val pool = Executors.newCachedThreadPool()

    private fun localIp(): String? {
        val ips = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        for (i in ips) for (a in i.inetAddresses) if (!a.isLoopbackAddress && a.address.size == 4 && a.isSiteLocalAddress) return a.hostAddress
        return null
    }

    /** The address the TV should use for [url], or null if the phone has no network address to offer. */
    @Synchronized fun wrap(url: String): String? {
        val ip = localIp() ?: return null
        if (server == null || server!!.isClosed) {
            val s = ServerSocket(0); server = s
            pool.execute { while (!s.isClosed) { try { val c = s.accept(); pool.execute { handle(c) } } catch (_: Exception) { } } }
        }
        return "http://$ip:${server!!.localPort}/p?u=" + URLEncoder.encode(url, "UTF-8")
    }

    private fun self(url: String): String {
        val s = server ?: return url
        return "http://${localIp()}:${s.localPort}/p?u=" + URLEncoder.encode(url, "UTF-8")
    }

    fun stop() { try { server?.close() } catch (_: Exception) { }; server = null }

    private fun readLine(i: InputStream): String? {
        val b = StringBuilder()
        while (true) { val c = i.read(); if (c < 0) return if (b.isEmpty()) null else b.toString(); if (c == '\n'.code) break; if (c != '\r'.code) b.append(c.toChar()) }
        return b.toString()
    }

    private fun cors(o: StringBuilder) {
        o.append("Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\nAccess-Control-Expose-Headers: *\r\n")
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 30000
            val inp = BufferedInputStream(sock.getInputStream()); val out = sock.getOutputStream()
            val req = readLine(inp) ?: return
            note("TV asked: ${req.take(60)}")
            var range: String? = null
            while (true) { val l = readLine(inp) ?: break; if (l.isEmpty()) break; if (l.startsWith("Range:", true)) range = l.substringAfter(':').trim() }
            val parts = req.split(' ')
            if (parts.size < 2) return
            if (parts[0] == "OPTIONS") { val h = StringBuilder("HTTP/1.1 204 No Content\r\n"); cors(h); h.append("Connection: close\r\n\r\n"); out.write(h.toString().toByteArray()); return }
            val target = android.net.Uri.parse("http://x" + parts[1]).getQueryParameter("u")
            if (target == null) { out.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray()); return }
            fun open(): HttpURLConnection {
                val x = URL(target).openConnection() as HttpURLConnection
                x.connectTimeout = 10000; x.readTimeout = 30000; x.instanceFollowRedirects = true
                x.setRequestProperty("User-Agent", userAgent)
                x.setRequestProperty("Accept", "*/*")
                x.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                if (referer.isNotEmpty()) {
                    x.setRequestProperty("Referer", referer)
                    try { val u = URI(referer); x.setRequestProperty("Origin", "${u.scheme}://${u.authority}") } catch (_: Exception) { }
                }
                CookieManager.getInstance().getCookie(target)?.let { x.setRequestProperty("Cookie", it) }
                if (range != null) x.setRequestProperty("Range", range)
                return x
            }
            var c = open()
            var code = c.responseCode
            var tries = 0
            // some sites allow one viewer per stream; the page was just playing it, so wait for that session to end
            while (code == 403 && tries < 5) {
                val msg = try { c.errorStream?.readBytes()?.toString(Charsets.UTF_8) ?: "" } catch (_: Exception) { "" }
                note("site answered 403 for ${short(target)}: " + msg.take(60).replace(Regex("\\s+"), " "))
                if (!msg.contains("duplicat", true)) break
                tries++; Thread.sleep(2000)
                c = open(); code = c.responseCode
            }
            note("site answered $code for ${short(target)}" + if (tries > 0) " after $tries retries" else "")
            val body: InputStream? = if (code >= 400) c.errorStream else c.inputStream
            val ctype = c.contentType ?: ""
            val isPlaylist = target.substringBefore('?').endsWith(".m3u8", true) || ctype.contains("mpegurl", true)
            val h = StringBuilder()
            if (isPlaylist && body != null) {
                val text = body.readBytes().toString(Charsets.UTF_8)
                val base = c.url.toString()
                val rewritten = text.lines().joinToString("\n") { line ->
                    when {
                        line.startsWith("#") -> Regex("URI=\"([^\"]+)\"").replace(line) { m -> "URI=\"" + self(resolve(base, m.groupValues[1])) + "\"" }
                        line.isBlank() -> line
                        else -> self(resolve(base, line.trim()))
                    }
                }.toByteArray()
                h.append("HTTP/1.1 $code OK\r\nContent-Type: application/x-mpegURL\r\nContent-Length: ${rewritten.size}\r\nCache-Control: no-cache\r\nConnection: close\r\n"); cors(h); h.append("\r\n")
                out.write(h.toString().toByteArray()); out.write(rewritten)
            } else {
                h.append("HTTP/1.1 $code OK\r\n")
                if (ctype.isNotEmpty()) h.append("Content-Type: $ctype\r\n")
                if (c.contentLengthLong >= 0) h.append("Content-Length: ${c.contentLengthLong}\r\n")
                c.getHeaderField("Content-Range")?.let { h.append("Content-Range: $it\r\n") }
                h.append("Accept-Ranges: bytes\r\nConnection: close\r\n"); cors(h); h.append("\r\n")
                out.write(h.toString().toByteArray())
                body?.copyTo(out)
            }
            out.flush()
        } catch (e: Exception) {
            note("failed: ${e.javaClass.simpleName} ${e.message ?: ""}".take(120))
        } finally { try { sock.close() } catch (_: Exception) { } }
    }

    private fun resolve(base: String, ref: String): String = try { URI(base).resolve(ref).toString() } catch (_: Exception) { ref }
}
