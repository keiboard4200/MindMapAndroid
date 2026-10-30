package jp.personal.mindmap

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MapManagerDialog(
    private val activity: Activity,
    private val library: MapLibrary,
    private val onCreate: () -> Unit,
    private val onOpen: (Long) -> Unit,
    private val onRename: (Long) -> Unit,
    private val onDuplicate: (Long) -> Unit,
    private val onDelete: (Long) -> Unit
) {
    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density + .5f).toInt()
    private fun button(title: String): Button = Button(activity).apply {
        text = title
        isAllCaps = false
        textSize = 12f
        minWidth = 0; minimumWidth = 0
        minHeight = 0; minimumHeight = 0
        setTextColor(UiStyle.text)
        background = UiStyle.rounded(activity)
        elevation = 0f
    }

    fun show() {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(10))
        }
        var dialog: AlertDialog? = null
        container.addView(button("＋  新しいマップを作成").apply {
            background = UiStyle.rounded(activity, UiStyle.accentSurface)
            setOnClickListener { dialog?.dismiss(); onCreate() }
        }, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(13) })

        val dateFormat = SimpleDateFormat("yy/MM/dd HH:mm", Locale.JAPAN)
        for (entry in library.entries.sortedByDescending { it.modifiedAt }) {
            val selected = entry.id == library.activeId
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = UiStyle.rounded(activity,
                    if (selected) UiStyle.accentSurface else UiStyle.raised, 14f,
                    if (selected) UiStyle.accent else UiStyle.line)
                setPadding(dp(14), dp(11), dp(12), dp(10))
            }
            card.addView(TextView(activity).apply {
                text = (if (selected) "●  " else "◇  ") + entry.map.title
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(UiStyle.text)
                maxLines = 4
            })
            card.addView(TextView(activity).apply {
                text = entry.map.nodes.size.toString() + " ノード  •  " +
                    entry.map.roots().size + " ルート  •  " +
                    dateFormat.format(Date(entry.modifiedAt))
                textSize = 11f
                setTextColor(UiStyle.muted)
                setPadding(0, dp(4), 0, dp(9))
            })
            val buttons = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            buttons.addView(button(if (selected) "表示中" else "このマップを開く").apply {
                isEnabled = !selected
                if (selected) alpha = 0.55f
                setOnClickListener { dialog?.dismiss(); onOpen(entry.id) }
            }, LinearLayout.LayoutParams(0, dp(45), 1f).apply { marginEnd = dp(8) })
            buttons.addView(button("管理 ⋯").apply {
                setOnClickListener {
                    dialog?.dismiss()
                    AlertDialog.Builder(activity).setTitle(entry.map.title)
                        .setItems(arrayOf("名前を変更", "複製する", "マップを削除")) { _, which ->
                            when (which) {
                                0 -> onRename(entry.id)
                                1 -> onDuplicate(entry.id)
                                2 -> onDelete(entry.id)
                            }
                        }.setNegativeButton("キャンセル", null).show()
                }
            }, LinearLayout.LayoutParams(dp(90), dp(45)))
            card.addView(buttons)
            container.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(9)
            })
        }
        val scroll = ScrollView(activity).apply { addView(container) }
        dialog = AlertDialog.Builder(activity)
            .setTitle("マップライブラリ  •  " + library.size + " 件")
            .setView(scroll).setPositiveButton("閉じる", null).create()
        dialog.show()
    }
}
