package app.mbrowser

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog

fun Context.dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

/** A dialog with one text field per hint. onOk receives the trimmed values. */
fun promptFields(
    ctx: Context, title: String, hints: List<String>, initial: List<String>,
    okLabel: String = "Save", password: Boolean = false, onOk: (List<String>) -> Unit,
) {
    val box = LinearLayout(ctx)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0)
    val fields = hints.mapIndexed { i, h ->
        EditText(ctx).also {
            it.hint = h
            it.setText(initial.getOrElse(i) { "" })
            it.setSingleLine()
            it.inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            it.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            box.addView(it)
        }
    }
    AlertDialog.Builder(ctx).setTitle(title).setView(box)
        .setPositiveButton(okLabel) { _, _ -> onOk(fields.map { it.text.toString().trim() }) }
        .setNegativeButton("Cancel", null).show()
}

/** A colour of the current theme (see the mb… attributes). */
fun android.content.Context.tc(attr: Int): Int {
    val tv = android.util.TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}
fun android.content.Context.tcl(attr: Int): android.content.res.ColorStateList = android.content.res.ColorStateList.valueOf(tc(attr))
