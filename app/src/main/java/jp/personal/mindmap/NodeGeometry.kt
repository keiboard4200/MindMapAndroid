package jp.personal.mindmap

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.max

/**
 * Single measurement source for drawing, hit-testing, zoom-to-fit and automatic alignment.
 * Units here are unzoomed map coordinates (same as MindNode.x/y).
 */
object NodeGeometry {
    data class Box(
        val width: Float,
        val height: Float,
        val titleLayout: StaticLayout,
        val commentLayout: StaticLayout?,
        val commentWidth: Float,
        val commentHeight: Float
    ) {
        val visualWidth: Float get() = max(width, commentWidth)
        val top: Float get() = height / 2f
        val bottom: Float get() = height / 2f +
            if (commentLayout != null) 7f + commentHeight else 0f
        val visualHeight: Float get() = top + bottom
        val collisionRadius: Float
            get() = kotlin.math.hypot(visualWidth / 2f, max(top, bottom))
    }

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val notePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(209, 222, 236)
        textSize = 12f
    }

    private fun wrap(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(24))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()

    fun measure(node: MindNode): Box {
        val longestTitle = node.title.split('\n')
            .maxOfOrNull { titlePaint.measureText(it) } ?: 0f
        val bodyWidth = (longestTitle + 70f).coerceIn(116f, 318f)
        val title = wrap(node.title, titlePaint, (bodyWidth - 68f).toInt())
        val bodyHeight = max(44f, title.height + 20f)
        val comment = if (node.note.isNotBlank() && node.commentVisible) {
            val longest = node.note.split('\n')
                .maxOfOrNull { notePaint.measureText(it) } ?: 0f
            val width = (longest + 8f).coerceIn(110f, 310f).toInt()
            wrap(node.note, notePaint, width)
        } else null
        return Box(
            bodyWidth, bodyHeight, title, comment,
            comment?.width?.toFloat() ?: 0f,
            comment?.height?.toFloat() ?: 0f
        )
    }
}
