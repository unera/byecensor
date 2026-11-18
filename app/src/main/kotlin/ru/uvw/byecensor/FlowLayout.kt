package ru.uvw.byecensor

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * A row of plates that wraps.
 *
 * The platform has no wrapping linear layout, and a domain plate should be as wide as its text:
 * three short names fit on one line, one long name takes the line. Measuring children once and
 * laying them out by their own widths is all it takes.
 */
class FlowLayout(context: Context) : ViewGroup(context) {
    init {
        setPadding(0, 0, 0, 0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = width - paddingLeft - paddingRight
        var lineWidth = 0
        var lineHeight = 0
        var totalHeight = 0
        var maxLineWidth = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val params = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + params.leftMargin + params.rightMargin
            val childHeight = child.measuredHeight + params.topMargin + params.bottomMargin
            if (lineWidth > 0 && lineWidth + childWidth > available) {
                totalHeight += lineHeight
                maxLineWidth = maxOf(maxLineWidth, lineWidth)
                lineWidth = 0
                lineHeight = 0
            }
            lineWidth += childWidth
            lineHeight = maxOf(lineHeight, childHeight)
        }
        totalHeight += lineHeight
        maxLineWidth = maxOf(maxLineWidth, lineWidth)
        setMeasuredDimension(
            resolveSize(maxLineWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(totalHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val available = right - left - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val params = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + params.leftMargin + params.rightMargin
            val childHeight = child.measuredHeight + params.topMargin + params.bottomMargin
            if (x > paddingLeft && x + childWidth > paddingLeft + available) {
                x = paddingLeft
                y += lineHeight
                lineHeight = 0
            }
            child.layout(
                x + params.leftMargin,
                y + params.topMargin,
                x + params.leftMargin + child.measuredWidth,
                y + params.topMargin + child.measuredHeight,
            )
            x += childWidth
            lineHeight = maxOf(lineHeight, childHeight)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: android.util.AttributeSet?): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun checkLayoutParams(params: LayoutParams?): Boolean = params is MarginLayoutParams
}
