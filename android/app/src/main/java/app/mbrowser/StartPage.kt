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

/** The speed dial page: dial tabs along the top, a grid of cards, an Add pill. Dials are bookmark folders. */
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

    init {
        root.findViewById<ImageButton>(R.id.btnTune).setOnClickListener { tuneMenu() }
    }

    private fun extras(): MutableList<String> {
        val a = try { JSONArray(prefs.extraDials) } catch (_: Exception) { JSONArray() }
        return MutableList(a.length()) { a.getString(it) }
    }

    private fun saveExtras(l: List<String>) { prefs.extraDials = JSONArray(l.distinct()).toString() }

    private fun dialNames(): List<String> {
        val def = prefs.defaultDialName
        val others = (Store.topFolders() + extras()).filter { it != def }.distinct().sorted()
        return listOf(def) + others
    }

    private fun currentDial(names: List<String>): String =
        prefs.currentDial.takeIf { it in names } ?: names[0]

    fun refresh() {
        val names = dialNames()
        val cur = currentDial(names)
        val def = prefs.defaultDialName
        tabsRow.removeAllViews()
        for (n in names) {
            val tv = TextView(act)
            tv.text = n
            tv.textSize = 16f
            tv.setPadding(act.dp(14), act.dp(14), act.dp(14), act.dp(12))
            if (n == cur) {
                tv.setTextColor(act.getColor(R.color.accent)); tv.setBackgroundResource(R.drawable.bg_tab_selected)
            } else tv.setTextColor(act.getColor(R.color.muted))
            tv.setOnClickListener { prefs.currentDial = n; refresh() }
            tabsRow.addView(tv)
        }

        val items = ArrayList<Any>(Store.inDial(cur, cur == def, def))
        if (prefs.showAdd) items.add(ADD)
        val cols = prefs.dialCols.coerceIn(2, 5)
        grid.layoutManager = GridLayoutManager(act, cols)
        grid.adapter = Adapter(items, cols, cur)
    }

    // ---------- actions ----------
    private fun folderFor(dial: String): String = if (dial == prefs.defaultDialName) "" else dial

    private fun addItem(dial: String) {
        promptFields(act, "Add to $dial", listOf("Address", "Title (optional)"), listOf("https://", ""), "Add") { v ->
            var url = v[0]
            if (url.isEmpty() || url == "https://") return@promptFields
            if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(url)) url = "https://$url"
            Store.upsert(url, v[1].ifBlank { Thumbs.hostOf(url) }, folderFor(dial))
            refresh()
        }
    }

    private fun itemMenu(b: Bookmark, dial: String) {
        val opts = arrayOf("Open in new tab", "Edit", "Move to another dial", "Remove")
        AlertDialog.Builder(act).setTitle(b.title.ifBlank { b.url }).setItems(opts) { _, i ->
            when (i) {
                0 -> open(b.url, true)
                1 -> promptFields(act, "Edit", listOf("Address", "Title"), listOf(b.url, b.title)) { v ->
                    if (v[0].isNotEmpty()) { Store.upsert(v[0], v[1], b.folder, b.url); refresh() }
                }
                2 -> {
                    val names = dialNames()
                    AlertDialog.Builder(act).setTitle("Move to").setItems(names.toTypedArray()) { _, k ->
                        Store.upsert(b.url, b.title, folderFor(names[k])); refresh()
                    }.show()
                }
                3 -> { Store.removeBookmark(b.url); refresh() }
            }
        }.show()
    }

    private fun tuneMenu() {
        val names = dialNames()
        val cur = currentDial(names)
        val isDef = cur == prefs.defaultDialName
        val opts = arrayListOf(
            "Add speed dial", "Rename “$cur”",
            if (isDef) "Delete dial (not available for the first dial)" else "Delete “$cur”",
            "Columns: ${prefs.dialCols}",
            (if (prefs.showTitles) "✓ " else "") + "Show titles",
            (if (prefs.showThumbs) "✓ " else "") + "Show thumbnails",
            (if (prefs.showAdd) "✓ " else "") + "Show Add button",
            "More settings…",
        )
        AlertDialog.Builder(act).setTitle("Speed dial").setItems(opts.toTypedArray()) { _, i ->
            when (i) {
                0 -> promptFields(act, "New speed dial", listOf("Name"), listOf(""), "Create") { v ->
                    val n = v[0].replace("/", " ").trim()
                    if (n.isNotEmpty() && n !in dialNames()) {
                        saveExtras(extras() + n); prefs.currentDial = n; refresh()
                    }
                }
                1 -> promptFields(act, "Rename dial", listOf("Name"), listOf(cur), "Rename") { v ->
                    val n = v[0].replace("/", " ").trim()
                    if (n.isEmpty() || n == cur || n in dialNames()) return@promptFields
                    if (isDef) prefs.defaultDialName = n
                    else {
                        Store.renameTopFolder(cur, n)
                        saveExtras(extras().map { if (it == cur) n else it })
                    }
                    prefs.currentDial = n; refresh()
                }
                2 -> if (!isDef) AlertDialog.Builder(act)
                    .setMessage("Delete “$cur” and the sites in it?")
                    .setPositiveButton("Delete") { _, _ ->
                        Store.deleteTopFolder(cur); saveExtras(extras().filter { it != cur })
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
                5 -> { prefs.showThumbs = !prefs.showThumbs; refresh() }
                6 -> { prefs.showAdd = !prefs.showAdd; refresh() }
                7 -> openSettings()
            }
        }.show()
    }

    // ---------- grid ----------
    private inner class Adapter(val items: List<Any>, val cols: Int, val dial: String) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
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
            else CardVH(inflater.inflate(R.layout.item_card, parent, false)).also {
                it.card.clipToOutline = true
            }
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            if (h is AddVH) {
                h.itemView.setOnClickListener { addItem(dial) }
                h.itemView.findViewById<View>(R.id.addPill).setOnClickListener { addItem(dial) }
                return
            }
            h as CardVH
            val b = items[pos] as Bookmark
            val host = Thumbs.hostOf(b.url)
            val label = b.title.ifBlank { host.ifEmpty { b.url } }

            // thumbnail height scales with the column count
            val widthPx = act.resources.displayMetrics.widthPixels - act.dp(20 + 12 * cols)
            val th = ((widthPx / cols) * 0.62f).toInt()
            h.thumb.layoutParams.height = th
            h.letter.layoutParams.height = th

            val bmp = if (prefs.showThumbs) Thumbs.thumb(act, host) else null
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

            h.itemView.setOnClickListener { open(b.url, false) }
            h.itemView.setOnLongClickListener { itemMenu(b, dial); true }
        }
    }
}
