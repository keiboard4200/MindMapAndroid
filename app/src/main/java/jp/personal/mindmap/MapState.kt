package jp.personal.mindmap

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class MindNode(
    val id: Long,
    var parentId: Long?,
    var title: String,
    var x: Float,
    var y: Float,
    var color: Int,
    var collapsed: Boolean = false,
    var note: String = "",
    var commentVisible: Boolean = true
)

class MapState(var title: String, val nodes: MutableList<MindNode>) {
    companion object {
        val palette = listOf(
            Color.rgb(49, 118, 173), Color.rgb(58, 147, 113),
            Color.rgb(149, 99, 170), Color.rgb(197, 137, 65),
            Color.rgb(176, 89, 109), Color.rgb(69, 144, 158),
            Color.rgb(73, 96, 174), Color.rgb(28, 130, 125),
            Color.rgb(90, 106, 125), Color.rgb(105, 144, 56)
        )

        fun blank() = MapState("新しいマインドマップ", mutableListOf(
            MindNode(1, null, "中心テーマ", 0f, 0f, Color.rgb(47, 103, 164))
        ))

        fun sample100(): MapState {
            val map = MapState("100ノードのテスト", mutableListOf(
                MindNode(1, null, "中心テーマ", 0f, 0f, Color.rgb(47, 103, 164))
            ))
            var nextId = 2L
            val branchNames = listOf("目的", "仕様", "調査", "設計", "製作", "評価")
            repeat(6) { branch ->
                val angle = branch * 2.0 * PI / 6.0
                val color = palette[branch]
                val branchId = nextId++
                map.nodes.add(MindNode(branchId, 1, branchNames[branch],
                    (220 * cos(angle)).toFloat(), (220 * sin(angle)).toFloat(), color))
                repeat(4) { i ->
                    val a = angle + (i - 1.5) * 0.17
                    val childId = nextId++
                    map.nodes.add(MindNode(childId, branchId, "項目 ${i + 1}",
                        (410 * cos(a)).toFloat(), (410 * sin(a)).toFloat(), color))
                    val leafCount = if (branch * 4 + i < 21) 3 else 2
                    repeat(leafCount) { leaf ->
                        val b = a + (leaf - (leafCount - 1) / 2.0) * 0.075
                        map.nodes.add(MindNode(nextId++, childId, "詳細 ${leaf + 1}",
                            (610 * cos(b)).toFloat(), (610 * sin(b)).toFloat(), color))
                    }
                }
            }
            check(map.nodes.size == 100)
            return map
        }

        fun parse(json: String): MapState {
            val doc = JSONObject(json)
            val arr = doc.getJSONArray("nodes")
            require(arr.length() in 1..5000) { "ノード数が不正です" }
            val result = mutableListOf<MindNode>()
            val ids = mutableSetOf<Long>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getLong("id")
                require(id > 0 && ids.add(id)) { "不正なノードIDです" }
                val x = obj.getDouble("x").toFloat()
                val y = obj.getDouble("y").toFloat()
                require(x.isFinite() && y.isFinite() && kotlin.math.abs(x) < 1_000_000 &&
                    kotlin.math.abs(y) < 1_000_000) { "不正な座標です" }
                result.add(MindNode(
                    id, if (obj.isNull("parentId")) null else obj.getLong("parentId"),
                    obj.optString("title", "無題").take(300), x, y,
                    obj.optInt("color", palette[0]), obj.optBoolean("collapsed", false),
                    obj.optString("note", "").take(20000),
                    obj.optBoolean("commentVisible", true)
                ))
            }
            require(result.any { it.parentId == null }) { "ルートが1個以上必要です" }
            val byId = result.associateBy { it.id }
            for (node in result) {
                val visited = mutableSetOf<Long>()
                var p: MindNode? = node
                while (p != null) {
                    require(visited.add(p.id)) { "親子関係が循環しています" }
                    p = p.parentId?.let { byId[it] ?: error("親ノードがありません") }
                }
            }
            return MapState(doc.optString("title", "マインドマップ").take(200), result)
        }
    }

    fun root(): MindNode = nodes.first { it.parentId == null }
    fun roots(): List<MindNode> = nodes.filter { it.parentId == null }
    fun find(id: Long?): MindNode? = nodes.firstOrNull { it.id == id }
    fun children(id: Long): List<MindNode> = nodes.filter { it.parentId == id }

    fun descendants(id: Long): List<MindNode> {
        val results = mutableListOf<MindNode>()
        val queue = ArrayDeque<Long>()
        queue.add(id)
        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            for (n in nodes) if (n.parentId == curr) {
                results.add(n)
                queue.add(n.id)
            }
        }
        return results
    }

    fun depth(node: MindNode): Int {
        var d = 0
        var p = node.parentId
        while (p != null && d < 100) {
            d++
            p = find(p)?.parentId
        }
        return d
    }

    fun visibleNodes(zoom: Float): List<MindNode> {
        val maxDepth = when {
            zoom < 0.35f -> 1
            zoom < 0.60f -> 2
            else -> Int.MAX_VALUE
        }
        return nodes.filter { n ->
            if (depth(n) > maxDepth) return@filter false
            var p = n.parentId
            while (p != null) {
                val parent = find(p) ?: break
                if (parent.collapsed) return@filter false
                p = parent.parentId
            }
            true
        }
    }

    fun hiddenCount(id: Long, visibleIds: Set<Long>) =
        descendants(id).count { it.id !in visibleIds }

    /**
     * Insert into the current layout style without moving existing nodes.
     * This is intentionally different from re-running automatic alignment.
     */
    fun addChild(parentId: Long, title: String, settings: AutoLayout.Settings): MindNode {
        val p = find(parentId) ?: root()
        val siblings = children(p.id)
        val gap = settings.spacing.coerceIn(150f, 300f)
        val x: Float
        val y: Float
        if (settings.style == AutoLayout.Style.LEFT_TO_RIGHT) {
            val provisional = MindNode(-1, p.id, title, 0f, 0f, p.color)
            val newBox = NodeGeometry.measure(provisional)
            val parentBox = NodeGeometry.measure(p)
            val horizontalGap = kotlin.math.max(32f, gap * 0.28f)
            val verticalGap = kotlin.math.max(22f, gap * 0.20f)
            val sorted = siblings.sortedBy { it.y }
            val sibling = sorted.firstOrNull()

            // Maintain the selected column alignment whenever siblings exist.
            x = if (sibling == null) {
                p.x + parentBox.visualWidth / 2f + horizontalGap +
                    newBox.visualWidth / 2f
            } else {
                val oldBox = NodeGeometry.measure(sibling)
                val proposed = when (settings.horizontal) {
                    AutoLayout.Horizontal.LEFT ->
                        sibling.x - oldBox.width / 2f + newBox.width / 2f
                    AutoLayout.Horizontal.CENTER -> sibling.x
                    AutoLayout.Horizontal.RIGHT ->
                        sibling.x + oldBox.width / 2f - newBox.width / 2f
                }
                kotlin.math.max(proposed,
                    p.x + parentBox.visualWidth / 2f + horizontalGap +
                        newBox.visualWidth / 2f)
            }
            y = when (settings.alignment) {
                AutoLayout.Align.TOP, AutoLayout.Align.CENTER ->
                    if (sorted.isEmpty()) p.y else {
                        val bottom = sorted.maxOf {
                            it.y + NodeGeometry.measure(it).bottom
                        }
                        bottom + verticalGap + newBox.top
                    }
                AutoLayout.Align.BOTTOM ->
                    if (sorted.isEmpty()) p.y else {
                        val top = sorted.minOf {
                            it.y - NodeGeometry.measure(it).top
                        }
                        top - verticalGap - newBox.bottom
                    }
            }
        } else {
            val parent = find(p.parentId)
            val direction = if (parent == null) 0.0
                else kotlin.math.atan2((p.y - parent.y).toDouble(), (p.x - parent.x).toDouble())
            val idx = siblings.size
            val theta = if (parent == null) {
                idx * (2.0 * PI / 6.0)
            } else {
                val offset = when {
                    idx == 0 -> 0.0
                    idx % 2 == 1 -> (idx + 1) / 2.0 * 0.4
                    else -> -idx / 2.0 * 0.4
                }
                direction + offset
            }
            x = p.x + (gap * cos(theta)).toFloat()
            y = p.y + (gap * sin(theta)).toFloat()
        }
        val node = MindNode((nodes.maxOfOrNull { it.id } ?: 0L) + 1L,
            p.id, title, x, y, p.color)
        nodes.add(node)
        p.collapsed = false
        return node
    }

    /**
     * Remove one node, preserving its children. If it was a root, its children
     * become separate roots. At least one root must remain at all times.
     */
    fun deleteNodeOnly(id: Long) {
        val target = find(id) ?: return
        val newParent = target.parentId
        if (nodes.size == 1) return
        for (child in children(id)) child.parentId = newParent
        if (target.parentId == null && roots().size == 1 && children(id).isEmpty()) return
        nodes.removeAll { it.id == id }
    }

    fun deleteBranch(id: Long) {
        val target = find(id) ?: return
        if (target.parentId == null && roots().size <= 1) return
        val ids = descendants(id).map { it.id }.toMutableSet().apply { add(id) }
        nodes.removeAll { it.id in ids }
    }

    /** Pick an open spot to the right of all existing, visible content. */
    fun freeRootPosition(): Pair<Float, Float> {
        val shown = visibleNodes(3.2f)
        val right = shown.maxOfOrNull {
            val b = NodeGeometry.measure(it)
            it.x + b.visualWidth / 2f
        } ?: 0f
        return (right + 280f) to (roots().firstOrNull()?.y ?: 0f)
    }

    fun addRoot(title: String): MindNode {
        require(nodes.size < 5000) { "ノード数の上限に達しました" }
        val (x, y) = freeRootPosition()
        val node = MindNode((nodes.maxOfOrNull { it.id } ?: 0L) + 1L,
            null, title.take(300), x, y, palette[roots().size % palette.size])
        nodes.add(node)
        return node
    }

    /**
     * Portable snapshot. The copied head is a temporary independent root, so
     * it can be pasted either as a new root or under a selected target node.
     */
    fun copyJson(id: Long, withDescendants: Boolean = true): String {
        val head = find(id) ?: error("コピーするノードがありません")
        val source = if (withDescendants) listOf(head) + descendants(id) else listOf(head)
        val copy = source.map { it.copy(parentId = if (it.id == id) null else it.parentId) }
        return MapState(head.title, copy.toMutableList()).asJson()
    }

    /** Clone all nodes with fresh IDs, preserving notes, colours and geometry. */
    fun pasteJson(snapshot: String, newParentId: Long? = null): MindNode {
        val fragment = parse(snapshot)
        require(nodes.size + fragment.nodes.size <= 5000) { "ノード数の上限に達しました" }
        val sourceRoot = fragment.root()
        val parent = if (newParentId == null) null
            else find(newParentId) ?: error("貼り付け先のノードがありません")
        val (anchorX, anchorY) = if (parent == null) freeRootPosition()
            else Pair(parent.x + NodeGeometry.measure(parent).visualWidth / 2f +
                NodeGeometry.measure(sourceRoot).visualWidth / 2f + 100f,
                parent.y + (children(parent.id).size * 95f))
        val idMap = mutableMapOf<Long, Long>()
        var next = (nodes.maxOfOrNull { it.id } ?: 0L) + 1L
        for (n in fragment.nodes) idMap[n.id] = next++
        val pasted = fragment.nodes.map { n ->
            n.copy(
                id = idMap.getValue(n.id),
                parentId = if (n.id == sourceRoot.id) parent?.id else
                    idMap[n.parentId] ?: error("コピー内の親IDが不正です"),
                x = anchorX + n.x - sourceRoot.x,
                y = anchorY + n.y - sourceRoot.y
            )
        }
        nodes.addAll(pasted)
        if (parent != null) parent.collapsed = false
        return pasted.first { it.id == idMap[sourceRoot.id] }
    }

    fun asJson(): String {
        val a = JSONArray()
        for (n in nodes) {
            a.put(JSONObject().apply {
                put("id", n.id)
                put("parentId", n.parentId ?: JSONObject.NULL)
                put("title", n.title)
                put("x", n.x.toDouble())
                put("y", n.y.toDouble())
                put("color", n.color)
                put("collapsed", n.collapsed)
                put("note", n.note)
                put("commentVisible", n.commentVisible)
            })
        }
        return JSONObject().apply {
            put("format", "mindmap-android-v1")
            put("title", title)
            put("nodes", a)
        }.toString(2)
    }
}
