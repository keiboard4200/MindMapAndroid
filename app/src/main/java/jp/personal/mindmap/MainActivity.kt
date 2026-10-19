package jp.personal.mindmap

import android.app.Activity
import android.app.AlertDialog
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
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var branchMode = false
    private var map = MapState.blank()
    private val createJsonRequest = 101
    private val openJsonRequest = 102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        map = try {
            val file = getFileStreamPath("mindmap.json")
            if (file.exists()) MapState.parse(file.readText(Charsets.UTF_8)) else MapState.blank()
        } catch (_: Exception) {
            MapState.blank()
        }
        setupScreen()
        canvas.map = map
        canvas.post { canvas.fitAll() }
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
        top.addView(actionButton("整列") { showAutoLayoutDialog() },
            LinearLayout.LayoutParams(dp(60), dp(48)))
        top.addView(actionButton("全体") { canvas.fitAll() },
            LinearLayout.LayoutParams(dp(60), dp(48)))
        top.addView(actionButton("⋮") { showAppMenu() },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        main.addView(top)

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
        canvas.onDoubleTap = { id -> renameNode(id) }
        canvas.onMoveFinished = { previous ->
            if (previous != map.asJson()) {
                pushUndo(previous)
                save()
            }
        }
        main.addView(canvas,
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
        addBottom("↶") { undo() }
        addBottom("↷") { redo() }
        main.addView(bottom)
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
        val parentId = canvas.selectedId ?: map.root().id
        // Input first: no default text to erase, and cancel creates no node.
        inputDialog("新しいノード", "", "ノード名を入力") { name ->
            lateinit var added: MindNode
            editTransaction {
                added = map.addChild(parentId)
                added.title = name.take(300)
            }
            canvas.select(added.id)
            canvas.focusOn(added.id)
        }
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

    private fun showNodeMenu(id: Long) {
        val node = map.find(id) ?: return
        val names = arrayOf("名前を編集", "子ノードを追加", "色を変更",
            if (node.collapsed) "展開" else "折りたたむ", "このノードを中心に表示", "この枝を削除")
        AlertDialog.Builder(this).setTitle(node.title).setItems(names) { _, which ->
            when (which) {
                0 -> renameNode(id)
                1 -> { canvas.select(id); addChild() }
                2 -> colorMenu(id)
                3 -> editTransaction { node.collapsed = !node.collapsed }
                4 -> canvas.focusOn(id)
                5 -> if (id == map.root().id) toast("中心ノードは削除できません") else
                    AlertDialog.Builder(this)
                        .setMessage("選択したノードと子孫をすべて削除しますか？")
                        .setNegativeButton("キャンセル", null)
                        .setPositiveButton("削除") { _, _ ->
                            editTransaction { map.deleteBranch(id) }
                            canvas.select(null)
                        }.show()
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
        val subtreeBox = CheckBox(this).apply {
            text = "配下のノードにも同じ色を適用"
            setPadding(dp(20), dp(10), dp(8), dp(8))
        }
        AlertDialog.Builder(this).setTitle("ノードの色")
            .setView(subtreeBox)
            .setItems(labels) { _, which ->
                editTransaction {
                    val node = map.find(id) ?: return@editTransaction
                    val targets = if (subtreeBox.isChecked)
                        listOf(node) + map.descendants(id) else listOf(node)
                    targets.forEach { it.color = MapState.palette[which] }
                }
            }.setNegativeButton("キャンセル", null).show()
    }

    private fun showAutoLayoutDialog() {
        val selected = canvas.selectedId
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        fun heading(label: String) = TextView(this).apply {
            text = label
            textSize = 14f
            setPadding(0, dp(12), 0, dp(4))
        }
        val scope = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val all = RadioButton(this).apply {
            id = View.generateViewId()
            text = "マップ全体"
            isChecked = true
        }
        val branch = RadioButton(this).apply {
            id = View.generateViewId()
            text = "選択ノードの枝のみ"
            isEnabled = selected != null
        }
        scope.addView(all)
        scope.addView(branch)
        val layoutStyle = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val radialStyle = RadioButton(this).apply {
            id = View.generateViewId()
            text = "放射状（中心から全方向）"
            isChecked = true
        }
        val rightTreeStyle = RadioButton(this).apply {
            id = View.generateViewId()
            text = "左→右（ルートを左に配置）"
        }
        layoutStyle.addView(radialStyle)
        layoutStyle.addView(rightTreeStyle)
        section.addView(heading("整列形式"))
        section.addView(layoutStyle)
        section.addView(heading("整列する範囲"))
        section.addView(scope)
        val spacing = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val compact = RadioButton(this).apply {
            id = View.generateViewId()
            text = "コンパクト（150）"
        }
        val normal = RadioButton(this).apply {
            id = View.generateViewId()
            text = "標準（220）"
            isChecked = true
        }
        val wide = RadioButton(this).apply {
            id = View.generateViewId()
            text = "広め（300）"
        }
        spacing.addView(compact)
        spacing.addView(normal)
        spacing.addView(wide)
        section.addView(heading("ノードの間隔"))
        section.addView(spacing)
        section.addView(TextView(this).apply {
            text = "整列は「実行」したときだけ行います。左→右形式は親を左に、子を右に並べます。自由配置にはUndoで戻せます。"
            textSize = 12f
            setPadding(0, dp(12), 0, dp(8))
        })
        val scroller = android.widget.ScrollView(this).apply { addView(section) }
        AlertDialog.Builder(this).setTitle("自動整列の設定")
            .setView(scroller)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("整列を実行") { _, _ ->
                val selectedOnly = scope.checkedRadioButtonId == branch.id
                val gap = when (spacing.checkedRadioButtonId) {
                    compact.id -> 150f
                    wide.id -> 300f
                    else -> 220f
                }
                var changed = 0
                editTransaction {
                    val style = if (layoutStyle.checkedRadioButtonId == rightTreeStyle.id)
                        AutoLayout.Style.LEFT_TO_RIGHT else AutoLayout.Style.RADIAL
                    changed = AutoLayout.apply(map, selected,
                        AutoLayout.Settings(selectedOnly, gap, style))
                }
                if (!selectedOnly && changed > 0) canvas.fitAll()
                toast(if (changed > 0) "${changed}ノードを整列しました" else "移動するノードがありません")
            }.show()
    }

    private fun showAppMenu() {
        val options = arrayOf("新しいマップ", "100ノードのテストマップ",
            "自動整列の設定", "JSONを書き出す", "JSONを読み込む", "全体にフィット", "操作方法")
        AlertDialog.Builder(this).setTitle("マップ操作").setItems(options) { _, which ->
            when (which) {
                0 -> confirmNewMap(MapState.blank())
                1 -> confirmNewMap(MapState.sample100())
                2 -> showAutoLayoutDialog()
                3 -> exportJson()
                4 -> importJson()
                5 -> canvas.fitAll()
                6 -> showHelp()
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
            "・ノードを2回タップ：名前編集\n" +
            "・ノード長押し：編集・色変更・折りたたみ・削除\n" +
            "・全体：マップ全体を表示\n" +
            "・大切なデータはJSONでバックアップしてください"
        ).setPositiveButton("閉じる", null).show()
    }

    private fun exportJson() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "mindmap-backup.json")
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
                    stream.use { it.write(map.asJson().toByteArray(Charsets.UTF_8)) }
                    toast("JSONを書き出しました")
                }
                openJsonRequest -> {
                    val input = contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("ファイルを開けません")
                    confirmNewMap(MapState.parse(input))
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
