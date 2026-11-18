package ru.uvw.byecensor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The few pieces every screen uses.
 *
 * No XML layouts: the app is four screens of text and switches, and building them in code keeps
 * the whole interface in one place where a change is visible.
 */
object Ui {
    private fun dip(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** A card: rounded background, padding, full width. */
    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dip(context, 16), dip(context, 14), dip(context, 16), dip(context, 14))
        setBackgroundColor(Color.parseColor("#161616"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dip(context, 12), dip(context, 6), dip(context, 12), 0) }
    }

    fun title(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 18f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
    }

    fun line(context: Context, text: String, mono: Boolean = false): TextView = TextView(context).apply {
        this.text = text
        textSize = if (mono) 12f else 14f
        setTextColor(if (mono) Color.parseColor("#9a9a9a") else Color.parseColor("#c9c9c9"))
        if (mono) setTypeface(Typeface.MONOSPACE)
        setPadding(0, dip(context, 4), 0, 0)
    }

    fun state(context: Context, text: String, good: Boolean): TextView = TextView(context).apply {
        this.text = text
        textSize = 15f
        setTextColor(if (good) Color.parseColor("#7bd88f") else Color.parseColor("#e07a7a"))
        setPadding(0, dip(context, 2), 0, 0)
    }

    /** A wide button, the shape of the one thing a screen wants to be pressed. */
    fun button(context: Context, text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dip(context, 14), 0, dip(context, 14))
            setBackgroundColor(Color.parseColor("#2b2b2b"))
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dip(context, 12), dip(context, 8), dip(context, 12), dip(context, 8)) }
        }

    /** A settings row: title, state, explanation, and a tap. */
    fun row(
        context: Context,
        title: String,
        value: String,
        hint: String,
        onTap: () -> Unit = {},
    ): View {
        val card = card(context)
        card.addView(this.title(context, title))
        if (value.isNotEmpty()) card.addView(line(context, value))
        card.addView(line(context, hint, mono = false))
        card.isClickable = true
        card.setOnClickListener { onTap() }
        return card
    }
}
