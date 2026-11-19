package ru.uvw.byecensor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import ru.uvw.byecensor.logic.Server
import ru.uvw.byecensor.store.Store

/**
 * The share link of a saved configuration, editable in place.
 *
 * The link is the only form in which every field is visible at once, and the only one a person can
 * correct by hand: `fp=chrome` → `fp=firefox` used to cost a delete and a fresh paste. The text
 * goes through the same [Server.parse] the paste path uses, so a link the app cannot dial is
 * refused here — with the reason under the field — instead of becoming a broken configuration.
 *
 * Shared by both screens that list servers: one implementation, so the two cannot drift apart.
 */
object LinkEditor {
    fun show(
        context: Context,
        store: Store,
        server: Server,
        onSaved: (Server) -> Unit = {},
        onFailed: (String) -> Unit = {},
    ) {
        val input = EditText(context).apply {
            setText(server.link())
            setTextColor(Color.parseColor("#dce6ff"))
            setHintTextColor(Color.parseColor("#6b7280"))
            setHint("vless://…")
            // 13 × 1.3: ссылку правят глазами и на ходу, а не в лупу
            textSize = 17f
            setTypeface(Typeface.MONOSPACE)
            setBackgroundColor(Color.parseColor("#111111"))
            val pad = (12 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            minLines = 5
            maxLines = 14
            gravity = Gravity.TOP or Gravity.START
            setTextIsSelectable(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val holder = FrameLayout(context).apply {
            val side = (16 * context.resources.displayMetrics.density).toInt()
            val top = (8 * context.resources.displayMetrics.density).toInt()
            setPadding(side, top, side, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("ссылка конфигурации")
            .setView(holder)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Сохранить", null)
            .create()
        // the positive button is overridden: a bad link keeps the dialog open with the text in it
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text.toString()
                val parsed = Server.parse(raw)
                when {
                    raw.isBlank() -> input.error = "ссылка пустая"
                    parsed == null ->
                        input.error = "не ссылка: нужна vless://, vmess://, trojan:// или ss://"
                    else -> {
                        store.replace(server, parsed)
                        dialog.dismiss()
                        onSaved(parsed)
                    }
                }
            }
        }
        dialog.show()
    }
}
