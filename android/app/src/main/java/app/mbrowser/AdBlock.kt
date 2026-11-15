package app.mbrowser

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ad blocking with the standard filter lists (EasyList, EasyPrivacy, uBlock filters).
 * Network rules (||host^, /path patterns, $third-party, $script, $domain=, @@ exceptions) drop requests,
 * and element-hiding rules (##.selector) are turned into CSS that is added to each page.
 * Until the lists are loaded, the small built-in domain list in [Blocker] is used.
 */
object AdBlock {
    private val LISTS = listOf(
        "https://easylist.to/easylist/easylist.txt" to "easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt" to "easyprivacy.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt" to "ublock.txt",
    )
    private const val MAX_AGE_MS = 3L * 24 * 3600 * 1000

    const val T_SCRIPT = 1; const val T_IMAGE = 2; const val T_STYLE = 4; const val T_XHR = 8; const val T_SUBDOC = 16
    const val T_MEDIA = 32; const val T_FONT = 64; const val T_OBJECT = 128; const val T_PING = 256; const val T_WS = 512; const val T_OTHER = 1024

    @Volatile private var engine: Engine? = null
    @Volatile var status: String = "Using the small built-in list"
    @Volatile private var busy = false

    // ---------- rules ----------
    private class Rule(
        val pattern: String, val hostAnchor: Boolean, val startAnchor: Boolean, val endAnchor: Boolean,
        val third: Int, val types: Int, val notTypes: Int, val domIn: List<String>, val domOut: List<String>,
        val pure: Boolean,
    ) {
        private var rx: Regex? = null
        fun regex(): Regex {
            rx?.let { return it }
            val sb = StringBuilder()
            if (hostAnchor) sb.append("^[a-z][a-z0-9+.-]*://(?:[^/?#]*\\.)?") else if (startAnchor) sb.append("^")
            for (ch in pattern) {
                when {
                    ch == '*' -> sb.append(".*")
                    ch == '^' -> sb.append("(?:[^a-z0-9_.%-]|$)")
                    ch.isLetterOrDigit() -> sb.append(ch)
                    else -> sb.append('\\').append(ch)
                }
            }
            if (endAnchor) sb.append('$')
            val r = Regex(sb.toString())
            rx = r
            return r
        }
    }

    private class Index {
        val byHost = HashMap<String, MutableList<Rule>>()
        val byToken = HashMap<String, MutableList<Rule>>()
        val loose = ArrayList<Rule>()
        var count = 0
    }

    private class Engine(val block: Index, val allow: Index, val generic: HashMap<String, MutableList<String>>,
                         val always: List<String>, val byDomain: HashMap<String, MutableList<Pair<String, List<String>>>>,
                         val exceptions: HashMap<String, MutableList<String>>, val hideCount: Int)

    // ---------- parsing ----------
    private val OPT_OK = Regex("^[~a-zA-Z0-9_,=|.\\-]+$")
    private val BAD_PSEUDO = listOf(":-abp", ":has-text", ":xpath", ":matches", ":upward", ":remove", ":style", ":nth-ancestor", ":min-text", ":watch", ":contains(")
    private val TOKEN = Regex("[a-z0-9]+")

    private fun typeBit(n: String): Int = when (n) {
        "script" -> T_SCRIPT; "image" -> T_IMAGE; "stylesheet", "css" -> T_STYLE; "xmlhttprequest", "xhr" -> T_XHR
        "subdocument", "frame" -> T_SUBDOC; "media" -> T_MEDIA; "font" -> T_FONT; "object" -> T_OBJECT
        "ping" -> T_PING; "websocket" -> T_WS; "other" -> T_OTHER; else -> 0
    }

    private fun parseNetwork(line0: String): Pair<Boolean, Rule>? {
        var l = line0
        var exception = false
        if (l.startsWith("@@")) { exception = true; l = l.substring(2) }
        if (l.length > 1 && l.startsWith("/") && l.endsWith("/")) return null   // regular expression rules: skipped
        var third = 0; var types = 0; var notTypes = 0
        val domIn = ArrayList<String>(); val domOut = ArrayList<String>()
        val dollar = l.lastIndexOf('$')
        if (dollar >= 0) {
            val opts = l.substring(dollar + 1)
            if (OPT_OK.matches(opts)) {
                l = l.substring(0, dollar)
                for (o in opts.split(',')) {
                    val neg = o.startsWith("~")
                    val n = if (neg) o.substring(1) else o
                    when {
                        n == "third-party" || n == "3p" -> third = if (neg) 2 else 1
                        n == "first-party" || n == "1p" -> third = if (neg) 1 else 2
                        n.startsWith("domain=") -> for (d in n.substring(7).split('|')) {
                            if (d.isEmpty()) continue
                            if (d.startsWith("~")) domOut.add(d.substring(1).lowercase()) else domIn.add(d.lowercase())
                        }
                        n == "important" || n == "match-case" || n == "collapse" -> {}
                        typeBit(n) != 0 -> if (neg) notTypes = notTypes or typeBit(n) else types = types or typeBit(n)
                        else -> return null   // an option this blocker doesn't understand: skip the rule rather than guess
                    }
                }
            }
        }
        var hostAnchor = false; var startAnchor = false; var endAnchor = false
        if (l.startsWith("||")) { hostAnchor = true; l = l.substring(2) }
        else if (l.startsWith("|")) { startAnchor = true; l = l.substring(1) }
        if (l.endsWith("|")) { endAnchor = true; l = l.substring(0, l.length - 1) }
        l = l.lowercase()
        if (l.isEmpty()) return null
        var pure = false
        if (hostAnchor) {
            val m = Regex("^[a-z0-9.-]+").find(l)
            if (m != null) {
                val rest = l.substring(m.value.length)
                pure = (rest.isEmpty() || rest == "^") && !endAnchor
            }
        }
        return exception to Rule(l, hostAnchor, startAnchor, endAnchor, third, types, notTypes, domIn, domOut, pure)
    }

    private fun addRule(ix: Index, r: Rule) {
        ix.count++
        if (r.hostAnchor) {
            val m = Regex("^[a-z0-9.-]+").find(r.pattern)
            if (m != null) {
                val after = r.pattern.getOrNull(m.value.length)
                if (after == null || after == '/' || after == '^' || after == '?' || after == ':') {
                    ix.byHost.getOrPut(m.value) { ArrayList() }.add(r); return
                }
            }
        }
        // otherwise find a whole word in the pattern that every matching address must contain
        var best: String? = null
        for (m in TOKEN.findAll(r.pattern)) {
            val s = m.range.first; val e = m.range.last + 1
            if (m.value.length < 3) continue
            val startOk = if (s == 0) (r.hostAnchor || r.startAnchor) else r.pattern[s - 1] != '*'
            val endOk = if (e >= r.pattern.length) r.endAnchor else r.pattern[e] != '*'
            if (startOk && endOk && (best == null || m.value.length > best.length)) best = m.value
        }
        if (best != null) ix.byToken.getOrPut(best) { ArrayList() }.add(r) else ix.loose.add(r)
    }

    private fun build(ctx: Context): Engine? {
        val dir = File(ctx.filesDir, "adblock")
        val files = LISTS.map { File(dir, it.second) }.filter { it.exists() }
        if (files.isEmpty()) return null
        val block = Index(); val allow = Index()
        val generic = HashMap<String, MutableList<String>>()
        val always = ArrayList<String>()
        val byDomain = HashMap<String, MutableList<Pair<String, List<String>>>>()
        val exceptions = HashMap<String, MutableList<String>>()
        var hides = 0
        for (f in files) {
            f.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line[0] == '!' || line[0] == '[') continue
                    val ex = line.indexOf("#@#")
                    if (ex >= 0 && !line.contains("#@#+js")) {
                        val sel = line.substring(ex + 3)
                        val doms = line.substring(0, ex).split(',').filter { it.isNotEmpty() }.map { it.lowercase() }
                        exceptions.getOrPut(sel) { ArrayList() }.addAll(if (doms.isEmpty()) listOf("*") else doms)
                        continue
                    }
                    if (line.contains("#?#") || line.contains("#\$#") || line.contains("#%#")) continue
                    val h = line.indexOf("##")
                    if (h >= 0) {
                        val sel = line.substring(h + 2)
                        if (sel.isEmpty() || sel.startsWith("+js") || BAD_PSEUDO.any { sel.contains(it) } || sel.contains('{')) continue
                        val doms = line.substring(0, h).split(',').filter { it.isNotEmpty() }.map { it.lowercase() }
                        val pos = doms.filter { !it.startsWith("~") }
                        val neg = doms.filter { it.startsWith("~") }.map { it.substring(1) }
                        hides++
                        if (pos.isEmpty()) {
                            val m = Regex("^[#.][A-Za-z0-9_-]+").find(sel)
                            if (m != null && neg.isEmpty()) generic.getOrPut(m.value) { ArrayList() }.add(sel)
                            else if (always.size < 2000 && neg.isEmpty()) always.add(sel)
                        } else for (d in pos) byDomain.getOrPut(d) { ArrayList() }.add(sel to neg)
                        continue
                    }
                    val r = parseNetwork(line) ?: continue
                    addRule(if (r.first) allow else block, r.second)
                }
            }
        }
        return Engine(block, allow, generic, always, byDomain, exceptions, hides)
    }

    // ---------- matching ----------
    private fun suffixes(host: String): List<String> {
        val out = ArrayList<String>()
        var h = host
        while (true) {
            out.add(h)
            val i = h.indexOf('.')
            if (i < 0 || i == h.length - 1) break
            h = h.substring(i + 1)
        }
        return out
    }

    private fun regDomain(host: String): String {
        val l = host.split('.')
        if (l.size <= 2) return host
        val tld = l.last(); val sld = l[l.size - 2]
        val n = if (tld.length == 2 && sld in setOf("co", "com", "org", "net", "gov", "ac", "edu")) 3 else 2
        return l.takeLast(n).joinToString(".")
    }

    private fun matchesDomains(r: Rule, pageHost: String): Boolean {
        if (r.domIn.isEmpty() && r.domOut.isEmpty()) return true
        val sfx = suffixes(pageHost)
        if (r.domOut.any { it in sfx }) return false
        return r.domIn.isEmpty() || r.domIn.any { it in sfx }
    }

    private fun applies(r: Rule, type: Int, third: Boolean, pageHost: String): Boolean {
        if (r.third == 1 && !third) return false
        if (r.third == 2 && third) return false
        if (r.types != 0 && (type == 0 || r.types and type == 0)) return false
        if (r.notTypes != 0 && type != 0 && r.notTypes and type != 0) return false
        return matchesDomains(r, pageHost)
    }

    private fun matches(ix: Index, url: String, host: String, type: Int, third: Boolean, pageHost: String): Boolean {
        for (s in suffixes(host)) {
            val l = ix.byHost[s] ?: continue
            for (r in l) if (applies(r, type, third, pageHost) && (r.pure || r.regex().containsMatchIn(url))) return true
        }
        val seen = HashSet<String>()
        for (m in TOKEN.findAll(url)) {
            val t = m.value
            if (t.length < 3 || !seen.add(t)) continue
            val l = ix.byToken[t] ?: continue
            for (r in l) if (applies(r, type, third, pageHost) && r.regex().containsMatchIn(url)) return true
        }
        for (r in ix.loose) if (applies(r, type, third, pageHost) && r.regex().containsMatchIn(url)) return true
        return false
    }

    /** True when the request should be dropped. [type] is one of the T_ values, or 0 when unknown. */
    fun shouldBlock(url: String, pageHost: String?, type: Int): Boolean {
        val e = engine
        val reqHost = try { java.net.URI(url).host?.lowercase() } catch (_: Exception) { null } ?: return false
        val page = pageHost?.lowercase() ?: ""
        if (e == null) return Blocker.shouldBlock(reqHost, page)
        val third = page.isNotEmpty() && regDomain(reqHost) != regDomain(page)
        val u = url.lowercase()
        if (e.allow.count > 0 && matches(e.allow, u, reqHost, type, third, page)) return false
        return matches(e.block, u, reqHost, type, third, page)
    }

    // ---------- hiding ----------
    /** CSS that hides ads on [host]. [first] adds the domain rules and the always-on rules; later calls only look up new ids and classes. */
    fun cosmeticCss(host: String, ids: List<String>, classes: List<String>, first: Boolean): String {
        val e = engine ?: return ""
        val h = host.lowercase()
        val sfx = suffixes(h)
        val out = LinkedHashSet<String>()
        fun allowed(sel: String): Boolean {
            val ex = e.exceptions[sel] ?: return true
            return !(ex.contains("*") || ex.any { it in sfx })
        }
        if (first) {
            for (s in sfx) e.byDomain[s]?.forEach { (sel, neg) -> if (neg.none { it in sfx } && allowed(sel)) out.add(sel) }
            for (sel in e.always) if (allowed(sel)) out.add(sel)
        }
        for (i in ids) e.generic["#$i"]?.forEach { if (allowed(it)) out.add(it) }
        for (c in classes) e.generic[".$c"]?.forEach { if (allowed(it)) out.add(it) }
        if (out.isEmpty()) return ""
        val sb = StringBuilder()
        for (sel in out) sb.append(sel).append("{display:none!important}\n")
        return sb.toString()
    }

    // ---------- lists ----------
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            reload(app)
            updateIfStale(app)
        }.start()
    }

    private fun reload(ctx: Context) {
        try {
            val e = build(ctx)
            engine = e
            if (e != null) status = "${e.block.count + e.allow.count} blocking rules and ${e.hideCount} hiding rules" + updatedText(ctx)
        } catch (t: Throwable) { engine = null; status = "Could not read the filter lists: ${t.message}" }
    }

    private fun updatedText(ctx: Context): String {
        val f = File(File(ctx.filesDir, "adblock"), LISTS[0].second)
        if (!f.exists()) return ""
        return " · updated " + android.text.format.DateFormat.getMediumDateFormat(ctx).format(java.util.Date(f.lastModified()))
    }

    private fun updateIfStale(ctx: Context) {
        val f = File(File(ctx.filesDir, "adblock"), LISTS[0].second)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS) download(ctx)
    }

    /** Downloads the lists (blocking) and reloads the rules. Returns a message. */
    fun download(ctx: Context): String {
        if (busy) return "Already updating"
        busy = true
        try {
            val dir = File(ctx.filesDir, "adblock").also { it.mkdirs() }
            var ok = 0
            for ((url, name) in LISTS) {
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 60000
                    c.setRequestProperty("User-Agent", "mbrowser-android")
                    if (c.responseCode !in 200..299) { c.disconnect(); continue }
                    val tmp = File(dir, "$name.tmp")
                    c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    c.disconnect()
                    if (tmp.length() > 10_000) { tmp.renameTo(File(dir, name)); ok++ } else tmp.delete()
                } catch (_: Exception) { }
            }
            if (ok == 0) return "Could not download the filter lists. Check your connection."
            reload(ctx)
            return "Filter lists updated ($ok of ${LISTS.size}). $status"
        } finally { busy = false }
    }
}
