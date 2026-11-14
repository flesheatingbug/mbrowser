package app.mbrowser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import java.text.DateFormat
import java.util.Date

/** One list screen for bookmarks, history and tabs from other devices. Tapping returns the URL. */
class ItemsActivity : AppCompatActivity() {
    private class Row(val title: String, val sub: String, val url: String)

    private var mode = "bookmarks"
    private var all: List<Row> = emptyList()
    private var shown: List<Row> = emptyList()
    private lateinit var list: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_items)
        Store.init(this)
        mode = intent.getStringExtra("mode") ?: "bookmarks"
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        findViewById<TextView>(R.id.title).text = when (mode) {
            "history" -> "History"
            "devices" -> "Tabs from other devices"
            else -> "Bookmarks"
        }
        findViewById<TextView>(R.id.hint).text = when (mode) {
            "history" -> "Tap to open. Long-press to clear all history."
            "devices" -> "Updated each time you sync."
            else -> "Tap to open. Long-press to remove."
        }
        findViewById<EditText>(R.id.filter).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { applyFilter(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        load()
    }

    private fun load() {
        all = when (mode) {
            "history" -> Store.historyList().take(1000).map {
                Row(it.title.ifBlank { it.url }, it.url + "  ·  " +
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.time)), it.url)
            }
            "devices" -> otherDeviceRows()
            else -> Store.bookmarkList().map { Row(it.title.ifBlank { it.url }, it.url, it.url) }
        }
        applyFilter(findViewById<EditText>(R.id.filter).text.toString())
    }

    private fun applyFilter(q: String) {
        val n = q.trim().lowercase()
        shown = if (n.isEmpty()) all else all.filter { it.title.lowercase().contains(n) || it.url.lowercase().contains(n) }
        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = if (shown.isEmpty()) 1 else shown.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(LayoutInflater.from(this@ItemsActivity).inflate(R.layout.item_row, parent, false)) {}
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val v = h.itemView
                val title = v.findViewById<TextView>(R.id.title)
                val sub = v.findViewById<TextView>(R.id.sub)
                val icon = v.findViewById<ImageView>(R.id.icon)
                if (shown.isEmpty()) {
                    title.text = "Nothing here yet"; sub.text = ""
                    v.setOnClickListener(null); v.setOnLongClickListener(null); return
                }
                val r = shown[pos]
                title.text = r.title; sub.text = r.sub
                val ic = Thumbs.icon(this@ItemsActivity, Thumbs.hostOf(r.url))
                if (ic != null) { icon.setImageBitmap(ic); icon.imageTintList = null }
                else { icon.setImageResource(R.drawable.ic_globe); icon.imageTintList = getColorStateList(R.color.muted) }
                v.setOnClickListener {
                    setResult(Activity.RESULT_OK, Intent().putExtra("url", r.url)); finish()
                }
                v.setOnLongClickListener {
                    when (mode) {
                        "bookmarks" -> AlertDialog.Builder(this@ItemsActivity).setMessage("Remove “${r.title}”?")
                            .setPositiveButton("Remove") { _, _ -> Store.removeBookmark(r.url); load() }
                            .setNegativeButton("Cancel", null).show()
                        "history" -> AlertDialog.Builder(this@ItemsActivity).setMessage("Clear all history?")
                            .setPositiveButton("Clear") { _, _ -> Store.clearHistory(); load() }
                            .setNegativeButton("Cancel", null).show()
                    }
                    true
                }
            }
        }
    }

    private fun otherDeviceRows(): List<Row> {
        val out = mutableListOf<Row>()
        val devices = try { JSONArray(Prefs(this).otherTabs) } catch (_: Exception) { JSONArray() }
        for (i in 0 until devices.length()) {
            val d = devices.getJSONObject(i)
            val name = d.optString("device")
            val tabs = d.optJSONArray("tabs") ?: continue
            for (j in 0 until tabs.length()) {
                val t = tabs.getJSONObject(j)
                out.add(Row(t.optString("title").ifBlank { t.optString("url") }, "$name  ·  ${t.optString("url")}", t.optString("url")))
            }
        }
        return out
    }
}
