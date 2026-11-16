package app.mbrowser

import android.app.Activity
import android.app.DatePickerDialog
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Browsing history grouped by day: search, filter by day or date range (with a date picker), select several to remove,
 * and bring removed entries back from the recycle bin (kept 30 days). Removing history only affects this phone.
 */
class HistoryActivity : BaseActivity() {
    private class Row(val header: String?, val visit: Visit?, val key: String)

    private var query = ""
    private var from = 0L
    private var to = Long.MAX_VALUE
    private var filterLabel = "All time"
    private var bin = false
    private val selected = LinkedHashSet<String>()
    private var rows: List<Row> = emptyList()
    private var shownKeys: List<String> = emptyList()      // every entry matching the search and filter, across all pages
    private var pageKeys: List<String> = emptyList()       // the entries on the page being shown
    private var page = 0
    private val PAGE_SIZE = 30

    private lateinit var titleView: TextView
    private lateinit var normalBar: LinearLayout
    private lateinit var selectBar: LinearLayout
    private lateinit var selCount: TextView
    private lateinit var searchBox: EditText
    private lateinit var filterChip: TextView
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var binBtn: TextView
    private lateinit var clearBtn: TextView
    private lateinit var actBtn: TextView
    private lateinit var allBox: CheckBox
    private lateinit var pageInfo: TextView
    private lateinit var prevBtn: TextView
    private lateinit var nextBtn: TextView
    private lateinit var pagerLabel: TextView

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

        normalBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(col(R.attr.mbBar)); setPadding(dpi(6), dpi(4), dpi(6), dpi(4)) }
        val back = ImageView(this).apply { setImageResource(R.drawable.ic_back); setColorFilter(col(R.attr.mbText), PorterDuff.Mode.SRC_IN); setPadding(dpi(12), dpi(12), dpi(12), dpi(12)); setOnClickListener { goBack() } }
        titleView = TextView(this).apply { textSize = 18f; setTextColor(col(R.attr.mbText)); setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 1 }
        clearBtn = textBtn("Clear") { clearShown() }
        binBtn = textBtn("Bin") { bin = !bin; selected.clear(); refresh() }
        normalBar.addView(back, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        normalBar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        normalBar.addView(clearBtn); normalBar.addView(binBtn)

        selectBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(col(R.attr.mbBar)); setPadding(dpi(6), dpi(4), dpi(6), dpi(4)); visibility = View.GONE }
        val close = ImageView(this).apply { setImageResource(R.drawable.ic_close); setColorFilter(col(R.attr.mbText), PorterDuff.Mode.SRC_IN); setPadding(dpi(12), dpi(12), dpi(12), dpi(12)); setOnClickListener { selected.clear(); refresh() } }
        selCount = TextView(this).apply { textSize = 17f; setTextColor(col(R.attr.mbText)) }
        actBtn = textBtn("Delete") { actOnSelected() }
        selectBar.addView(close, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        selectBar.addView(selCount, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selectBar.addView(textBtn("Page") { selected.addAll(pageKeys); refresh() })
        selectBar.addView(actBtn)

        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        searchBox = EditText(this).apply {
            hint = "Search history"; setHintTextColor(col(R.attr.mbMuted)); setTextColor(col(R.attr.mbText)); textSize = 15f
            setBackgroundResource(R.drawable.bg_pill); setPadding(dpi(18), 0, dpi(18), 0); maxLines = 1; inputType = InputType.TYPE_CLASS_TEXT
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim() ?: ""; page = 0; refresh() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        filterChip = TextView(this).apply {
            textSize = 13f; setTextColor(col(R.attr.mbAccent)); setBackgroundResource(R.drawable.bg_pill); gravity = Gravity.CENTER
            setPadding(dpi(14), 0, dpi(14), 0); setOnClickListener { pickFilter() }
        }
        searchRow.addView(searchBox, LinearLayout.LayoutParams(0, dpi(48), 1f))
        searchRow.addView(filterChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(48)).apply { marginStart = dpi(8) })

        val allRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(10), 0, dpi(16), 0) }
        allBox = CheckBox(this).apply {
            setOnClickListener {
                if (pageKeys.isNotEmpty() && selected.containsAll(pageKeys)) selected.removeAll(pageKeys.toSet()) else selected.addAll(pageKeys)
                refresh()
            }
        }
        pageInfo = TextView(this).apply { textSize = 13f; setTextColor(col(R.attr.mbMuted)) }
        allRow.addView(allBox)
        allRow.addView(pageInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val pager = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setBackgroundColor(col(R.attr.mbBar)); setPadding(dpi(6), dpi(2), dpi(6), dpi(2)) }
        prevBtn = textBtn("‹ Newer") { if (page > 0) { page--; refresh(); list.scrollToPosition(0) } }
        nextBtn = textBtn("Older ›") { page++; refresh(); list.scrollToPosition(0) }
        pagerLabel = TextView(this).apply { textSize = 13f; setTextColor(col(R.attr.mbText)); gravity = Gravity.CENTER }
        pager.addView(prevBtn); pager.addView(pagerLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); pager.addView(nextBtn)

        list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@HistoryActivity); adapter = Adapter(); clipToPadding = false; setPadding(0, 0, 0, dpi(24)) }
        empty = TextView(this).apply { textSize = 15f; setTextColor(col(R.attr.mbMuted)); gravity = Gravity.CENTER; setPadding(dpi(24), dpi(40), dpi(24), dpi(24)) }

        root.addView(normalBar); root.addView(selectBar)
        root.addView(searchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dpi(10), dpi(10), dpi(10), dpi(6)) })
        root.addView(allRow)
        root.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(pager)
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) { override fun handleOnBackPressed() { goBack() } })
        refresh()
    }

    private fun goBack() {
        when {
            selected.isNotEmpty() -> { selected.clear(); refresh() }
            bin -> { bin = false; refresh() }
            query.isNotEmpty() -> searchBox.setText("")
            else -> finish()
        }
    }

    // ---------- the list ----------
    private fun dayStart(t: Long): Long { val c = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }; return c.timeInMillis }

    private fun dayLabel(t: Long): String {
        val today = dayStart(System.currentTimeMillis()); val d = dayStart(t)
        return when (d) {
            today -> "Today"
            today - 86_400_000L -> "Yesterday"
            else -> DateFormat.getDateInstance(DateFormat.FULL).format(Date(t))
        }
    }

    private fun matches(v: Visit): Boolean {
        if (v.time < from || v.time > to) return false
        if (query.isEmpty()) return true
        val q = query.lowercase()
        return v.title.lowercase().contains(q) || v.url.lowercase().contains(q)
    }

    private fun refresh() {
        val src: List<Visit> = if (bin) Store.historyBinList().map { it.visit } else Store.historyList()
        val filtered = src.filter { matches(it) }
        shownKeys = filtered.map { Store.visitKey(it) }
        val pages = maxOf(1, (filtered.size + PAGE_SIZE - 1) / PAGE_SIZE)
        page = page.coerceIn(0, pages - 1)
        val slice = filtered.drop(page * PAGE_SIZE).take(PAGE_SIZE)
        val out = ArrayList<Row>(); val keys = ArrayList<String>()
        var last = ""
        for (v in slice) {
            val lab = dayLabel(v.time)
            if (lab != last) { out.add(Row(lab, null, "h$lab")); last = lab }
            out.add(Row(null, v, Store.visitKey(v))); keys.add(Store.visitKey(v))
        }
        rows = out; pageKeys = keys
        selected.retainAll(shownKeys.toSet())
        val sel = selected.isNotEmpty()
        normalBar.visibility = if (sel) View.GONE else View.VISIBLE
        selectBar.visibility = if (sel) View.VISIBLE else View.GONE
        selCount.text = "${selected.size} selected"
        actBtn.text = if (bin) "Restore" else "Delete"
        binBtn.text = if (bin) "Done" else "Bin"
        clearBtn.text = if (bin) "Empty" else "Clear"
        clearBtn.visibility = if (shownKeys.isEmpty() && !bin) View.GONE else View.VISIBLE
        titleView.text = if (bin) "Recycle bin (30 days)" else "History"
        filterChip.text = "$filterLabel ▾"
        allBox.isChecked = keys.isNotEmpty() && selected.containsAll(keys)
        allBox.isEnabled = keys.isNotEmpty()
        val first = if (filtered.isEmpty()) 0 else page * PAGE_SIZE + 1
        pageInfo.text = "Select all on this page  ·  $first–${page * PAGE_SIZE + slice.size} of ${filtered.size}"
        pagerLabel.text = "Page ${page + 1} of $pages"
        prevBtn.alpha = if (page > 0) 1f else 0.35f; nextBtn.alpha = if (page < pages - 1) 1f else 0.35f
        prevBtn.isEnabled = page > 0; nextBtn.isEnabled = page < pages - 1
        empty.visibility = if (out.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when { bin -> "The bin is empty."; query.isNotEmpty() || filterLabel != "All time" -> "Nothing matches."; else -> "No history yet." }
        list.adapter?.notifyDataSetChanged()
    }

    // ---------- filter ----------
    private fun setRange(label: String, f: Long, t: Long) { filterLabel = label; from = f; to = t; page = 0; refresh() }

    private fun pickFilter() {
        val names = arrayOf("All time", "Last hour", "Today", "Yesterday", "Last 7 days", "Last 30 days", "Pick a date…", "Pick a date range…")
        AlertDialog.Builder(this).setTitle("Show history from").setItems(names) { _, w ->
            val today = dayStart(System.currentTimeMillis()); val day = 86_400_000L
            when (w) {
                0 -> setRange("All time", 0L, Long.MAX_VALUE)
                1 -> setRange("Last hour", System.currentTimeMillis() - 3_600_000L, Long.MAX_VALUE)
                2 -> setRange("Today", today, Long.MAX_VALUE)
                3 -> setRange("Yesterday", today - day, today - 1)
                4 -> setRange("Last 7 days", today - 6 * day, Long.MAX_VALUE)
                5 -> setRange("Last 30 days", today - 29 * day, Long.MAX_VALUE)
                6 -> pickDate("Pick a day") { d -> setRange(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(d)), d, d + day - 1) }
                else -> pickDate("From") { a -> pickDate("To") { b ->
                    val lo = minOf(a, b); val hi = maxOf(a, b)
                    val fmt = DateFormat.getDateInstance(DateFormat.SHORT)
                    setRange(fmt.format(Date(lo)) + " – " + fmt.format(Date(hi)), lo, hi + day - 1)
                } }
            }
        }.show()
    }

    private fun pickDate(title: String, done: (Long) -> Unit) {
        val c = Calendar.getInstance()
        val dlg = DatePickerDialog(this, { _, y, m, d ->
            val cc = Calendar.getInstance().apply { clear(); set(y, m, d, 0, 0, 0) }
            done(cc.timeInMillis)
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        dlg.setTitle(title); dlg.datePicker.maxDate = System.currentTimeMillis()
        dlg.show()
    }

    // ---------- actions ----------
    private fun open(url: String) { setResult(Activity.RESULT_OK, Intent().putExtra("url", url)); finish() }

    private fun toggle(key: String) { if (!selected.remove(key)) selected.add(key); refresh() }

    private fun actOnSelected() {
        val keys = selected.toSet()
        if (keys.isEmpty()) return
        if (bin) { val n = Store.restoreVisits(keys); Toast.makeText(this, "Restored $n", Toast.LENGTH_SHORT).show() }
        else { val n = Store.removeVisits(keys); Toast.makeText(this, "Removed $n. They stay in the bin for 30 days.", Toast.LENGTH_SHORT).show() }
        selected.clear(); refresh()
    }

    private fun clearShown() {
        if (bin) {
            AlertDialog.Builder(this).setTitle("Empty the bin?").setMessage("This permanently deletes everything in it.")
                .setPositiveButton("Empty") { _, _ -> Store.emptyHistoryBin(); refresh() }.setNegativeButton("Cancel", null).show()
            return
        }
        val n = shownKeys.size
        if (n == 0) return
        val what = if (query.isEmpty() && filterLabel == "All time") "all $n entries" else "$n " + (if (n == 1) "entry" else "entries") + (if (filterLabel != "All time") " from " + filterLabel.lowercase() else "") + (if (query.isNotEmpty()) " matching \"$query\"" else "")
        AlertDialog.Builder(this).setTitle("Remove $what?")
            .setMessage("They go to the recycle bin for 30 days. This only affects this phone.")
            .setPositiveButton("Remove") { _, _ -> Store.removeVisits(shownKeys.toSet()); refresh() }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- adapter ----------
    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position].header != null) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) {
                val h = TextView(this@HistoryActivity).apply {
                    textSize = 13f; setTextColor(col(R.attr.mbAccent)); setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(dpi(16), dpi(14), dpi(16), dpi(6))
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                return object : RecyclerView.ViewHolder(h) {}
            }
            val row = LinearLayout(this@HistoryActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(6), dpi(4), dpi(16), dpi(4))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val cb = CheckBox(this@HistoryActivity)
            val texts = LinearLayout(this@HistoryActivity).apply { orientation = LinearLayout.VERTICAL }
            val t1 = TextView(this@HistoryActivity).apply { textSize = 14f; setTextColor(col(R.attr.mbText)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val t2 = TextView(this@HistoryActivity).apply { textSize = 11f; setTextColor(col(R.attr.mbMuted)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            texts.addView(t1); texts.addView(t2)
            row.addView(cb); row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.tag = arrayOf<View>(t1, t2, cb)
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val r = rows[pos]
            if (r.header != null) { (h.itemView as TextView).text = r.header; return }
            val v = r.visit ?: return
            @Suppress("UNCHECKED_CAST") val tags = h.itemView.tag as Array<View>
            val tv = arrayOf(tags[0] as TextView, tags[1] as TextView); val cb = tags[2] as CheckBox
            tv[0].text = v.title.ifBlank { v.url }
            tv[1].text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(v.time)) + "  ·  " + v.url
            val isSel = r.key in selected
            cb.isChecked = isSel
            cb.setOnClickListener { toggle(r.key) }
            h.itemView.setBackgroundColor(if (isSel) (col(R.attr.mbAccent) and 0x00FFFFFF) or 0x33000000 else Color.TRANSPARENT)
            h.itemView.setOnClickListener { if (selected.isNotEmpty() || bin) toggle(r.key) else open(v.url) }
            h.itemView.setOnLongClickListener { toggle(r.key); true }
        }
    }
}
