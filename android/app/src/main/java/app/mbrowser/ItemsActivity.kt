package app.mbrowser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import java.text.DateFormat
import java.util.Date

/** One simple list screen for bookmarks, history and tabs from other devices. Tapping returns the URL. */
class ItemsActivity : AppCompatActivity() {
    private class Row(val title: String, val sub: String, val url: String)

    private var mode = "bookmarks"
    private var rows: MutableList<Row> = mutableListOf()
    private lateinit var list: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_items)
        Store.init(this)
        mode = intent.getStringExtra("mode") ?: "bookmarks"
        list = findViewById(R.id.list)
        findViewById<TextView>(R.id.title).text = when (mode) {
            "history" -> "History"
            "devices" -> "Tabs from other devices"
            else -> "Bookmarks"
        }
        findViewById<TextView>(R.id.hint).text = when (mode) {
            "history" -> "Tap to open. Long-press to clear all history."
            "devices" -> "Updated when you sync."
            else -> "Tap to open. Long-press to remove."
        }
        load()
        list.setOnItemClickListener { _, _, pos, _ ->
            setResult(Activity.RESULT_OK, Intent().putExtra("url", rows[pos].url))
            finish()
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            when (mode) {
                "bookmarks" -> AlertDialog.Builder(this).setMessage("Remove “${rows[pos].title}”?")
                    .setPositiveButton("Remove") { _, _ -> Store.removeBookmark(rows[pos].url); load() }
                    .setNegativeButton("Cancel", null).show()
                "history" -> AlertDialog.Builder(this).setMessage("Clear all history?")
                    .setPositiveButton("Clear") { _, _ -> Store.clearHistory(); load() }
                    .setNegativeButton("Cancel", null).show()
            }
            true
        }
    }

    private fun load() {
        rows = when (mode) {
            "history" -> Store.historyList().take(500).map {
                Row(it.title.ifBlank { it.url }, it.url + "  ·  " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.time)), it.url)
            }.toMutableList()
            "devices" -> otherDeviceRows()
            else -> Store.bookmarkList().map { Row(it.title.ifBlank { it.url }, it.url, it.url) }.toMutableList()
        }
        val titles = rows.map { it.title + "\n" + it.sub }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            if (titles.isEmpty()) listOf("Nothing here yet") else titles)
        if (titles.isEmpty()) rows = mutableListOf()
    }

    private fun otherDeviceRows(): MutableList<Row> {
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
