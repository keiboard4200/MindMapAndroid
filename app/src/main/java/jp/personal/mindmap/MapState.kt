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
    var collapsed: Boolean = false
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
                    obj.optInt("color", palette[0]), obj.optBoolean("collapsed", false)
                ))
            }
            require(result.count { it.parentId == null } == 1) { "ルートは1個必要です" }
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

    fun addChild(parentId: Long): MindNode {
        val p = find(parentId) ?: root()
        val siblings = children(p.id).size
        val angle = if (p.parentId == null) siblings * 2.0 * PI / 6.0
                    else kotlin.math.atan2(p.y.toDouble(), p.x.toDouble()) + (siblings - 1.5) * 0.3
        val radius = if (p.parentId == null) 210.0 else 150.0
        val node = MindNode((nodes.maxOfOrNull { it.id } ?: 0L) + 1L, p.id,
            "新しいノード",
            p.x + (radius * cos(angle)).toFloat(),
            p.y + (radius * sin(angle)).toFloat(), p.color)
        nodes.add(node)
        p.collapsed = false
        return node
    }

    fun deleteBranch(id: Long) {
        if (id == root().id) return
        val ids = descendants(id).map { it.id }.toMutableSet().apply { add(id) }
        nodes.removeAll { it.id in ids }
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
            })
        }
        return JSONObject().apply {
            put("format", "mindmap-android-v1")
            put("title", title)
            put("nodes", a)
        }.toString(2)
    }
}
