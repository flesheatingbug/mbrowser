package app.mbrowser

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor

/** Remembers which downloads were started from this app, so they can be listed and opened here. */
object Downloads {
    class Item(val id: Long, val name: String, val mime: String, val status: Int, val done: Long, val total: Long, val time: Long)

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    fun ids(ctx: Context): List<Long> =
        (sp(ctx).getString("ids", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }

    fun add(ctx: Context, id: Long) {
        val l = (listOf(id) + ids(ctx)).distinct().take(200)
        sp(ctx).edit().putString("ids", l.joinToString(",")).apply()
    }

    fun forget(ctx: Context, id: Long) {
        sp(ctx).edit().putString("ids", ids(ctx).filter { it != id }.joinToString(",")).apply()
    }

    fun list(ctx: Context): List<Item> {
        val all = ids(ctx)
        if (all.isEmpty()) return emptyList()
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val out = mutableListOf<Item>()
        val c: Cursor? = dm.query(DownloadManager.Query().setFilterById(*all.toLongArray()))
        c?.use {
            while (it.moveToNext()) {
                fun s(col: String) = it.getString(it.getColumnIndexOrThrow(col)) ?: ""
                fun n(col: String) = it.getLong(it.getColumnIndexOrThrow(col))
                val title = s(DownloadManager.COLUMN_TITLE)
                val uri = s(DownloadManager.COLUMN_LOCAL_URI)
                val name = title.ifBlank { uri.substringAfterLast('/') }
                out.add(Item(n(DownloadManager.COLUMN_ID), name, s(DownloadManager.COLUMN_MEDIA_TYPE),
                    n(DownloadManager.COLUMN_STATUS).toInt(), n(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    n(DownloadManager.COLUMN_TOTAL_SIZE_BYTES), n(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)))
            }
        }
        // ids the system no longer knows (cleared elsewhere) are dropped from the list
        val known = out.map { it.id }.toSet()
        if (known.size != all.size) sp(ctx).edit().putString("ids", all.filter { it in known }.joinToString(",")).apply()
        return out.sortedByDescending { it.time }
    }
}
