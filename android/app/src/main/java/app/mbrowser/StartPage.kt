package app.mbrowser

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.graphics.Outline
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

/**
 * The speed dial page: dial tabs along the top, a grid of cards, an Add pill.
 * The dials live in the synced "startPage" settings group, so they match your other devices.
 * A dial with a "dials" list and no address is a folder; folders can nest to any depth.
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
    init { grid.clipChildren = false; grid.clipToPadding = false }
    private val crumb: TextView = root.findViewById(R.id.crumb)
    private val ADD = Any()
    private var wp = false                  // drawn over a wallpaper: no bar behind the dial tabs, light text
    private var refreshPending = false
    private var reorder = false             // rearranging: touch and hold a tile, then drag it
    private val path = ArrayList<Int>()     // indexes of the folders we are inside, within the current dial

    private class Dial(val index: Int, val title: String, val url: String, val folder: Boolean, val count: Int)

    /** Called by the main screen when the wallpaper is shown or hidden behind this page. */
    fun setOnWallpaper(on: Boolean) {
        if (wp == on) return
        wp = on
        root.findViewById<View>(R.id.dialBar).setBackgroundColor(Color.TRANSPARENT)
        root.findViewById<ImageButton>(R.id.btnTune).imageTintList = android.content.res.ColorStateList.valueOf(if (on) Color.WHITE else act.tc(R.attr.mbText))
        refresh()
    }

    /** Icons arrive in the background; redraw once they have, not once for each. */
    private fun scheduleRefresh() {
        if (refreshPending) return
        refreshPending = true
        root.postDelayed({ refreshPending = false; if (!reorder) { grid.adapter?.notifyDataSetChanged(); popupGrid?.adapter?.notifyDataSetChanged() } }, 400)
    }

    /** Swipe left: next speed dial (on the last one, offers a new one). Swipe right: back to the previous one. */
    private fun swipeDial(forward: Boolean) {
        if (reorder) return
        val sp = Settings.value("startPage")
        val g = groupsOf(sp)
        val cur = currentId(sp)
        var i = 0
        for (k in 0 until g.length()) if (g.getJSONObject(k).optString("id") == cur) i = k
        val n = i + if (forward) 1 else -1
        if (n < 0) return
        if (n >= g.length()) { newDialPrompt(); return }
        prefs.currentDial = g.getJSONObject(n).optString("id"); path.clear(); refresh()
        val w = grid.width.toFloat()
        grid.translationX = if (forward) w * 0.35f else -w * 0.35f; grid.alpha = 0.3f
        grid.animate().translationX(0f).alpha(1f).setDuration(180).start()
    }

    private fun newDialPrompt() {
        AlertDialog.Builder(act).setTitle("New speed dial?").setMessage("You're on the last speed dial. Make a new one here?")
            .setPositiveButton("Create") { _, _ -> newDial() }.setNegativeButton("Cancel", null).show()
    }

    private fun newDial() {
        promptFields(act, "New speed dial", listOf("Name"), listOf(""), "Create") { v ->
            if (v[0].isEmpty()) return@promptFields
            val id = "g" + System.currentTimeMillis()
            edit { s -> groupsOf(s).put(JSONObject().put("id", id).put("name", v[0]).put("dials", JSONArray())) }
            prefs.currentDial = id; path.clear(); refresh()
        }
    }

    init {
        DialIcons.onChanged { scheduleRefresh() }
        val swipes = android.view.GestureDetector(act, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(a: android.view.MotionEvent?, b: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (a == null) return false
                val dx = b.x - a.x; val dy = b.y - a.y
                if (Math.abs(dx) > act.dp(70) && Math.abs(dx) > Math.abs(dy) * 1.8f && Math.abs(vx) > 600) { swipeDial(dx < 0); return true }
                return false
            }
        })
        grid.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean { swipes.onTouchEvent(e); return false }
        })
        root.findViewById<View>(R.id.dialBar).setBackgroundColor(Color.TRANSPARENT)
        root.findViewById<ImageButton>(R.id.btnTune).setOnClickListener { tuneMenu() }
        crumb.setOnClickListener { up() }
        makeHelper().attachToRecyclerView(grid)
    }

    private fun makeHelper(): ItemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled() = reorder
        override fun isItemViewSwipeEnabled() = false
        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
            if (reorder && vh is Adapter.CardVH) makeMovementFlags(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) else 0
        override fun canDropOver(rv: RecyclerView, cur: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = target is Adapter.CardVH
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            if (target !is Adapter.CardVH) return false
            (rv.adapter as? Adapter)?.move(vh.bindingAdapterPosition, target.bindingAdapterPosition); return true
        }
        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, state: Int) {
            super.onSelectedChanged(vh, state)
            if (state == ItemTouchHelper.ACTION_STATE_DRAG) vh?.itemView?.alpha = 0.7f
        }
        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.alpha = 1f
            (rv.adapter as? Adapter)?.let { saveOrder(it) }
        }
    })

    /** Writes the tiles' new order back to the dial list (the indexes change, so the grid is rebuilt afterwards). */
    private fun saveOrder(a: Adapter) {
        val order = a.items.filterIsInstance<Dial>().map { it.index }
        if (order == order.sorted()) return
        val p = ArrayList(a.p)
        edit { sp ->
            val arr = listAt(sp, a.groupId, p) ?: return@edit
            val objs = (0 until arr.length()).map { arr.get(it) }
            val ordered = order.mapNotNull { objs.getOrNull(it) } + objs.filterIndexed { i, _ -> i !in order }
            while (arr.length() > 0) arr.remove(0)
            for (o in ordered) arr.put(o)
        }
        grid.post { refresh() }
    }

    /** Columns for the current screen: the chosen count is for portrait, and turning the phone sideways fits more tiles of the same size. */
    private fun effCols(): Int {
        val base = prefs.dialCols.coerceIn(2, 5)
        val dm = act.resources.displayMetrics
        val ratio = dm.widthPixels.toFloat() / minOf(dm.widthPixels, dm.heightPixels)
        return Math.round(base * ratio).coerceIn(base, 12)
    }

    fun resetPath() { path.clear(); popup?.let { it.setOnDismissListener(null); it.dismiss() }; popup = null; popupGrid = null }

    /** Goes up one folder. Returns false when already at the top. */
    fun up(): Boolean {
        if (path.isEmpty()) return false
        path.removeAt(path.size - 1)
        refresh()
        return true
    }

    // ---------- data ----------
    private fun groupsOf(sp: JSONObject): JSONArray = sp.optJSONArray("groups") ?: JSONArray().also { sp.put("groups", it) }

    private fun currentId(sp: JSONObject): String {
        val g = groupsOf(sp)
        val want = prefs.currentDial
        for (i in 0 until g.length()) if (g.getJSONObject(i).optString("id") == want) return want
        return if (g.length() > 0) g.getJSONObject(0).optString("id") else "main"
    }

    private fun group(sp: JSONObject, id: String): JSONObject? {
        val g = groupsOf(sp)
        for (i in 0 until g.length()) if (g.getJSONObject(i).optString("id") == id) return g.getJSONObject(i)
        return null
    }

    // Desktop uses { type:"folder", items:[] }; Android uses { dials:[] }. Accept both.
    private fun dialArray(o: JSONObject): JSONArray =
        o.optJSONArray("dials") ?: o.optJSONArray("items") ?: JSONArray().also { o.put("dials", it) }

    private fun isFolder(o: JSONObject) =
        (o.has("dials") || o.has("items") || o.optString("type") == "folder") && o.optString("url").isEmpty()

    /** The list of dials inside the given folder path of a group (the group's own list for an empty path). */
    private fun listAt(sp: JSONObject, groupId: String, p: List<Int>): JSONArray? {
        var arr = group(sp, groupId)?.let { dialArray(it) } ?: return null
        for (i in p) {
            val o = arr.optJSONObject(i) ?: return null
            if (!isFolder(o)) return null
            arr = dialArray(o)
        }
        return arr
    }

    private fun itemsOf(sp: JSONObject, id: String, p: List<Int>): List<Dial> {
        val a = listAt(sp, id, p) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val f = isFolder(o)
            val u = o.optString("url")
            if (!f && !u.startsWith("http")) null
            else Dial(i, o.optString("title"), u, f, if (f) (o.optJSONArray("dials")?.length() ?: 0) else 0)
        }
    }

    /** Reads the startPage group, lets [change] modify it, and saves it (which marks it newer for sync). */
    private fun edit(change: (JSONObject) -> Unit) {
        val sp = Settings.value("startPage")
        change(sp)
        Settings.set("startPage", sp)
    }

    fun refresh() {
        val sp = Settings.value("startPage")
        val cur = currentId(sp)
        // a folder path that no longer exists (changed on another device) falls back to the top
        if (path.isNotEmpty() && listAt(sp, cur, path) == null) path.clear()
        tabsRow.removeAllViews()
        val g = groupsOf(sp)
        for (i in 0 until g.length()) {
            val o = g.getJSONObject(i)
            val id = o.optString("id")
            val tv = TextView(act)
            tv.text = o.optString("name", "Speed Dial")
            tv.textSize = 16f
            tv.setPadding(act.dp(14), act.dp(14), act.dp(14), act.dp(12))
            if (wp) {
                tv.setTextColor(if (id == cur) Color.WHITE else Color.argb(0xB3, 255, 255, 255))
                tv.setShadowLayer(6f, 0f, 1f, Color.argb(0x99, 0, 0, 0))
                if (id == cur) tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
            } else if (id == cur) {
                tv.setTextColor(act.tc(R.attr.mbAccent)); tv.setBackgroundResource(R.drawable.bg_tab_selected)
            } else tv.setTextColor(act.tc(R.attr.mbMuted))
            tv.setOnClickListener { prefs.currentDial = id; path.clear(); refresh() }
            tabsRow.addView(tv)
        }

        crumb.visibility = View.GONE

        val items = ArrayList<Any>(itemsOf(sp, cur, emptyList()))
        if (prefs.showAdd || reorder) items.add(ADD)
        val cols = effCols()
        grid.layoutManager = GridLayoutManager(act, cols)
        grid.adapter = Adapter(items, cols, cur, sp.optBoolean("thumbnails", true), emptyList())
        updatePopup(sp, cur)
    }

    // ---------- actions ----------
    private fun addMenu(groupId: String, p: List<Int>) {
        AlertDialog.Builder(act).setItems(arrayOf("Add a site", "Add a folder")) { _, i ->
            if (i == 0) addItem(groupId, p) else addFolder(groupId, p)
        }.show()
    }

    /** The same compact bar as on the desktop: one address field and an Add button, titled by the site's name. */
    private fun addItem(groupId: String, p: List<Int>) {
        val dlg = android.app.Dialog(act)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val bar = LinearLayout(act)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(act.dp(14), act.dp(6), act.dp(8), act.dp(6))
        bar.background = GradientDrawable().also { g ->
            g.cornerRadius = act.dp(14).toFloat(); g.setColor(act.tc(R.attr.mbCard)); g.setStroke(act.dp(1), act.tc(R.attr.mbLine))
        }
        val field = android.widget.EditText(act)
        field.hint = "Type address to add to your Speed Dial"
        field.setSingleLine()
        field.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        field.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
        field.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        field.background = null
        field.setTextColor(act.tc(R.attr.mbText)); field.setHintTextColor(act.tc(R.attr.mbMuted))
        field.textSize = 15f
        bar.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        val go = TextView(act)
        go.text = "Add"; go.textSize = 15f; go.setTypeface(null, android.graphics.Typeface.BOLD)
        go.setPadding(act.dp(16), act.dp(10), act.dp(16), act.dp(10))
        go.background = GradientDrawable().also { g -> g.cornerRadius = act.dp(10).toFloat(); g.setColor(act.tc(R.attr.mbAccent)) }
        go.setTextColor(Color.WHITE)
        go.alpha = 0.5f; go.isEnabled = false
        bar.addView(go)
        fun submit() {
            var url = field.text.toString().trim()
            if (url.isEmpty()) return
            if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(url)) url = "https://$url"
            edit { sp -> listAt(sp, groupId, p)?.put(JSONObject().put("title", Thumbs.hostOf(url).removePrefix("www.").ifBlank { url }).put("url", url)) }
            dlg.dismiss(); refresh()
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { val on = !e.isNullOrBlank(); go.isEnabled = on; go.alpha = if (on) 1f else 0.5f }
        })
        field.setOnEditorActionListener { _, _, _ -> submit(); true }
        go.setOnClickListener { submit() }
        dlg.setContentView(bar)
        dlg.window?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.TOP)
            w.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            w.attributes = w.attributes.also { it.y = act.dp(56); it.horizontalMargin = 0.02f; it.dimAmount = 0.35f }
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dlg.show()
        field.requestFocus()
    }

    private fun addFolder(groupId: String, p: List<Int>) {
        promptFields(act, "New folder", listOf("Folder name"), listOf(""), "Create") { v ->
            if (v[0].isEmpty()) return@promptFields
            edit { sp -> listAt(sp, groupId, p)?.put(JSONObject().put("title", v[0]).put("folder", true).put("dials", JSONArray())) }
            refresh()
        }
    }

    private fun startReorder() {
        reorder = true; refresh()
        android.widget.Toast.makeText(act, "Touch and hold a tile, then drag it. Tap Done when finished.", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun itemMenu(d: Dial, groupId: String, p: List<Int>) {
        val opts = if (d.folder) arrayOf("Open", "Rename", "Move to…", "Delete folder", "Rearrange")
        else arrayOf("Open in new tab", "Edit", "Move to…", "Remove", "Rearrange")
        AlertDialog.Builder(act).setTitle(d.title.ifBlank { d.url }).setItems(opts) { _, i ->
            when (i) {
                0 -> if (d.folder) openFolder(p + d.index) else { resetPath(); open(d.url, true) }
                1 -> if (d.folder) promptFields(act, "Rename folder", listOf("Folder name"), listOf(d.title), "Rename") { v ->
                    if (v[0].isNotEmpty()) { edit { sp -> listAt(sp, groupId, p)?.optJSONObject(d.index)?.put("title", v[0]) }; refresh() }
                } else promptFields(act, "Edit", listOf("Address", "Title"), listOf(d.url, d.title)) { v ->
                    if (v[0].isNotEmpty()) {
                        edit { sp -> listAt(sp, groupId, p)?.optJSONObject(d.index)?.put("url", v[0])?.put("title", v[1]) }
                        refresh()
                    }
                }
                2 -> moveItem(d, groupId, p)
                4 -> startReorder()
                3 -> {
                    val doIt = { edit { sp -> listAt(sp, groupId, p)?.remove(d.index) }; refresh() }
                    if (d.folder && d.count > 0) AlertDialog.Builder(act).setMessage("Delete “${d.title}” and the ${d.count} items in it?")
                        .setPositiveButton("Delete") { _, _ -> doIt() }.setNegativeButton("Cancel", null).show()
                    else doIt()
                }
            }
        }.show()
    }

    private fun moveItem(d: Dial, groupId: String, p: List<Int>) {
        val sp = Settings.value("startPage")
        val labels = ArrayList<String>()
        val targets = ArrayList<(JSONObject) -> JSONArray?>()
        if (p.isNotEmpty()) {
            labels.add("⬆  Up one level"); targets.add { s -> listAt(s, groupId, p.dropLast(1)) }
        }
        for (f in itemsOf(sp, groupId, p)) if (f.folder && f.index != d.index) {
            labels.add("📁  " + f.title)
            targets.add { s -> listAt(s, groupId, p)?.optJSONObject(f.index)?.let { dialArray(it) } }
        }
        val g = groupsOf(sp)
        for (i in 0 until g.length()) {
            val o = g.getJSONObject(i)
            val id = o.optString("id")
            if (id == groupId) continue
            labels.add("Dial: " + o.optString("name")); targets.add { s -> group(s, id)?.let { dialArray(it) } }
        }
        if (labels.isEmpty()) {
            AlertDialog.Builder(act).setMessage("There is nowhere to move this. Create a folder or another dial first.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(act).setTitle("Move to").setItems(labels.toTypedArray()) { _, k ->
            edit { s ->
                val src = listAt(s, groupId, p) ?: return@edit
                val dst = targets[k](s) ?: return@edit
                val item = src.opt(d.index) ?: return@edit
                src.remove(d.index)
                dst.put(item)
            }
            refresh()
        }.show()
    }

    private fun tuneMenu() {
        val dlg = android.app.Dialog(act)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val card = LinearLayout(act); card.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable(); bg.cornerRadius = act.dp(24).toFloat(); bg.setColor(act.tc(R.attr.mbCard)); bg.setStroke(act.dp(1), act.tc(R.attr.mbLine))
        card.background = bg
        card.setPadding(act.dp(8), act.dp(16), act.dp(8), act.dp(8))
        val scroll = android.widget.ScrollView(act); scroll.addView(card)

        fun title(t: String) {
            val tv = TextView(act); tv.text = t; tv.textSize = 20f; tv.setTypeface(null, android.graphics.Typeface.BOLD); tv.setTextColor(act.tc(R.attr.mbText))
            tv.setPadding(act.dp(16), 0, act.dp(16), act.dp(8)); card.addView(tv)
        }
        fun section(t: String) {
            val tv = TextView(act); tv.text = t.uppercase(); tv.textSize = 12f; tv.letterSpacing = 0.08f; tv.setTextColor(act.tc(R.attr.mbMuted))
            tv.setPadding(act.dp(16), act.dp(14), act.dp(16), act.dp(4)); card.addView(tv)
        }
        /** One row: icon, label, and optionally a value or a switch on the right. */
        fun row(icon: Int, label: String, value: String? = null, sw: Boolean? = null, danger: Boolean = false, action: (Boolean) -> Unit) {
            val r = LinearLayout(act); r.orientation = LinearLayout.HORIZONTAL; r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(act.dp(16), 0, act.dp(12), 0); r.foreground = TypedValueHolder.selectable(act)
            val ic = ImageView(act); ic.setImageResource(icon); ic.imageTintList = android.content.res.ColorStateList.valueOf(if (danger) Color.parseColor("#ef5350") else act.tc(R.attr.mbMuted))
            r.addView(ic, LinearLayout.LayoutParams(act.dp(22), act.dp(22)))
            val tv = TextView(act); tv.text = label; tv.textSize = 16f; tv.setTextColor(if (danger) Color.parseColor("#ef5350") else act.tc(R.attr.mbText)); tv.setPadding(act.dp(16), 0, 0, 0)
            r.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
            if (value != null) { val v = TextView(act); v.text = value; v.textSize = 15f; v.setTextColor(act.tc(R.attr.mbMuted)); r.addView(v) }
            var swv: androidx.appcompat.widget.SwitchCompat? = null
            if (sw != null) {
                swv = androidx.appcompat.widget.SwitchCompat(act); swv.isChecked = sw; swv.isClickable = false
                r.addView(swv)
            }
            r.setOnClickListener { if (swv != null) { swv.isChecked = !swv.isChecked; action(swv.isChecked) } else { dlg.dismiss(); action(true) } }
            card.addView(r, LinearLayout.LayoutParams(-1, act.dp(48)))
        }

        val sp = Settings.value("startPage")
        val cur = currentId(sp)
        val curName = group(sp, cur)?.optString("name") ?: "Speed Dial"
        val onlyOne = groupsOf(sp).length() <= 1
        val thumbs = sp.optBoolean("thumbnails", true)

        title(curName)
        section("Add")
        row(R.drawable.ic_folder, "New folder here") { addFolder(cur, emptyList()) }
        row(R.drawable.ic_add, "New dial page") { newDial() }

        section("This dial")
        row(R.drawable.ic_settings, "Rename") {
            promptFields(act, "Rename dial", listOf("Name"), listOf(curName), "Rename") { v ->
                if (v[0].isNotEmpty()) { edit { s -> group(s, cur)?.put("name", v[0]) }; refresh() }
            }
        }
        row(R.drawable.ic_tune, "Rearrange") { startReorder() }
        if (!onlyOne) row(R.drawable.ic_trash, "Delete this dial", danger = true) {
            AlertDialog.Builder(act).setMessage("Delete “$curName” and everything in it?")
                .setPositiveButton("Delete") { _, _ ->
                    edit { s -> val g = groupsOf(s); val n = JSONArray()
                        for (k in 0 until g.length()) if (g.getJSONObject(k).optString("id") != cur) n.put(g.get(k))
                        s.put("groups", n) }
                    prefs.currentDial = ""; path.clear(); refresh()
                }.setNegativeButton("Cancel", null).show()
        }

        section("Look")
        row(R.drawable.ic_tabs, "Columns", value = prefs.dialCols.toString()) {
            val c = arrayOf("2", "3", "4", "5")
            AlertDialog.Builder(act).setTitle("Columns")
                .setSingleChoiceItems(c, (prefs.dialCols - 2).coerceIn(0, 3)) { d, k -> prefs.dialCols = k + 2; d.dismiss(); refresh() }.show()
        }
        row(R.drawable.ic_menu, "Show titles", sw = prefs.showTitles) { prefs.showTitles = it; refresh() }
        row(R.drawable.ic_globe, "Show thumbnails", sw = thumbs) { Settings.setThumbnails(it); refresh() }
        row(R.drawable.ic_add, "Show add button", sw = prefs.showAdd) { prefs.showAdd = it; refresh() }

        section("More")
        row(R.drawable.ic_settings, "All settings…") { openSettings() }

        dlg.setContentView(scroll)
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dlg.window?.setLayout((act.resources.displayMetrics.widthPixels * 0.9f).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.show()
    }


    // ---------- folder popup ----------
    private var popup: android.app.Dialog? = null
    private var popupGrid: RecyclerView? = null
    private var popupTitle: TextView? = null
    private var popupBack: TextView? = null

    private fun openFolder(p: List<Int>) { path.clear(); path.addAll(p); refresh() }

    private fun buildPopup() {
        val dlg = android.app.Dialog(act)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val card = LinearLayout(act); card.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable(); bg.cornerRadius = act.dp(24).toFloat(); bg.setColor(act.tc(R.attr.mbCard)); bg.setStroke(act.dp(1), act.tc(R.attr.mbLine))
        card.background = bg
        card.setPadding(act.dp(8), act.dp(8), act.dp(8), act.dp(12))
        val head = LinearLayout(act); head.orientation = LinearLayout.HORIZONTAL; head.gravity = Gravity.CENTER_VERTICAL
        val back = TextView(act); back.text = "‹"; back.textSize = 26f; back.setTextColor(act.tc(R.attr.mbText)); back.setPadding(act.dp(12), 0, act.dp(8), act.dp(4))
        back.setOnClickListener { up() }
        val title = TextView(act); title.textSize = 18f; title.setTypeface(null, android.graphics.Typeface.BOLD); title.setTextColor(act.tc(R.attr.mbText))
        title.setPadding(act.dp(8), act.dp(10), act.dp(8), act.dp(10)); title.maxLines = 1; title.ellipsize = android.text.TextUtils.TruncateAt.END
        val close = TextView(act); close.text = "✕"; close.textSize = 18f; close.setTextColor(act.tc(R.attr.mbMuted)); close.setPadding(act.dp(12), act.dp(6), act.dp(12), act.dp(6))
        close.setOnClickListener { dlg.dismiss() }
        head.addView(back); head.addView(title, LinearLayout.LayoutParams(0, -2, 1f)); head.addView(close)
        val rv = RecyclerView(act); rv.clipChildren = false; rv.clipToPadding = false
        rv.overScrollMode = View.OVER_SCROLL_NEVER
        makeHelper().attachToRecyclerView(rv)
        card.addView(head, LinearLayout.LayoutParams(-1, -2))
        card.addView(rv, LinearLayout.LayoutParams(-1, -2))
        dlg.setContentView(card)
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dlg.window?.setLayout((act.resources.displayMetrics.widthPixels * 0.94f).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.window?.setGravity(Gravity.CENTER)
        dlg.setOnDismissListener { popup = null; popupGrid = null; if (path.isNotEmpty()) { path.clear(); refresh() } }
        popup = dlg; popupGrid = rv; popupTitle = title; popupBack = back
        dlg.show()
    }

    private fun updatePopup(sp: JSONObject, cur: String) {
        if (path.isEmpty()) {
            popup?.let { it.setOnDismissListener(null); it.dismiss() }
            popup = null; popupGrid = null
            return
        }
        if (popup == null) buildPopup()
        val folder = listAt(sp, cur, path.dropLast(1))?.optJSONObject(path.last())
        popupTitle?.text = folder?.optString("title")?.ifBlank { "Folder" } ?: "Folder"
        popupBack?.visibility = if (path.size > 1) View.VISIBLE else View.GONE
        val items = ArrayList<Any>(itemsOf(sp, cur, path))
        if (prefs.showAdd || reorder) items.add(ADD)
        val cols = effCols()
        popupGrid?.layoutManager = GridLayoutManager(act, cols)
        popupGrid?.adapter = Adapter(items, cols, cur, sp.optBoolean("thumbnails", true), ArrayList(path))
    }

    // ---------- grid ----------
    private inner class Adapter(val items: MutableList<Any>, val cols: Int, val groupId: String, val thumbsOn: Boolean, val p: List<Int>) :
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

        fun move(from: Int, to: Int) {
            if (from < 0 || to < 0 || from >= items.size || to >= items.size) return
            items.add(to, items.removeAt(from)); notifyItemMoved(from, to)
        }

        override fun getItemViewType(position: Int) = if (items[position] === ADD) 1 else 0
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 1) AddVH(inflater.inflate(R.layout.item_add, parent, false))
            else CardVH(inflater.inflate(R.layout.item_card, parent, false)).also {
                it.card.clipToOutline = true
                it.itemView.setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> v.animate().translationY(-act.dp(3).toFloat()).scaleX(1.03f).scaleY(1.03f).setDuration(140).start()
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(180).start()
                    }
                    false
                }
            }
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            bind(h, pos)
            if (reorder && h is CardVH) {
                h.itemView.setOnClickListener(null); h.itemView.setOnLongClickListener(null)
                h.itemView.isClickable = false; h.itemView.isLongClickable = false
            }
        }

        /** Cards (a thumbnail in a card) or icons only (a big rounded icon with its name, straight on the page). */
        private fun look(h: CardVH, icons: Boolean, size: Int, th: Int) {
            val tlp = h.thumb.layoutParams as FrameLayout.LayoutParams
            val llp = h.letter.layoutParams as FrameLayout.LayoutParams
            if (icons) {
                h.card.background = null; h.card.clipToOutline = false; (h.card as android.view.ViewGroup).clipChildren = false; h.card.elevation = 0f
                h.thumb.elevation = act.dp(5).toFloat(); h.letter.elevation = act.dp(5).toFloat()
                tlp.width = size; tlp.height = size; tlp.gravity = Gravity.CENTER_HORIZONTAL
                llp.width = size; llp.height = size; llp.gravity = Gravity.CENTER_HORIZONTAL
                val round = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, v.width * 0.22f) }
                }
                h.thumb.outlineProvider = round; h.thumb.clipToOutline = true
                h.letter.outlineProvider = round; h.letter.clipToOutline = true
                h.icon.visibility = View.GONE
                h.title.gravity = Gravity.CENTER; h.title.textSize = 13f
                h.title.setTextColor(if (wp) Color.WHITE else act.tc(R.attr.mbText))
                if (wp) h.title.setShadowLayer(6f, 0f, 1f, Color.argb(0x99, 0, 0, 0)) else h.title.setShadowLayer(0f, 0f, 0f, 0)
                h.row.setPadding(act.dp(2), act.dp(6), act.dp(2), act.dp(4))
                (h.row as LinearLayout).gravity = Gravity.CENTER
                (h.title.layoutParams as LinearLayout.LayoutParams).apply { width = -1; weight = 0f; marginStart = 0 }
            } else {
                h.card.setBackgroundResource(R.drawable.bg_card); h.card.clipToOutline = true; h.card.elevation = act.dp(4).toFloat()
                h.thumb.elevation = 0f; h.letter.elevation = 0f
                tlp.width = -1; tlp.height = th; tlp.gravity = Gravity.NO_GRAVITY
                llp.width = -1; llp.height = th; llp.gravity = Gravity.NO_GRAVITY
                h.thumb.outlineProvider = ViewOutlineProvider.BACKGROUND; h.thumb.clipToOutline = false
                h.letter.outlineProvider = ViewOutlineProvider.BACKGROUND; h.letter.clipToOutline = false
                h.icon.visibility = View.VISIBLE
                h.title.gravity = Gravity.START; h.title.textSize = 12f
                h.title.setTextColor(act.tc(R.attr.mbText)); h.title.setShadowLayer(0f, 0f, 0f, 0)
                h.row.setPadding(act.dp(8), 0, act.dp(8), act.dp(8))
                (h.row as LinearLayout).gravity = Gravity.CENTER_VERTICAL
                (h.title.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; marginStart = act.dp(8) }
            }
            h.thumb.layoutParams = tlp; h.letter.layoutParams = llp
        }

        private fun bind(h: RecyclerView.ViewHolder, pos: Int) {
            if (h is AddVH) {
                val pill = h.itemView.findViewById<TextView>(R.id.addPill)
                val plus = prefs.dialStyle == "icons" && !reorder
                if (plus) {
                    // icons-only look: just a round +, no text
                    pill.text = "+"; pill.textSize = 30f; pill.gravity = Gravity.CENTER
                    pill.setCompoundDrawables(null, null, null, null)
                    // same size as the icons and lined up with their top edge
                    val wpx = act.resources.displayMetrics.widthPixels - act.dp(20 + 18 * cols)
                    val sz = minOf(act.dp(78), ((wpx / cols) * 0.8f).toInt())
                    pill.minWidth = 0; pill.minHeight = 0
                    pill.layoutParams = FrameLayout.LayoutParams(sz, sz, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also { it.topMargin = act.dp(14) }   // the cards start 9dp margin + 5dp padding down
                    pill.setPadding(0, 0, 0, act.dp(4))
                    pill.contentDescription = "Add speed dial"
                } else {
                    pill.textSize = 16f; pill.gravity = Gravity.CENTER_VERTICAL
                    pill.minWidth = 0; pill.minHeight = 0
                    pill.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
                    pill.setCompoundDrawablesRelativeWithIntrinsicBounds(if (reorder) 0 else R.drawable.ic_add, 0, 0, 0)   // no + beside "Done"
                    pill.setPadding(act.dp(22), act.dp(14), act.dp(22), act.dp(14))
                }
                if (reorder) { pill.text = "Done"; pill.setOnClickListener { reorder = false; refresh() } }
                else { if (!plus) pill.text = "Add"; pill.setOnClickListener { addMenu(groupId, p) } }
                return
            }
            h as CardVH
            val d = items[pos] as Dial
            val host = Thumbs.hostOf(d.url)
            val label = d.title.ifBlank { host.ifEmpty { d.url } }

            val widthPx = act.resources.displayMetrics.widthPixels - act.dp(20 + 18 * cols)
            val th = ((widthPx / cols) * 0.56f).toInt()
            val icons = prefs.dialStyle == "icons"
            val size = minOf(act.dp(78), ((widthPx / cols) * 0.8f).toInt())
            look(h, icons, size, th)

            if (d.folder && icons) {
                val kids = itemsOf(Settings.value("startPage"), groupId, p + d.index).filter { !it.folder }.map { it.url }
                kids.take(4).forEach { DialIcons.ensure(act, it) { scheduleRefresh() } }
                h.letter.visibility = View.GONE
                h.thumb.visibility = View.VISIBLE
                h.thumb.scaleType = ImageView.ScaleType.FIT_XY
                h.thumb.setPadding(0, 0, 0, 0); h.thumb.background = null; h.thumb.imageTintList = null
                h.thumb.setImageBitmap(DialIcons.folder(act, kids, size, if (wp) Color.argb(0x73, 0, 0, 0) else act.tc(R.attr.mbPill)))
                h.title.text = label
                h.row.visibility = View.VISIBLE
                h.itemView.setOnClickListener { openFolder(p + d.index) }
                h.itemView.setOnLongClickListener { itemMenu(d, groupId, p); true }
                return
            }
            if (d.folder) {
                h.letter.visibility = View.GONE
                h.thumb.visibility = View.VISIBLE
                h.thumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
                h.thumb.setPadding(act.dp(28), act.dp(18), act.dp(28), act.dp(18))
                h.thumb.setImageResource(R.drawable.ic_folder)
                h.thumb.imageTintList = act.tcl(R.attr.mbAccent)
                val g = GradientDrawable()
                g.cornerRadius = act.dp(12).toFloat()
                g.setColor(act.tc(R.attr.mbPill))
                h.thumb.background = g
                h.icon.setImageResource(R.drawable.ic_folder); h.icon.imageTintList = act.tcl(R.attr.mbAccent)
                h.title.text = label
                h.row.visibility = View.VISIBLE   // folders always show their name
                h.itemView.setOnClickListener { openFolder(p + d.index) }
                h.itemView.setOnLongClickListener { itemMenu(d, groupId, p); true }
                return
            }

            // a normal site: undo anything a folder tile may have set on this recycled view
            h.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            h.thumb.setPadding(0, 0, 0, 0)
            h.thumb.background = null
            h.thumb.imageTintList = null

            if (icons) {
                val big = DialIcons.get(act, host)
                h.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                if (big != null) {
                    h.thumb.setImageBitmap(big); h.thumb.visibility = View.VISIBLE; h.letter.visibility = View.GONE
                } else {
                    DialIcons.ensure(act, d.url) { scheduleRefresh() }
                    h.thumb.visibility = View.GONE; h.letter.visibility = View.VISIBLE
                    h.letter.text = (label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '•').toString()
                    val g = GradientDrawable()
                    g.cornerRadius = size * 0.22f
                    g.setColor(Color.HSVToColor(floatArrayOf(Math.floorMod(host.hashCode(), 360).toFloat(), 0.45f, 0.5f)))
                    h.letter.background = g
                }
                h.title.text = label
                h.row.visibility = if (prefs.showTitles) View.VISIBLE else View.GONE
                h.itemView.setOnClickListener { resetPath(); open(d.url, false) }
                h.itemView.setOnLongClickListener { itemMenu(d, groupId, p); true }
                return
            }

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
            else { h.icon.setImageResource(R.drawable.ic_globe); h.icon.imageTintList = act.tcl(R.attr.mbMuted) }
            h.title.text = label
            h.row.visibility = if (prefs.showTitles) View.VISIBLE else View.GONE

            h.itemView.setOnClickListener { resetPath(); open(d.url, false) }
            h.itemView.setOnLongClickListener { itemMenu(d, groupId, p); true }
        }
    }
}
