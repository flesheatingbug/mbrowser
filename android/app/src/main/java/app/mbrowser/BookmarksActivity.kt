package app.mbrowser

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The bookmark manager: browse folders and sub-folders, search, sort, select several to move or delete, edit one,
 * and bring back deleted bookmarks from the recycle bin (kept 30 days).
 */
class BookmarksActivity : BaseActivity() {
    private class Item(val folder: String?, val bm: Bookmark?, val title: String, val sub: String)

    private var path = ""
    private var query = ""
    private var sort = 0               // 0 newest, 1 title, 2 address, 3 oldest
    private var bin = false
    private val selected = LinkedHashSet<String>()
    private var items: List<Item> = emptyList()

    private lateinit var titleView: TextView
    private lateinit var normalBar: LinearLayout
    private lateinit var selectBar: LinearLayout
    private lateinit var selCount: TextView
    private lateinit var searchBox: EditText
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var binBtn: TextView
    private lateinit var moveBtn: TextView
    private lateinit var editBtn: TextView
    private lateinit var delBtn: TextView

    private fun dpi(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(attr: Int): Int { val tv = TypedValue(); theme.resolveAttribute(attr, tv, true); return tv.data }

    private fun textBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(col(R.attr.mbAccent)); setPadding(dpi(12), dpi(10), dpi(12), dpi(10))
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.attr.mbBg)); fitsSystemWindows = true }

        // top bar: normal mode
        normalBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(col(R.attr.mbBar)); setPadding(dpi(6), dpi(4), dpi(6), dpi(4)) }
        val back = ImageView(this).apply { setImageResource(R.drawable.ic_back); setColorFilter(col(R.attr.mbText), PorterDuff.Mode.SRC_IN); setPadding(dpi(12), dpi(12), dpi(12), dpi(12)); setOnClickListener { goBack() } }
        titleView = TextView(this).apply { textSize = 18f; setTextColor(col(R.attr.mbText)); setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.START }
        binBtn = textBtn("Bin") { bin = !bin; path = ""; selected.clear(); refresh() }
        normalBar.addView(back, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        normalBar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        normalBar.addView(textBtn("Sort") { pickSort() })
        normalBar.addView(binBtn)

        // top bar: selection mode
        selectBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(col(R.attr.mbBar)); setPadding(dpi(6), dpi(4), dpi(6), dpi(4)); visibility = View.GONE }
        val close = ImageView(this).apply { setImageResource(R.drawable.ic_close); setColorFilter(col(R.attr.mbText), PorterDuff.Mode.SRC_IN); setPadding(dpi(12), dpi(12), dpi(12), dpi(12)); setOnClickListener { selected.clear(); refresh() } }
        selCount = TextView(this).apply { textSize = 17f; setTextColor(col(R.attr.mbText)) }
        moveBtn = textBtn("Move") { pickFolderForMove() }
        editBtn = textBtn("Edit") { editSelected() }
        delBtn = textBtn("Delete") { deleteOrRestoreSelected() }
        selectBar.addView(close, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        selectBar.addView(selCount, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selectBar.addView(textBtn("All") { selectAll() })
        selectBar.addView(moveBtn); selectBar.addView(editBtn); selectBar.addView(delBtn)

        searchBox = EditText(this).apply {
            hint = "Search bookmarks"; setHintTextColor(col(R.attr.mbMuted)); setTextColor(col(R.attr.mbText)); textSize = 15f
            setBackgroundResource(R.drawable.bg_pill); setPadding(dpi(18), 0, dpi(18), 0); maxLines = 1; inputType = InputType.TYPE_CLASS_TEXT
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim() ?: ""; refresh() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@BookmarksActivity); adapter = Adapter(); clipToPadding = false; setPadding(0, 0, 0, dpi(24)) }
        empty = TextView(this).apply { textSize = 15f; setTextColor(col(R.attr.mbMuted)); gravity = Gravity.CENTER; setPadding(dpi(24), dpi(40), dpi(24), dpi(24)) }

        root.addView(normalBar); root.addView(selectBar)
        root.addView(searchBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48)).apply { setMargins(dpi(10), dpi(10), dpi(10), dpi(6)) })
        root.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) { override fun handleOnBackPressed() { goBack() } })
        refresh()
    }

    private fun goBack() {
        when {
            selected.isNotEmpty() -> { selected.clear(); refresh() }
            bin -> { bin = false; refresh() }
            query.isNotEmpty() -> searchBox.setText("")
            path.isNotEmpty() -> { path = path.substringBeforeLast('/', ""); refresh() }
            else -> finish()
        }
    }

    // ---------- building the list ----------
    private fun compare(a: Bookmark, b: Bookmark): Int = when (sort) {
        1 -> a.title.compareTo(b.title, ignoreCase = true)
        2 -> a.url.compareTo(b.url, ignoreCase = true)
        3 -> a.added.compareTo(b.added)
        else -> b.updated.compareTo(a.updated)
    }

    private fun sub(b: Bookmark, showFolder: Boolean) = (if (showFolder && b.folder.isNotEmpty()) b.folder + "  ·  " else "") + b.url +
        (if (b.added > 0) "  ·  " + android.text.format.DateFormat.getMediumDateFormat(this).format(java.util.Date(b.added)) else "")

    private fun build(): List<Item> {
        if (bin) return Store.binList().filter { matches(it) }.map { Item(null, it, it.title.ifBlank { it.url }, sub(it, true)) }
        val all = Store.liveBookmarks()
        if (query.isNotEmpty()) return all.filter { matches(it) }.sortedWith { a, b -> compare(a, b) }.map { Item(null, it, it.title.ifBlank { it.url }, sub(it, true)) }
        val out = ArrayList<Item>()
        val prefix = if (path.isEmpty()) "" else "$path/"
        val folders = LinkedHashMap<String, Int>()
        for (b in all) {
            if (b.folder.isEmpty() || !(prefix.isEmpty() || b.folder.startsWith(prefix))) continue
            val rest = b.folder.removePrefix(prefix)
            if (rest.isEmpty()) continue
            val child = prefix + rest.substringBefore('/')
            folders[child] = (folders[child] ?: 0) + 1
        }
        for ((f, n) in folders.entries.sortedBy { it.key.lowercase() }) out.add(Item(f, null, f.substringAfterLast('/'), "$n bookmark" + (if (n == 1) "" else "s")))
        for (b in all.filter { it.folder == path }.sortedWith { a, b -> compare(a, b) }) out.add(Item(null, b, b.title.ifBlank { b.url }, sub(b, false)))
        return out
    }

    private fun matches(b: Bookmark): Boolean {
        if (query.isEmpty()) return true
        val q = query.lowercase()
        return b.title.lowercase().contains(q) || b.url.lowercase().contains(q) || b.folder.lowercase().contains(q)
    }

    private fun refresh() {
        items = build()
        selected.retainAll(items.mapNotNull { it.bm?.url }.toSet())
        val sel = selected.isNotEmpty()
        normalBar.visibility = if (sel) View.GONE else View.VISIBLE
        selectBar.visibility = if (sel) View.VISIBLE else View.GONE
        selCount.text = "${selected.size} selected"
        editBtn.visibility = if (selected.size == 1 && !bin) View.VISIBLE else View.GONE
        moveBtn.visibility = if (bin) View.GONE else View.VISIBLE
        delBtn.text = if (bin) "Restore" else "Delete"
        binBtn.text = if (bin) "Done" else "Bin"
        titleView.text = when { bin -> "Recycle bin (30 days)"; query.isNotEmpty() -> "Search results"; path.isEmpty() -> "Bookmarks"; else -> path.replace("/", "  ›  ") }
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when { bin -> "The bin is empty."; query.isNotEmpty() -> "Nothing matches."; else -> "No bookmarks here yet." }
        list.adapter?.notifyDataSetChanged()
    }

    // ---------- actions ----------
    private fun open(url: String) { setResult(Activity.RESULT_OK, Intent().putExtra("url", url)); finish() }

    private fun toggle(url: String) { if (!selected.remove(url)) selected.add(url); refresh() }

    private fun selectAll() { for (i in items) i.bm?.let { selected.add(it.url) }; refresh() }

    private fun pickSort() {
        val names = arrayOf("Newest first", "Title (A–Z)", "Address (A–Z)", "Oldest first")
        AlertDialog.Builder(this).setTitle("Sort by").setSingleChoiceItems(names, sort) { d, w -> sort = w; d.dismiss(); refresh() }.show()
    }

    private fun deleteOrRestoreSelected() {
        val urls = selected.toList()
        if (urls.isEmpty()) return
        if (bin) { Store.restoreMany(urls); Toast.makeText(this, "Restored ${urls.size}", Toast.LENGTH_SHORT).show(); selected.clear(); refresh(); return }
        AlertDialog.Builder(this).setTitle("Delete ${urls.size} bookmark" + (if (urls.size == 1) "" else "s") + "?")
            .setMessage("They go to the recycle bin for 30 days.")
            .setPositiveButton("Delete") { _, _ -> Store.deleteMany(urls); selected.clear(); refresh() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun foldersAll(): List<String> {
        val set = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)
        for (b in Store.liveBookmarks()) {
            var p = b.folder
            while (p.isNotEmpty()) { set.add(p); p = p.substringBeforeLast('/', "") }
        }
        return set.toList()
    }

    private fun pickFolderForMove() {
        val urls = selected.toList()
        if (urls.isEmpty()) return
        val folders = foldersAll()
        val names = ArrayList<String>(); names.add("Top level (no folder)"); names.addAll(folders); names.add("＋ New folder…")
        AlertDialog.Builder(this).setTitle("Move ${urls.size} to").setItems(names.toTypedArray()) { _, w ->
            when (w) {
                0 -> doMove(urls, "")
                names.size - 1 -> askText("New folder", "Folder name (use / for a sub-folder)", if (path.isEmpty()) "" else "$path/") { doMove(urls, it) }
                else -> doMove(urls, folders[w - 1])
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun doMove(urls: List<String>, folder: String) {
        Store.moveMany(urls, folder); selected.clear(); refresh()
        Toast.makeText(this, "Moved", Toast.LENGTH_SHORT).show()
    }

    private fun askText(title: String, hint: String, initial: String, ok: (String) -> Unit) {
        val et = EditText(this).apply { setText(initial); setSelection(initial.length); this.hint = hint; setTextColor(col(R.attr.mbText)); inputType = InputType.TYPE_CLASS_TEXT }
        val box = LinearLayout(this).apply { setPadding(dpi(20), dpi(8), dpi(20), 0); addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> ok(et.text.toString().trim()) }.setNegativeButton("Cancel", null).show()
    }

    private fun editSelected() {
        val u = selected.firstOrNull() ?: return
        val b = Store.liveBookmarks().firstOrNull { it.url == u } ?: return
        fun field(hint: String, v: String) = EditText(this).apply { setText(v); this.hint = hint; setTextColor(col(R.attr.mbText)); inputType = InputType.TYPE_CLASS_TEXT; maxLines = 3 }
        val t = field("Title", b.title); val a = field("Address", b.url); val f = field("Folder (a/b for sub-folders)", b.folder)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(20), dpi(8), dpi(20), 0); addView(t); addView(a); addView(f) }
        AlertDialog.Builder(this).setTitle("Edit bookmark").setView(box).setPositiveButton("Save") { _, _ ->
            val url = a.text.toString().trim()
            if (url.isEmpty()) { Toast.makeText(this, "The address can't be empty", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
            Store.upsert(url, t.text.toString().trim(), Store.cleanPath(f.text.toString()), b.url)
            selected.clear(); refresh()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun folderMenu(folder: String) {
        AlertDialog.Builder(this).setTitle(folder.substringAfterLast('/')).setItems(arrayOf("Rename or move folder", "Delete folder and its bookmarks")) { _, w ->
            if (w == 0) askText("Rename folder", "New path", folder) { n -> if (n.isNotEmpty() && Store.cleanPath(n) != folder) { Store.renameFolder(folder, n); refresh() } }
            else AlertDialog.Builder(this).setTitle("Delete this folder?").setMessage("Everything inside goes to the recycle bin for 30 days.")
                .setPositiveButton("Delete") { _, _ -> Store.deleteFolder(folder); refresh() }.setNegativeButton("Cancel", null).show()
        }.show()
    }

    // ---------- list adapter ----------
    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val row = LinearLayout(this@BookmarksActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(16), dpi(10), dpi(12), dpi(10))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val icon = ImageView(this@BookmarksActivity).apply { id = View.generateViewId(); setColorFilter(col(R.attr.mbMuted), PorterDuff.Mode.SRC_IN) }
            val texts = LinearLayout(this@BookmarksActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(14), 0, 0, 0) }
            val t1 = TextView(this@BookmarksActivity).apply { textSize = 14f; setTextColor(col(R.attr.mbText)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val t2 = TextView(this@BookmarksActivity).apply { textSize = 11f; setTextColor(col(R.attr.mbMuted)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            texts.addView(t1); texts.addView(t2)
            row.addView(icon, LinearLayout.LayoutParams(dpi(24), dpi(24)))
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.tag = arrayOf(icon, t1, t2)
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val item = items[pos]
            @Suppress("UNCHECKED_CAST") val v = h.itemView.tag as Array<View>
            val icon = v[0] as ImageView; val t1 = v[1] as TextView; val t2 = v[2] as TextView
            t1.text = item.title; t2.text = item.sub
            val bm = item.bm
            val isSel = bm != null && bm.url in selected
            h.itemView.setBackgroundColor(if (isSel) (col(R.attr.mbAccent) and 0x00FFFFFF) or 0x33000000 else Color.TRANSPARENT)
            icon.setImageResource(when { isSel -> R.drawable.ic_star_filled; item.folder != null -> R.drawable.ic_folder; else -> R.drawable.ic_star })
            val fol = item.folder
            if (fol != null) {
                h.itemView.setOnClickListener { path = fol; refresh() }
                h.itemView.setOnLongClickListener { folderMenu(fol); true }
            } else if (bm != null) {
                h.itemView.setOnClickListener { if (selected.isNotEmpty() || bin) toggle(bm.url) else open(bm.url) }
                h.itemView.setOnLongClickListener { toggle(bm.url); true }
            }
        }
    }
}
