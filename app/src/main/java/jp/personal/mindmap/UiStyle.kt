package jp.personal.mindmap

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** A consistent native Android dark palette without additional UI libraries. */
object UiStyle {
    val background = Color.rgb(12, 20, 33)
    val panel = Color.rgb(19, 32, 49)
    val raised = Color.rgb(29, 45, 64)
    val accent = Color.rgb(79, 159, 226)
    val accentSurface = Color.rgb(35, 92, 146)
    val text = Color.rgb(237, 244, 253)
    val muted = Color.rgb(163, 184, 205)
    val line = Color.rgb(48, 66, 86)

    fun rounded(context: Context, color: Int = raised, radiusDp: Float = 13f,
                border: Int = line): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(color)
            setStroke((1f * density).toInt().coerceAtLeast(1), border)
        }
    }
}
