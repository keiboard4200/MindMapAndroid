package jp.personal.mindmap

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.graphics.Color
import android.content.res.ColorStateList
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.RadioButton
import android.view.View
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.ArrayDeque

class MainActivity : Activity() {
    private lateinit var canvas: MindMapView
    private lateinit var titleView: TextView
    private lateinit var modeButton: Button
    private lateinit var mapPanel: LinearLayout
    private lateinit var todoPanel: TodoPanel
    private lateinit var mapTab: Button
    private lateinit var todoTab: Button
    private val todoStore by lazy { TodoStore(this) }
    private var savedLayout = AutoLayout.Settings(
        selectedBranchOnly = false,
        spacing = 220f,
        style = AutoLayout.Style.LEFT_TO_RIGHT,
        alignment = AutoLayout.Align.CENTER
    )
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var branchMode = false
    private var map = MapState.blank()
    private val archiveStore by lazy { ArchiveStore(this) }
    private var clipboardJson: String? = null
    private val createJsonRequest = 101
    private val openJsonRequest = 102
    private val archiveExportRequest = 103
    private val archiveImportRequest = 104
    private val todoExportRequest = 105
    private val todoImportRequest = 106
    private val notificationRequest = 107

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        map = try {
            val file = getFileStreamPath("mindmap.json")
            if (file.exists()) MapState.parse(file.readText(Charsets.UTF_8)) else MapState.blank()
        } catch (_: Exception) {
            MapState.blank()
        }
        savedLayout = readLayoutSettings()
        clipboardJson = getSharedPreferences("map_clipboard", MODE_PRIVATE)
            .getString("snapshot", null)
        TodoNotifications.createChannel(this)
        setupScreen()
        canvas.map = map
        canvas.post { canvas.fitAll() }
        todoStore.rescheduleAll()
        if (intent.getBooleanExtra("open_todo", false)) selectMode(true)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun actionButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(4), 0, dp(4), 0)
        setTextColor(Color.rgb(233, 242, 253))
        backgroundTintList = ColorStateList.valueOf(Color.rgb(37, 50, 67))
        setOnClickListener { onClick() }
    }

    private fun setupScreen() {
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 22, 32))
        }
        main.setOnApplyWindowInsetsListener { view, insets ->
            val safe = if (android.os.Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                else null
            if (safe != null) view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setContentView(main)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }
        mapTab = actionButton("マインドマップ") { selectMode(false) }
        todoTab = actionButton("ToDoリスト") { selectMode(true) }
        tabs.addView(mapTab, LinearLayout.LayoutParams(0, dp(49), 1f))
        tabs.addView(todoTab, LinearLayout.LayoutParams(0, dp(49), 1f))
        main.addView(tabs)

        mapPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), 0, dp(9), 0)
        }
        titleView = TextView(this).apply {
            text = map.title
            textSize = 16f
            setTextColor(Color.rgb(237, 244, 252))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { renameMap() }
        }
        top.addView(titleView, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        top.addView(actionButton("整列") { executeSavedLayout() },
            LinearLayout.LayoutParams(dp(56), dp(48)))
        top.addView(actionButton("設定") { showAutoLayoutDialog() },
            LinearLayout.LayoutParams(dp(54), dp(48)))
        top.addView(actionButton("全体") { canvas.fitAll() },
            LinearLayout.LayoutParams(dp(60), dp(48)))
        top.addView(actionButton("⋮") { showAppMenu() },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        mapPanel.addView(top)

        canvas = MindMapView(this)
        canvas.onSelectionChanged = { id ->
            branchMode = false
            canvas.groupDrag = false
            modeButton.text = "単独"
            titleView.text = if (id != null) {
                val node = map.find(id)
                "${node?.title ?: ""} · ${map.descendants(id).size}件"
            } else map.title
        }
        canvas.onEditRequested = { id -> showNodeMenu(id) }
        canvas.onDoubleTap = { id -> editNodeDetails(id) }
        canvas.onFoldClicked = { id ->
            val node = map.find(id)
            if (node != null && map.children(id).isNotEmpty()) {
                editTransaction { node.collapsed = !node.collapsed }
            }
        }
        canvas.onCommentClicked = { id ->
            map.find(id)?.let { node ->
                editTransaction { node.commentVisible = !node.commentVisible }
            }
        }
        canvas.onMoveFinished = { previous ->
            if (previous != map.asJson()) {
                pushUndo(previous)
                save()
            }
        }
        mapPanel.addView(canvas,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(3), dp(4), dp(4))
        }
        fun addBottom(label: String, click: () -> Unit): Button {
            val button = actionButton(label, click)
            bottom.addView(button, LinearLayout.LayoutParams(0, dp(54), 1f))
            return button
        }
        addBottom("＋ 子") { addChild() }
        modeButton = addBottom("単独") { toggleMoveMode() }
        addBottom("色") {
            canvas.selectedId?.let { colorMenu(it) }
                ?: toast("色を変えるノードを選択してください")
        }
        addBottom("コメント") {
            canvas.selectedId?.let { editNote(it) }
                ?: toast("コメントを書くノードを選択してください")
        }
        addBottom("削除") { deleteSelected() }
        addBottom("↶") { undo() }
        addBottom("↷") { redo() }
        mapPanel.addView(bottom)
        main.addView(mapPanel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        todoPanel = TodoPanel(
            this, todoStore,
            { requestTodoNotificationPermission() },
            { exportTodoJson() },
            { importTodoJson() }
        )
        main.addView(todoPanel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        selectMode(false)
    }

    private fun selectMode(todo: Boolean) {
        mapPanel.visibility = if (todo) View.GONE else View.VISIBLE
        todoPanel.visibility = if (todo) View.VISIBLE else View.GONE
        mapTab.backgroundTintList = ColorStateList.valueOf(
            if (!todo) Color.rgb(48, 111, 171) else Color.rgb(37, 50, 67))
        todoTab.backgroundTintList = ColorStateList.valueOf(
            if (todo) Color.rgb(48, 111, 171) else Color.rgb(37, 50, 67))
        if (todo) todoPanel.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_todo", false)) selectMode(true)
    }

    private fun requestTodoNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this).setTitle("ToDoの通知を許可")
                .setMessage("期限のリマインダーを受け取るには通知の許可が必要です。許可しなくてもToDoは保存できます。")
                .setNegativeButton("後で", null)
                .setPositiveButton("許可を設定") { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        notificationRequest)
                }.show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == notificationRequest) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
                toast("通知が有効になりました")
            else toast("通知が許可されていないためリマインダーは表示されません")
        }
    }

    private fun toggleMoveMode() {
        val id = canvas.selectedId ?: run {
            toast("移動するノードを先に選んでください")
            return
        }
        branchMode = !branchMode
        canvas.groupDrag = branchMode
        val count = map.descendants(id).size + 1
        modeButton.text = if (branchMode) "枝($count)" else "単独"
        toast(if (branchMode) "$count ノードをまとめて移動" else "選択ノードだけ移動")
    }

    private fun editTransaction(body: () -> Unit) {
        val previous = map.asJson()
        body()
        if (previous != map.asJson()) {
            pushUndo(previous)
            save()
            canvas.refresh()
        }
    }

    private fun pushUndo(previous: String) {
        undoStack.addLast(previous)
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun undo() {
        if (undoStack.isEmpty()) { toast("戻せる操作がありません"); return }
        redoStack.addLast(map.asJson())
        replaceMap(MapState.parse(undoStack.removeLast()), true)
    }

    private fun redo() {
        if (redoStack.isEmpty()) { toast("やり直せる操作がありません"); return }
        undoStack.addLast(map.asJson())
        replaceMap(MapState.parse(redoStack.removeLast()), true)
    }

    private fun replaceMap(next: MapState, preserveViewport: Boolean = false) {
        val oldSelection = canvas.selectedId
        map = next
        canvas.map = map
        canvas.select(if (preserveViewport && map.find(oldSelection) != null) oldSelection else null)
        titleView.text = map.title
        branchMode = false
        canvas.groupDrag = false
        modeButton.text = "単独"
        if (!preserveViewport) canvas.post { canvas.fitAll() }
        save()
    }

    private fun save() {
        try {
            val output = getFileStreamPath("mindmap.json")
            val temp = getFileStreamPath("mindmap.json.tmp")
            temp.writeText(map.asJson(), Charsets.UTF_8)
            if (!temp.renameTo(output)) {
                temp.copyTo(output, overwrite = true)
                temp.delete()
            }
        } catch (ex: Exception) {
            toast("保存失敗: ${ex.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun addChild() {
        val parentId = canvas.selectedId ?: if (map.roots().size == 1) map.root().id else {
            toast("複数のルートがあります。親にするノードを選択してください")
            return
        }
        // Input first: no default text to erase, and cancel creates no node.
        inputDialog("新しいノード", "", "ノード名を入力") { name ->
            lateinit var added: MindNode
            editTransaction {
                added = map.addChild(parentId, name.take(300), savedLayout)
            }
            canvas.select(added.id)
            canvas.focusOn(added.id)
        }
    }

    private fun addIndependentRoot() {
        inputDialog("独立したルートを追加", "", "ルートのタイトル") { name ->
            lateinit var added: MindNode
            editTransaction { added = map.addRoot(name) }
            canvas.select(added.id)
            canvas.focusOn(added.id)
            toast("独立したルートを追加しました")
        }
    }

    private fun copyNode(id: Long, includeChildren: Boolean) {
        try {
            val snapshot = map.copyJson(id, includeChildren)
            clipboardJson = snapshot
            getSharedPreferences("map_clipboard", MODE_PRIVATE).edit()
                .putString("snapshot", snapshot).apply()
            val count = MapState.parse(snapshot).nodes.size
            toast(if (includeChildren) "枝全体（$count ノード）をコピーしました"
                else "ノードだけコピーしました")
        } catch (ex: Exception) {
            showError("コピーに失敗しました", ex)
        }
    }

    private fun showPasteMenu() {
        val snapshot = clipboardJson ?: run {
            toast("先にノードをコピーしてください")
            return
        }
        val destination = canvas.selectedId
        val options = if (destination == null)
            arrayOf("独立したルートとして貼り付け")
        else arrayOf("独立したルートとして貼り付け", "選択ノードの子として貼り付け")
        AlertDialog.Builder(this).setTitle("貼り付け先を選択")
            .setItems(options) { _, which ->
                try {
                    val parentId = if (which == 1) destination else null
                    lateinit var pasted: MindNode
                    editTransaction { pasted = map.pasteJson(snapshot, parentId) }
                    canvas.select(pasted.id)
                    canvas.focusOn(pasted.id)
                    toast("枝を貼り付けました")
                } catch (ex: Exception) {
                    showError("貼り付けに失敗しました", ex)
                }
            }.setNegativeButton("キャンセル", null).show()
    }

    private fun archiveRoot(id: Long) {
        val node = map.find(id)
        if (node == null || node.parentId != null) {
            toast("アーカイブに保存できるのはルートノードのみです")
            return
        }
        try {
            val entry = archiveStore.saveRoot(map, id)
            toast("「${entry.title}」をアーカイブへコピー保存しました")
        } catch (ex: Exception) {
            showError("アーカイブ保存に失敗しました", ex)
        }
    }

    private fun showArchives() {
        val items = try {
            archiveStore.entries()
        } catch (ex: Exception) {
            showError("アーカイブの読み込みに失敗しました", ex)
            return
        }
        if (items.isEmpty()) {
            AlertDialog.Builder(this).setTitle("アーカイブ")
                .setMessage("保存されたルートはありません。ルートノードを長押しして保存できます。")
                .setPositiveButton("閉じる", null).show()
            return
        }
        val format = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.JAPAN)
        val labels = items.map {
            "${it.title}  (${it.nodeCount}件)\n${format.format(java.util.Date(it.savedAt))}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("ルートのアーカイブ")
            .setItems(labels) { _, index ->
                val entry = items[index]
                AlertDialog.Builder(this).setTitle(entry.title)
                    .setMessage("${entry.nodeCount}ノードの保存済みスナップショット。元のマップには影響しません。")
                    .setPositiveButton("独立ルートとして復元") { _, _ ->
                        try {
                            lateinit var restored: MindNode
                            editTransaction { restored = map.pasteJson(entry.json) }
                            canvas.select(restored.id)
                            canvas.focusOn(restored.id)
                            toast("アーカイブから復元しました")
                        } catch (ex: Exception) {
                            showError("アーカイブの復元に失敗しました", ex)
                        }
                    }
                    .setNeutralButton("アーカイブから削除") { _, _ ->
                        AlertDialog.Builder(this)
                            .setMessage("保存した「${entry.title}」をアーカイブから削除しますか？現在のマップには影響しません。")
                            .setNegativeButton("キャンセル", null)
                            .setPositiveButton("削除") { _, _ ->
                                try {
                                    archiveStore.delete(entry.id)
                                    toast("アーカイブから削除しました")
                                } catch (ex: Exception) {
                                    showError("アーカイブの削除に失敗しました", ex)
                                }
                            }.show()
                    }
                    .setNegativeButton("戻る", null).show()
            }
            .setNegativeButton("閉じる", null).show()
    }

    private fun showError(title: String, error: Exception) {
        AlertDialog.Builder(this).setTitle(title)
            .setMessage(error.message ?: "不明なエラー")
            .setPositiveButton("閉じる", null).show()
    }

    private fun renameMap() {
        inputDialog("マップ名", map.title) { input ->
            editTransaction { map.title = input.take(200) }
            titleView.text = map.title
        }
    }

    private fun renameNode(id: Long) {
        val node = map.find(id) ?: return
        inputDialog("ノードの名前", node.title) { input ->
            editTransaction { node.title = input.take(300) }
            canvas.select(id)
        }
    }

    private fun inputDialog(
        title: String, initial: String, hint: String = "入力してください",
        done: (String) -> Unit
    ) {
        val edit = EditText(this).apply {
            setSingleLine(false)
            maxLines = 4
            setText(initial)
            setHint(hint)
            selectAll()
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(edit)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("保存") { _, _ ->
                val result = edit.text.toString().trim()
                if (result.isNotEmpty()) done(result) else toast("空の名前は保存できません")
            }.create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.setOnShowListener {
            edit.requestFocus()
            val keyboard = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            keyboard.showSoftInput(edit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    private fun editNote(id: Long) {
        val node = map.find(id) ?: return
        val editor = EditText(this).apply {
            setText(node.note)
            hint = "コメントを入力"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 5
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("コメント：${node.title}")
            .setView(editor)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("保存") { _, _ ->
                editTransaction { node.note = editor.text.toString().take(20000) }
            }.show()
    }

    private fun editNodeDetails(id: Long) {
        val node = map.find(id) ?: return
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(8))
        }
        val titleLabel = TextView(this).apply { text = "タイトル" }
        val titleInput = EditText(this).apply {
            setText(node.title)
            setSelection(text.length)
            maxLines = 5
        }
        val noteLabel = TextView(this).apply {
            text = "コメント（ノードの下に表示）"
            setPadding(0, dp(14), 0, 0)
        }
        val noteInput = EditText(this).apply {
            setText(node.note)
            hint = "コメントを入力"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
        }
        form.addView(titleLabel)
        form.addView(titleInput)
        form.addView(noteLabel)
        form.addView(noteInput)
        val scroll = android.widget.ScrollView(this).apply { addView(form) }
        AlertDialog.Builder(this).setTitle("ノードを編集").setView(scroll)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("保存") { _, _ ->
                val newTitle = titleInput.text.toString().trim()
                if (newTitle.isBlank()) {
                    toast("タイトルは空欄にできません")
                } else {
                    editTransaction {
                        node.title = newTitle.take(300)
                        node.note = noteInput.text.toString().take(20000)
                        node.commentVisible = true
                    }
                }
            }.show()
    }

    private fun deleteSelected() {
        val id = canvas.selectedId ?: run {
            toast("削除するノードを選択してください")
            return
        }
        confirmDelete(id)
    }

    private fun confirmDelete(id: Long) {
        val node = map.find(id) ?: return
        if (node.parentId == null && map.roots().size <= 1) {
            toast("最後のルートは削除できません")
            return
        }
        val descendants = map.descendants(id).size
        val options = arrayOf(
            if (node.parentId == null) "このルートだけ削除（子を独立ルートに）"
            else "このノードだけ削除（子は親に付け替え）",
            "この枝を削除（子孫" + descendants + "件も削除）"
        )
        AlertDialog.Builder(this).setTitle("削除方法： " + node.title)
            .setItems(options) { _, choice ->
                val message = if (choice == 0)
                    if (node.parentId == null) "このルートだけ削除し、その子ノードを独立したルートにしますか？"
                    else "このノードだけを削除し、子ノードは上位の親に接続しますか？"
                    else "このノードと配下" + descendants + "件を完全に削除しますか？"
                AlertDialog.Builder(this).setMessage(message)
                    .setNegativeButton("キャンセル", null)
                    .setPositiveButton("削除") { _, _ ->
                        editTransaction {
                            if (choice == 0) map.deleteNodeOnly(id) else map.deleteBranch(id)
                        }
                        canvas.select(null)
                    }.show()
            }
            .setNegativeButton("キャンセル", null).show()
    }

    private fun showNodeMenu(id: Long) {
        val node = map.find(id) ?: return
        val isRoot = node.parentId == null
        val options = mutableListOf(
            "タイトルとコメントを編集",
            "子ノードを追加",
            "色を変更",
            "コメントを編集",
            if (node.collapsed) "子ノードを展開" else "子ノードを折りたたむ",
            "このノードを中心に表示",
            "ノードのみコピー",
            "配下の枝をすべてコピー",
            "貼り付け先を選ぶ",
            "削除"
        )
        if (isRoot) options.add("ルート全体をアーカイブに保存")
        AlertDialog.Builder(this).setTitle(node.title)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> editNodeDetails(id)
                    1 -> { canvas.select(id); addChild() }
                    2 -> colorMenu(id)
                    3 -> editNote(id)
                    4 -> if (map.children(id).isNotEmpty()) editTransaction {
                        node.collapsed = !node.collapsed
                    } else toast("子ノードがありません")
                    5 -> canvas.focusOn(id)
                    6 -> copyNode(id, false)
                    7 -> copyNode(id, true)
                    8 -> { canvas.select(id); showPasteMenu() }
                    9 -> confirmDelete(id)
                    10 -> if (isRoot) archiveRoot(id)
                }
            }.show()
    }

    private fun colorMenu(id: Long) {
        if (map.find(id) == null) return
        val names = listOf("青", "緑", "紫", "オレンジ", "赤", "シアン",
            "藍色", "ティール", "グレー", "ライム")
        val labels = names.mapIndexed { i, label ->
            SpannableString("●   $label").apply {
                setSpan(ForegroundColorSpan(MapState.palette[i]), 0, 1, 0)
            }
        }.toTypedArray<CharSequence>()
        AlertDialog.Builder(this).setTitle("選択ノードの色")
            .setItems(labels) { _, which ->
                editTransaction { map.find(id)?.color = MapState.palette[which] }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun readLayoutSettings(): AutoLayout.Settings {
        val prefs = getSharedPreferences("layout_settings", MODE_PRIVATE)
        val style = runCatching {
            AutoLayout.Style.valueOf(
                prefs.getString("style", AutoLayout.Style.LEFT_TO_RIGHT.name)!!
            )
        }.getOrDefault(AutoLayout.Style.LEFT_TO_RIGHT)
        val alignment = runCatching {
            AutoLayout.Align.valueOf(
                prefs.getString("alignment", AutoLayout.Align.CENTER.name)!!
            )
        }.getOrDefault(AutoLayout.Align.CENTER)
        val horizontal = runCatching {
            AutoLayout.Horizontal.valueOf(
                prefs.getString("horizontal", AutoLayout.Horizontal.CENTER.name)!!
            )
        }.getOrDefault(AutoLayout.Horizontal.CENTER)
        return AutoLayout.Settings(
            selectedBranchOnly = prefs.getBoolean("selected_only", false),
            spacing = prefs.getFloat("spacing", 220f).coerceIn(150f, 300f),
            style = style,
            alignment = alignment,
            horizontal = horizontal
        )
    }

    private fun persistLayoutSettings(layout: AutoLayout.Settings) {
        getSharedPreferences("layout_settings", MODE_PRIVATE).edit()
            .putString("style", layout.style.name)
            .putString("alignment", layout.alignment.name)
            .putString("horizontal", layout.horizontal.name)
            .putBoolean("selected_only", layout.selectedBranchOnly)
            .putFloat("spacing", layout.spacing)
            .apply()
    }

    private fun executeSavedLayout() {
        val id = canvas.selectedId
        if (savedLayout.selectedBranchOnly && id == null) {
            toast("枝だけ整列する設定です。先にノードを選択してください")
            return
        }
        var changed = 0
        editTransaction {
            changed = AutoLayout.apply(map, id, savedLayout)
        }
        if (changed == 0) {
            toast("移動するノードがありません")
        } else {
            if (!savedLayout.selectedBranchOnly) canvas.fitAll()
            toast("${changed}ノードを整列しました（Undo可能）")
        }
    }

    private fun showAutoLayoutDialog() {
        val selected = canvas.selectedId
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(10))
        }
        fun heading(label: String) = TextView(this).apply {
            text = label
            textSize = 14f
            setPadding(0, dp(12), 0, dp(5))
        }
        fun radio(group: RadioGroup, label: String): RadioButton =
            RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                group.addView(this)
            }

        val styleGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val radial = radio(styleGroup, "放射状")
        val leftRight = radio(styleGroup, "左→右のツリー")
        styleGroup.check(if (savedLayout.style == AutoLayout.Style.RADIAL) radial.id else leftRight.id)
        container.addView(heading("整列形式"))
        container.addView(styleGroup)

        val scopeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val whole = radio(scopeGroup, "マップ全体")
        val subtree = radio(scopeGroup, "選択ノードの枝のみ")
        subtree.isEnabled = selected != null
        scopeGroup.check(if (savedLayout.selectedBranchOnly && selected != null) subtree.id else whole.id)
        container.addView(heading("整列する範囲"))
        container.addView(scopeGroup)

        val spacingGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val compact = radio(spacingGroup, "コンパクト（150）")
        val regular = radio(spacingGroup, "標準（220）")
        val wide = radio(spacingGroup, "広め（300）")
        spacingGroup.check(when {
            savedLayout.spacing <= 180f -> compact.id
            savedLayout.spacing >= 260f -> wide.id
            else -> regular.id
        })
        container.addView(heading("間隔"))
        container.addView(spacingGroup)

        val alignmentGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val top = radio(alignmentGroup, "上詰め")
        val center = radio(alignmentGroup, "中央揃え")
        val bottom = radio(alignmentGroup, "下詰め")
        alignmentGroup.check(when (savedLayout.alignment) {
            AutoLayout.Align.TOP -> top.id
            AutoLayout.Align.CENTER -> center.id
            AutoLayout.Align.BOTTOM -> bottom.id
        })
        fun syncAlignmentEnabled() {
            val enabled = styleGroup.checkedRadioButtonId == leftRight.id
            top.isEnabled = enabled
            center.isEnabled = enabled
            bottom.isEnabled = enabled
        }
        styleGroup.setOnCheckedChangeListener { _, _ -> syncAlignmentEnabled() }
        syncAlignmentEnabled()
        container.addView(heading("枝の縦位置（左→右の整列時）"))
        container.addView(alignmentGroup)

        val horizontalGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val alignLeft = radio(horizontalGroup, "左詰め（ノード左端を揃える）")
        val alignCenter = radio(horizontalGroup, "中央揃え（ノード中心を揃える）")
        val alignRight = radio(horizontalGroup, "右詰め（ノード右端を揃える）")
        horizontalGroup.check(when (savedLayout.horizontal) {
            AutoLayout.Horizontal.LEFT -> alignLeft.id
            AutoLayout.Horizontal.CENTER -> alignCenter.id
            AutoLayout.Horizontal.RIGHT -> alignRight.id
        })
        container.addView(heading("階層内の横揃え（左→右の整列時）"))
        container.addView(horizontalGroup)

        fun syncHorizontalEnabled() {
            val enabled = styleGroup.checkedRadioButtonId == leftRight.id
            alignLeft.isEnabled = enabled
            alignCenter.isEnabled = enabled
            alignRight.isEnabled = enabled
        }
        // Both alignment groups only apply to the left-to-right tree style.
        styleGroup.setOnCheckedChangeListener { _, _ ->
            syncAlignmentEnabled()
            syncHorizontalEnabled()
        }
        syncHorizontalEnabled()

        container.addView(TextView(this).apply {
            text = "左詰め／中央揃え／右詰めは各階層のノード幅が違う場合の基準位置です。コメントを表示しているノードは、その全文の幅と高さを含めて間隔を確保します。非表示コメントは間隔に含めません。保存後は上部「整列」で即実行します。"
            textSize = 12f
            setPadding(0, dp(12), 0, dp(8))
        })
        val scroll = android.widget.ScrollView(this).apply { addView(container) }
        AlertDialog.Builder(this)
            .setTitle("整列設定")
            .setView(scroll)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("設定を保存") { _, _ ->
                val style = if (styleGroup.checkedRadioButtonId == radial.id)
                    AutoLayout.Style.RADIAL else AutoLayout.Style.LEFT_TO_RIGHT
                val alignment = when (alignmentGroup.checkedRadioButtonId) {
                    top.id -> AutoLayout.Align.TOP
                    bottom.id -> AutoLayout.Align.BOTTOM
                    else -> AutoLayout.Align.CENTER
                }
                val horizontal = when (horizontalGroup.checkedRadioButtonId) {
                    alignLeft.id -> AutoLayout.Horizontal.LEFT
                    alignRight.id -> AutoLayout.Horizontal.RIGHT
                    else -> AutoLayout.Horizontal.CENTER
                }
                savedLayout = AutoLayout.Settings(
                    selectedBranchOnly = scopeGroup.checkedRadioButtonId == subtree.id,
                    spacing = when (spacingGroup.checkedRadioButtonId) {
                        compact.id -> 150f
                        wide.id -> 300f
                        else -> 220f
                    },
                    style = style,
                    alignment = alignment,
                    horizontal = horizontal
                )
                persistLayoutSettings(savedLayout)
                toast("整列設定を保存しました")
            }.show()
    }

    private fun showAppMenu() {
        val options = arrayOf(
            "＋ 独立ルートを追加",
            "選択ノードをコピー",
            "選択ノードの枝をコピー",
            "クリップボードから貼り付け",
            "ルートをアーカイブに保存",
            "アーカイブ一覧・復元",
            "整列設定",
            "全体を整列（保存済み設定）",
            "全体にフィット",
            "新しいマップ",
            "100ノードのテストマップ",
            "JSONを書き出す（マップ＋整列＋ToDo）",
            "JSONを読み込む",
            "操作方法",
            "アーカイブを書き出す（バックアップ）",
            "アーカイブを読み込む"
        )
        AlertDialog.Builder(this).setTitle("マップ操作").setItems(options) { _, which ->
            when (which) {
                0 -> addIndependentRoot()
                1 -> canvas.selectedId?.let { copyNode(it, false) }
                    ?: toast("コピーするノードを選択してください")
                2 -> canvas.selectedId?.let { copyNode(it, true) }
                    ?: toast("コピーするノードを選択してください")
                3 -> showPasteMenu()
                4 -> canvas.selectedId?.let { archiveRoot(it) }
                    ?: toast("ルートノードを選択してください")
                5 -> showArchives()
                6 -> showAutoLayoutDialog()
                7 -> executeSavedLayout()
                8 -> canvas.fitAll()
                9 -> confirmNewMap(MapState.blank())
                10 -> confirmNewMap(MapState.sample100())
                11 -> exportJson()
                12 -> importJson()
                13 -> showHelp()
                14 -> exportArchive()
                15 -> importArchive()
            }
        }.show()
    }

    private fun confirmNewMap(next: MapState) {
        AlertDialog.Builder(this).setTitle("マップを切り替えますか？")
            .setMessage("現在のマップは端末内で上書きされます。必要なら先にJSONを書き出してください。")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("切り替え") { _, _ ->
                undoStack.clear()
                redoStack.clear()
                replaceMap(next)
            }.show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this).setTitle("操作方法").setMessage(
            "・ノードをタップ：選択\n" +
            "・ノードをドラッグ：単独または枝ごと移動\n" +
            "・空白をドラッグ：マップ移動\n" +
            "・2本指：拡大・縮小\n" +
            "・ノードを2回タップ：タイトル＋コメント編集\n" +
            "・左右のボタン：コメント表示／折りたたみ切り替え\n" +
            "・ノード長押し：編集・色変更・削除\n" +
            "・上部「整列」：保存した設定で即座に整列\n" +
            "・上部「設定」：整列設定を保存\n" +
            "・左の目：コメント表示切替／右の＋−：子ノード表示切替\n" +
            "・長押し：ノードのみ／枝ごとコピー、貼り付け、削除\n" +
            "・⋮：独立ルートの追加、ルートのアーカイブ\n" +
            "・全体：マップ全体を表示\n" +
            "・大切なデータはJSONでバックアップしてください"
        ).setPositiveButton("閉じる", null).show()
    }

    private fun confirmCombinedRestore(backup: BackupBundle.Restored) {
        val todoJson = backup.todoJson ?: return
        val layout = backup.layout ?: return
        val todoCount = org.json.JSONObject(todoJson).getJSONArray("tasks").length()
        AlertDialog.Builder(this).setTitle("バックアップを復元")
            .setMessage(
                "マインドマップ（${backup.map.nodes.size}ノード）、" +
                "整列設定、ToDo（${todoCount}件）を復元します。" +
                "現在のマップ・整列設定・ToDoは上書きされます。" +
                "アーカイブには影響しません。続けますか？"
            )
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("すべて復元") { _, _ ->
                val previousMap = map.asJson()
                val previousTodos = todoStore.exportJson()
                val previousLayout = savedLayout
                try {
                    // All sections are validated before confirming. Apply the
                    // backup only after the user explicitly approves replacement.
                    todoStore.importJson(todoJson)
                    savedLayout = layout
                    persistLayoutSettings(layout)
                    replaceMap(backup.map)
                    undoStack.clear()
                    redoStack.clear()
                    todoPanel.refresh()
                    toast("マップ・整列設定・ToDoを復元しました")
                } catch (ex: Exception) {
                    runCatching {
                        todoStore.importJson(previousTodos)
                        savedLayout = previousLayout
                        persistLayoutSettings(previousLayout)
                        replaceMap(MapState.parse(previousMap))
                        todoPanel.refresh()
                    }
                    showError("復元に失敗しました", ex)
                }
            }.show()
    }

    private fun exportJson() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "mindmap-full-backup.json")
        }
        startActivityForResult(intent, createJsonRequest)
    }

    private fun importJson() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, openJsonRequest)
    }

    private fun exportArchive() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "mindmap-archive-backup.json")
        }
        startActivityForResult(intent, archiveExportRequest)
    }

    private fun importArchive() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, archiveImportRequest)
    }

    private fun exportTodoJson() {
        val request = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "mindmap-todos-backup.json")
        }
        startActivityForResult(request, todoExportRequest)
    }

    private fun importTodoJson() {
        val request = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(request, todoImportRequest)
    }

    @Deprecated("Android file picker callback for the dependency-free prototype")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            when (requestCode) {
                createJsonRequest -> {
                    val stream = contentResolver.openOutputStream(uri)
                        ?: error("書き込み先を開けません")
                    val backup = BackupBundle.export(map, savedLayout, todoStore.exportJson())
                    stream.use { it.write(backup.toByteArray(Charsets.UTF_8)) }
                    toast("マップ・整列設定・ToDoをJSONに保存しました")
                }
                openJsonRequest -> {
                    val input = contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("ファイルを開けません")
                    val parsed = BackupBundle.parse(input)
                    parsed.todoJson?.let { todoStore.validateJson(it) }
                    if (parsed.isCombined) confirmCombinedRestore(parsed)
                    else confirmNewMap(parsed.map)
                }
                archiveExportRequest -> {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(archiveStore.exportJson().toByteArray(Charsets.UTF_8))
                    } ?: error("出力先を開けません")
                    toast("アーカイブのバックアップを書き出しました")
                }
                todoExportRequest -> {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(todoStore.exportJson().toByteArray(Charsets.UTF_8))
                    } ?: error("ToDoの書き出し先を開けません")
                    toast("ToDoのJSONを書き出しました")
                }
                todoImportRequest -> {
                    val text = contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("ToDoファイルを開けません")
                    AlertDialog.Builder(this).setTitle("ToDoを取り込む")
                        .setMessage("現在のToDoリストとタスクは、選択したJSONの内容に置き換わります。続けますか？")
                        .setNegativeButton("キャンセル", null)
                        .setPositiveButton("置き換える") { _, _ ->
                            try {
                                todoStore.importJson(text)
                                todoPanel.refresh()
                                toast("ToDoを読み込みました")
                            } catch (ex: Exception) {
                                showError("ToDoの読み込みに失敗しました", ex)
                            }
                        }.show()
                }
                archiveImportRequest -> {
                    val text = contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("アーカイブのバックアップを開けません")
                    AlertDialog.Builder(this).setTitle("アーカイブを取り込み")
                        .setMessage("バックアップの保存済みルートを追加します。現在のアーカイブは残ります。")
                        .setNegativeButton("キャンセル", null)
                        .setPositiveButton("取り込む") { _, _ ->
                            try {
                                val count = archiveStore.importJson(text)
                                toast("${count}件のアーカイブを取り込みました")
                            } catch (ex: Exception) {
                                showError("アーカイブの取り込みに失敗しました", ex)
                            }
                        }.show()
                }
            }
        } catch (ex: Exception) {
            AlertDialog.Builder(this).setTitle("ファイル操作エラー")
                .setMessage(ex.message ?: "不明なエラー")
                .setPositiveButton("閉じる", null).show()
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
