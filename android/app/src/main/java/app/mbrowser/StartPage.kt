package app.mbrowser

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

/**
 * The speed dial page: dial tabs along the top, a grid of cards, an Add pill.
 * The dials live in the synced "startPage" settings group, so they match your other devices.
 */
class StartPage(
    private val act: AppCompatActivity,
    val root: View,
    private val open: (url: String, newTab: Boolean) -> Unit,
    private val openSettings: () -> Unit,
) {
    private val prefs = Prefs(act)
    private val tabsRow: LinearLayout = root.findViewById(R.id.dialTabs)
    private val grid: RecyclerView = root.findViewById(R.id.grid)
    private val ADD = Any()

    private class Dial(val title: String, val url: String)

    init {
        root.findViewById<ImageButton>(R.id.btnTune).setOnClickListener { tuneMenu() }
    }

    // ---------- data ----------
    private fun groupsOf(sp: JSONObject): JSONArray = sp.optJSONArray("groups") ?: JSONArray().also { sp.put("groups", it) }

    private fun currentId(sp: JSONObject): String {
        val g = groupsOf(sp)
        val want = prefs.currentDial
        for (i in 0 until g.length()) if (g.getJSONObject(i).optString("id") == want) return want
        return if (g.length() > 0) g.getJSONObject(0).optString("id") else "main"
    }

    private fun dialsOf(sp: JSONObject, id: String): List<Dial> {
        val g = groupsOf(sp)
        for (i in 0 until g.length()) {
            val o = g.getJSONObject(i)
            if (o.optString("id") != id) continue
            val d = o.optJSONArray("dials") ?: return emptyList()
            return (0 until d.length()).map { Dial(d.getJSONObject(it).optString("title"), d.getJSONObject(it).optString("url")) }
        }
        return emptyList()
    }

    /** Reads the startPage group, lets [change] modify it, and saves it (which marks it newer for sync). */
    private fun edit(change: (JSONObject) -> Unit) {
        val sp = Settings.value("startPage")
        change(sp)
        Settings.set("startPage", sp)
    }

    private fun group(sp: JSONObject, id: String): JSONObject? {
        val g = groupsOf(sp)
        for (i in 0 until g.length()) if (g.getJSONObject(i).optString("id") == id) return g.getJSONObject(i)
        return null
    }

    private fun dialArray(g: JSONObject): JSONArray = g.optJSONArray("dials") ?: JSONArray().also { g.put("dials", it) }

    fun refresh() {
        val sp = Settings.value("startPage")
        val cur = currentId(sp)
        tabsRow.removeAllViews()
        val g = groupsOf(sp)
        for (i in 0 until g.length()) {
            val o = g.getJSONObject(i)
            val id = o.optString("id")
            val tv = TextView(act)
            tv.text = o.optString("name", "Speed Dial")
            tv.textSize = 16f
            tv.setPadding(act.dp(14), act.dp(14), act.dp(14), act.dp(12))
            if (id == cur) {
                tv.setTextColor(act.getColor(R.color.accent)); tv.setBackgroundResource(R.drawable.bg_tab_selected)
            } else tv.setTextColor(act.getColor(R.color.muted))
            tv.setOnClickListener { prefs.currentDial = id; refresh() }
            tabsRow.addView(tv)
        }
        val items = ArrayList<Any>(dialsOf(sp, cur))
        if (prefs.showAdd) items.add(ADD)
        val cols = prefs.dialCols.coerceIn(2, 5)
        grid.layoutManager = GridLayoutManager(act, cols)
        grid.adapter = Adapter(items, cols, cur, sp.optBoolean("thumbnails", true))
    }

    // ---------- actions ----------
    private fun addItem(groupId: String) {
        promptFields(act, "Add to speed dial", listOf("Address", "Title (optional)"), listOf("https://", ""), "Add") { v ->
            var url = v[0]
            if (url.isEmpty() || url == "https://") return@promptFields
            if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(url)) url = "https://$url"
            edit { sp -> group(sp, groupId)?.let { dialArray(it).put(JSONObject().put("title", v[1].ifBlank { Thumbs.hostOf(url) }).put("url", url)) } }
            refresh()
        }
    }

    private fun itemMenu(index: Int, d: Dial, groupId: String) {
        val opts = arrayOf("Open in new tab", "Edit", "Move to another dial", "Remove")
        AlertDialog.Builder(act).setTitle(d.title.ifBlank { d.url }).setItems(opts) { _, i ->
            when (i) {
                0 -> open(d.url, true)
                1 -> promptFields(act, "Edit", listOf("Address", "Title"), listOf(d.url, d.title)) { v ->
                    if (v[0].isNotEmpty()) {
                        edit { sp -> group(sp, groupId)?.let { dialArray(it).put(index, JSONObject().put("title", v[1]).put("url", v[0])) } }
                        refresh()
                    }
                }
                2 -> moveItem(index, d, groupId)
                3 -> { removeAt(groupId, index); refresh() }
            }
        }.show()
    }

    private fun removeAt(groupId: String, index: Int) {
        edit { sp -> group(sp, groupId)?.let { g -> val a = dialArray(g); val n = JSONArray()
            for (k in 0 until a.length()) if (k != index) n.put(a.get(k)); g.put("dials", n) } }
    }

    private fun moveItem(index: Int, d: Dial, groupId: String) {
        val sp = Settings.value("startPage")
        val g = groupsOf(sp)
        val ids = ArrayList<String>(); val names = ArrayList<String>()
        for (i in 0 until g.length()) {
            val o = g.getJSONObject(i)
            if (o.optString("id") != groupId) { ids.add(o.optString("id")); names.add(o.optString("name")) }
        }
        if (ids.isEmpty()) return
        AlertDialog.Builder(act).setTitle("Move to").setItems(names.toTypedArray()) { _, k ->
            edit { s ->
                group(s, groupId)?.let { src -> val a = dialArray(src); val n = JSONArray()
                    for (q in 0 until a.length()) if (q != index) n.put(a.get(q)); src.put("dials", n) }
                group(s, ids[k])?.let { dialArray(it).put(JSONObject().put("title", d.title).put("url", d.url)) }
            }
            refresh()
        }.show()
    }

    private fun tuneMenu() {
        val sp = Settings.value("startPage")
        val cur = currentId(sp)
        val curName = group(sp, cur)?.optString("name") ?: "Speed Dial"
        val onlyOne = groupsOf(sp).length() <= 1
        val thumbs = sp.optBoolean("thumbnails", true)
        val opts = arrayOf(
            "Add speed dial", "Rename “$curName”",
            if (onlyOne) "Delete dial (needs at least two)" else "Delete “$curName”",
            "Columns: ${prefs.dialCols}",
            (if (prefs.showTitles) "✓ " else "") + "Show titles",
            (if (thumbs) "✓ " else "") + "Show thumbnails",
            (if (prefs.showAdd) "✓ " else "") + "Show Add button",
            "More settings…",
        )
        AlertDialog.Builder(act).setTitle("Speed dial").setItems(opts) { _, i ->
            when (i) {
                0 -> promptFields(act, "New speed dial", listOf("Name"), listOf(""), "Create") { v ->
                    if (v[0].isEmpty()) return@promptFields
                    val id = "g" + System.currentTimeMillis()
                    edit { s -> groupsOf(s).put(JSONObject().put("id", id).put("name", v[0]).put("dials", JSONArray())) }
                    prefs.currentDial = id; refresh()
                }
                1 -> promptFields(act, "Rename dial", listOf("Name"), listOf(curName), "Rename") { v ->
                    if (v[0].isNotEmpty()) { edit { s -> group(s, cur)?.put("name", v[0]) }; refresh() }
                }
                2 -> if (!onlyOne) AlertDialog.Builder(act).setMessage("Delete “$curName” and the sites in it?")
                    .setPositiveButton("Delete") { _, _ ->
                        edit { s -> val g = groupsOf(s); val n = JSONArray()
                            for (k in 0 until g.length()) if (g.getJSONObject(k).optString("id") != cur) n.put(g.get(k))
                            s.put("groups", n) }
                        prefs.currentDial = ""; refresh()
                    }.setNegativeButton("Cancel", null).show()
                3 -> {
                    val c = arrayOf("2", "3", "4", "5")
                    AlertDialog.Builder(act).setTitle("Columns")
                        .setSingleChoiceItems(c, (prefs.dialCols - 2).coerceIn(0, 3)) { d, k ->
                            prefs.dialCols = k + 2; d.dismiss(); refresh()
                        }.show()
                }
                4 -> { prefs.showTitles = !prefs.showTitles; refresh() }
                5 -> { Settings.setThumbnails(!thumbs); refresh() }
                6 -> { prefs.showAdd = !prefs.showAdd; refresh() }
                7 -> openSettings()
            }
        }.show()
    }

    // ---------- grid ----------
    private inner class Adapter(val items: List<Any>, val cols: Int, val groupId: String, val thumbsOn: Boolean) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val inflater = LayoutInflater.from(act)

        inner class CardVH(v: View) : RecyclerView.ViewHolder(v) {
            val card: View = v.findViewById(R.id.card)
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val letter: TextView = v.findViewById(R.id.letter)
            val icon: ImageView = v.findViewById(R.id.icon)
            val title: TextView = v.findViewById(R.id.title)
            val row: View = v.findViewById(R.id.titleRow)
        }
        inner class AddVH(v: View) : RecyclerView.ViewHolder(v)

        override fun getItemViewType(position: Int) = if (items[position] === ADD) 1 else 0
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 1) AddVH(inflater.inflate(R.layout.item_add, parent, false))
            else CardVH(inflater.inflate(R.layout.item_card, parent, false)).also { it.card.clipToOutline = true }
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            if (h is AddVH) {
                h.itemView.findViewById<View>(R.id.addPill).setOnClickListener { addItem(groupId) }
                return
            }
            h as CardVH
            val d = items[pos] as Dial
            val host = Thumbs.hostOf(d.url)
            val label = d.title.ifBlank { host.ifEmpty { d.url } }

            val widthPx = act.resources.displayMetrics.widthPixels - act.dp(20 + 12 * cols)
            val th = ((widthPx / cols) * 0.62f).toInt()
            h.thumb.layoutParams.height = th
            h.letter.layoutParams.height = th

            val bmp = if (thumbsOn) Thumbs.thumb(act, host) else null
            if (bmp != null) {
                h.thumb.setImageBitmap(bmp); h.thumb.visibility = View.VISIBLE; h.letter.visibility = View.GONE
            } else {
                h.thumb.visibility = View.GONE; h.letter.visibility = View.VISIBLE
                h.letter.text = (label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '•').toString()
                val g = GradientDrawable()
                g.cornerRadius = act.dp(12).toFloat()
                g.setColor(Color.HSVToColor(floatArrayOf(Math.floorMod(host.hashCode(), 360).toFloat(), 0.45f, 0.5f)))
                h.letter.background = g
            }
            val ic = Thumbs.icon(act, host)
            if (ic != null) { h.icon.setImageBitmap(ic); h.icon.imageTintList = null }
            else { h.icon.setImageResource(R.drawable.ic_globe); h.icon.imageTintList = act.getColorStateList(R.color.muted) }
            h.title.text = label
            h.row.visibility = if (prefs.showTitles) View.VISIBLE else View.GONE

            h.itemView.setOnClickListener { open(d.url, false) }
            h.itemView.setOnLongClickListener { itemMenu(pos, d, groupId); true }
        }
    }
}
