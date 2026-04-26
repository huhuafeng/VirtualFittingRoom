package com.virtualfittingroom.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.widget.TextView

/**
 * Scrolling debug log panel — shows real-time pipeline status like a live chat overlay.
 * Positioned at bottom-left of the screen.
 */
class DebugLogView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    private val lines = mutableListOf<String>()
    private val maxLines = 20

    private var expanded = false
    private val collapsedHeightPx: Int
    private val expandedHeightPx: Int

    init {
        setBackgroundColor(Color.parseColor("#B3000000"))
        setTextColor(Color.parseColor("#00FF00"))
        textSize = 10f
        typeface = Typeface.MONOSPACE
        setPadding(8, 6, 8, 6)

        val density = resources.displayMetrics.density
        collapsedHeightPx = (180 * density).toInt()
        expandedHeightPx = (resources.displayMetrics.heightPixels * 0.6).toInt()
    }

    /** Toggle between expanded and collapsed state. */
    fun toggle() {
        expanded = !expanded
        val params = layoutParams ?: return
        params.height = if (expanded) expandedHeightPx else collapsedHeightPx
        layoutParams = params
    }

    /** Append a log line. Thread-safe via post. */
    fun log(line: String) {
        post {
            lines.add(line)
            if (lines.size > maxLines) {
                lines.removeAt(0)
            }
            text = lines.joinToString("\n")
        }
    }

    /** Clear all lines. */
    fun clear() {
        post {
            lines.clear()
            text = ""
        }
    }
}
