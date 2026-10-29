package jp.personal.mindmap

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TodoPanel(
    private val activity: Activity,
    private val store: TodoStore,
    private val requestNotificationPermission: () -> Unit,
    private val exportBackup: () -> Unit,
    private val importBackup: () -> Unit
) : LinearLayout(activity) {
    private val bg = Color.rgb(16, 22, 32)
    private val fg = Color.rgb(237, 244, 252)
    private val muted = Color.rgb(177, 196, 218)
    private val surface = Color.rgb(35, 48, 65)
    private val format = SimpleDateFormat("yyyy/MM/dd (E) HH:mm", Locale.JAPAN)
    private val completedFormat = SimpleDateFormat("yy/MM/dd_HH:mm", Locale.JAPAN)
    private val listButton = button("")
    private val stats = label("", 13f, muted)
    private val content = LinearLayout(activity).apply { orientation = VERTICAL }
    private val filterButtons = mutableListOf<Button>()
    private var filter = 0

    init {
        orientation = VERTICAL
        setBackgroundColor(bg)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        val top = row()
        top.addView(listButton, LayoutParams(0, dp(48), 1f))
        listButton.setOnClickListener { chooseList() }
        top.addView(button("＋ リスト").apply { setOnClickListener { newList() } },
            LayoutParams(dp(95), dp(48)))
        addView(top)

        val info = row()
        info.addView(stats, LayoutParams(0, dp(46), 1f))
        info.addView(button("データ").apply { setOnClickListener { dataMenu() } },
            LayoutParams(dp(75), dp(44)))
        addView(info)

        val filters = row()
        listOf("未完了", "すべて", "完了").forEachIndexed { index, text ->
            val button = button(text).apply { setOnClickListener { filter = index; refresh() } }
            filterButtons.add(button)
            filters.addView(button, LayoutParams(0, dp(44), 1f))
        }
        addView(filters)
        val scroll = ScrollView(activity).apply { addView(content); isFillViewport = true }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(button("＋ ToDoを追加").apply { setOnClickListener { editTask(null) } },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(55)))
        refresh()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + .5f).toInt()
    private fun button(t: String): Button = Button(activity).apply {
        text = t; isAllCaps = false; textSize = 12f
        minHeight = 0; minimumHeight = 0
        setTextColor(fg)
        backgroundTintList = ColorStateList.valueOf(surface)
        setPadding(dp(6), 0, dp(6), 0)
    }
    private fun label(t: String, size: Float = 14f, color: Int = fg): TextView =
        TextView(activity).apply {
            text = t; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        }
    private fun row() = LinearLayout(activity).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    }
    private fun toast(t: String) = Toast.makeText(activity, t, Toast.LENGTH_SHORT).show()

    fun refresh() {
        listButton.text = "☷ " + store.currentList().name
        val tasks = store.tasks.filter { it.listId == store.currentListId }
        val open = tasks.count { !it.done }
        val overdue = tasks.count { !it.done &&
            it.dueAt != null && it.dueAt!! < System.currentTimeMillis() }
        stats.text = "未完了 " + open + " / 全" + tasks.size + "件" +
            if (overdue > 0) " · 期限超過" + overdue + "件" else ""
        filterButtons.forEachIndexed { index, button ->
            button.backgroundTintList = ColorStateList.valueOf(
                if (filter == index) Color.rgb(46, 106, 158) else surface)
        }
        content.removeAllViews()
        val visible = tasks.filter {
            if (filter == 0) !it.done else if (filter == 2) it.done else true
        }.sortedWith(compareBy<TodoTask> { it.done }
            .thenBy { it.dueAt ?: Long.MAX_VALUE }
            .thenByDescending { it.priority }
            .thenBy { it.id })
        if (visible.isEmpty()) content.addView(label(
            "ToDoがありません。下のボタンから追加できます。", 14f, muted).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(70), 0, dp(70))
            })
        for (task in visible) {
            val card = LinearLayout(activity).apply {
                orientation = VERTICAL
                setBackgroundColor(surface)
                setPadding(dp(8), dp(5), dp(8), dp(7))
            }
            val row = row()
            row.addView(CheckBox(activity).apply {
                isChecked = task.done
                contentDescription = "完了を切り替える: " + task.title
                setOnCheckedChangeListener { _, checked ->
                    store.toggleDone(task.id, checked)
                    refresh()
                }
            }, LayoutParams(dp(48), dp(48)))
            row.addView(label(task.title, 15f, if (task.done) muted else fg).apply {
                if (task.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                setOnClickListener { editTask(task) }
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("編集").apply {
                setOnClickListener { editTask(task) }
            }, LayoutParams(dp(62), dp(40)))
            card.addView(row)
            val date = task.dueAt
            val badges = mutableListOf<String>()
            if (date != null) badges.add("期限: " + format.format(Date(date)))
            if (task.done) badges.add(
                if (task.completedAt != null)
                    "完了: " + completedFormat.format(Date(task.completedAt!!))
                else "完了: 日時未記録（旧版）"
            )
            if (task.priority == 2) badges.add("重要")
            if (task.remindBeforeMinutes >= 0 && date != null) badges.add(
                "通知: " + when (task.remindBeforeMinutes) {
                    0 -> "期限時"; 60 -> "1時間前"; else -> "前日"
                })
            if (badges.isNotEmpty()) card.addView(label(badges.joinToString(" · "), 12f,
                if (!task.done && date != null && date < System.currentTimeMillis())
                    Color.rgb(255, 151, 139) else muted
            ).apply { setPadding(dp(48), 0, 0, dp(4)) })
            if (task.memo.isNotBlank()) card.addView(label(task.memo, 12f, muted).apply {
                setPadding(dp(48), 0, 0, dp(4)); maxLines = 4
            })
            content.addView(card, LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
    }

    private fun chooseList() {
        val lists = store.lists.toList()
        AlertDialog.Builder(activity).setTitle("ToDoリストの切替")
            .setItems(lists.map { it.name }.toTypedArray()) { _, index ->
                store.currentListId = lists[index].id
                store.persistSelection()
                refresh()
            }.setNeutralButton("＋ リスト") { _, _ -> newList() }
            .setNegativeButton("閉じる", null).show()
    }

    private fun newList() {
        val edit = EditText(activity).apply {
            hint = "例：仕事、買い物"
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(activity).setTitle("新しいリスト").setView(edit)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("作成") { _, _ ->
                val value = edit.text.toString().trim()
                if (value.isEmpty()) toast("リスト名を入力してください")
                else try {
                    store.addList(value); filter = 0; refresh()
                } catch (ex: Exception) { toast(ex.message ?: "保存できません") }
            }.show()
    }

    private fun dataMenu() {
        AlertDialog.Builder(activity).setTitle("ToDoデータ")
            .setItems(arrayOf("ToDoをJSONで書き出す",
                "ToDoのJSONを読み込む", "現在のリストを削除")) { _, index ->
                when (index) {
                    0 -> exportBackup()
                    1 -> importBackup()
                    2 -> deleteCurrentList()
                }
            }.show()
    }

    private fun deleteCurrentList() {
        if (store.lists.size == 1) { toast("最後のリストは削除できません"); return }
        val list = store.currentList()
        val total = store.tasks.count { it.listId == list.id }
        AlertDialog.Builder(activity).setTitle("リストを削除")
            .setMessage("「" + list.name + "」と" + total +
                "件のToDoを削除します。元に戻せません。")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("削除") { _, _ ->
                store.deleteList(list.id); filter = 0; refresh()
            }.show()
    }

    private fun editTask(existing: TodoTask?) {
        val src = existing ?: TodoTask(0, store.currentListId, "")
        val calendar = Calendar.getInstance().apply {
            if (src.dueAt != null) timeInMillis = src.dueAt!!
            else {
                add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 18)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }
        val form = LinearLayout(activity).apply {
            orientation = VERTICAL; setPadding(dp(20), dp(5), dp(20), dp(8))
        }
        fun heading(t: String) = form.addView(label(t, 12f, muted).apply {
            setPadding(0, dp(11), 0, dp(4))
        })
        heading("やること")
        val title = EditText(activity).apply {
            setText(src.title); hint = "ToDoを入力"; maxLines = 3
        }
        form.addView(title)
        heading("メモ")
        val memo = EditText(activity).apply {
            setText(src.memo); hint = "補足・手順（任意）"
            minLines = 2; maxLines = 5
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        form.addView(memo)
        val due = CheckBox(activity).apply {
            text = "期限を設定"; isChecked = src.dueAt != null
        }
        form.addView(due)
        val date = button("")
        val time = button("")
        fun updateDate() {
            date.text = SimpleDateFormat("yyyy/MM/dd (E)", Locale.JAPAN)
                .format(Date(calendar.timeInMillis))
            time.text = SimpleDateFormat("HH:mm", Locale.JAPAN)
                .format(Date(calendar.timeInMillis))
        }
        updateDate()
        val dateRow = row()
        dateRow.addView(date, LayoutParams(0, dp(48), 2f))
        dateRow.addView(time, LayoutParams(0, dp(48), 1f))
        form.addView(dateRow)
        date.setOnClickListener {
            DatePickerDialog(activity, { _, year, month, day ->
                calendar.set(year, month, day); updateDate()
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)).show()
        }
        time.setOnClickListener {
            TimePickerDialog(activity, { _, hour, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, minute)
                calendar.set(Calendar.SECOND, 0)
                updateDate()
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
        }
        heading("通知")
        val reminder = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                listOf("通知しない", "期限になったら", "1時間前", "前日"))
            setSelection(when (src.remindBeforeMinutes) {
                0 -> 1; 60 -> 2; 1440 -> 3; else -> 0
            })
        }
        form.addView(reminder)
        due.setOnCheckedChangeListener { _, checked ->
            date.isEnabled = checked
            time.isEnabled = checked
            reminder.isEnabled = checked
        }
        date.isEnabled = due.isChecked
        time.isEnabled = due.isChecked
        reminder.isEnabled = due.isChecked
        heading("優先度")
        val priority = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                listOf("低", "普通", "高"))
            setSelection(src.priority.coerceIn(0,2))
        }
        form.addView(priority)
        val scroll = ScrollView(activity).apply { addView(form) }
        AlertDialog.Builder(activity)
            .setTitle(if (existing == null) "ToDoを追加" else "ToDoを編集")
            .setView(scroll)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("保存") { _, _ ->
                val name = title.text.toString().trim()
                if (name.isBlank()) {
                    toast("内容を入力してください")
                } else {
                    val dueAt = if (due.isChecked) calendar.timeInMillis else null
                    val minutes = if (dueAt == null) -1 else
                        listOf(-1, 0, 60, 1440)[reminder.selectedItemPosition.coerceIn(0,3)]
                    val edited = src.copy(
                        title = name.take(300), memo = memo.text.toString().take(20000),
                        dueAt = dueAt, remindBeforeMinutes = minutes,
                        priority = priority.selectedItemPosition.coerceIn(0,2)
                    )
                    try {
                        if (existing == null) store.addTask(edited) else store.saveTask(edited)
                        refresh()
                        if (minutes >= 0) {
                            requestNotificationPermission()
                            if (dueAt!! - minutes * 60_000L <= System.currentTimeMillis())
                                toast("通知時刻が過去のため予約されません")
                            else toast("通知を予約しました（遅れる場合があります）")
                        }
                    } catch (ex: Exception) { toast(ex.message ?: "保存失敗") }
                }
            }
            .setNeutralButton(if (existing == null) "閉じる" else "削除") { _, _ ->
                if (existing != null) AlertDialog.Builder(activity)
                    .setMessage("「" + existing.title + "」を削除しますか？")
                    .setNegativeButton("キャンセル", null)
                    .setPositiveButton("削除") { _, _ ->
                        store.deleteTask(existing.id); refresh()
                    }.show()
            }.show()
    }
}
