package jp.personal.mindmap

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Explicit only: alignment never runs implicitly on edit or open.
 * Measures the same visual bounds (title + shown comment) as MindMapView.
 */
object AutoLayout {
    enum class Style { RADIAL, LEFT_TO_RIGHT }
    enum class Align { TOP, CENTER, BOTTOM }
    enum class Horizontal { LEFT, CENTER, RIGHT }

    data class Settings(
        val selectedBranchOnly: Boolean,
        val spacing: Float,
        val style: Style = Style.LEFT_TO_RIGHT,
        val alignment: Align = Align.CENTER,
        val horizontal: Horizontal = Horizontal.CENTER
    )

    fun apply(map: MapState, selectedId: Long?, settings: Settings): Int {
        val root = if (settings.selectedBranchOnly) map.find(selectedId) ?: return 0
                   else map.root()
        val before = map.nodes.associate { it.id to (it.x to it.y) }
        val changed = if (settings.style == Style.LEFT_TO_RIGHT) {
            leftToRight(map, root, settings)
        } else {
            radial(map, root, settings)
        }

        // Folded descendants do not consume space, but preserve their relative
        // coordinates when the visible, folded parent changes position.
        for (folded in map.nodes.filter { it.collapsed && changed.contains(it.id) }) {
            val old = before[folded.id] ?: continue
            val dx = folded.x - old.first
            val dy = folded.y - old.second
            for (hidden in map.descendants(folded.id)) {
                hidden.x += dx
                hidden.y += dy
            }
        }
        return map.nodes.count { n ->
            val p = before[n.id]
            p != null && (p.first != n.x || p.second != n.y)
        }
    }

    private fun leftToRight(
        map: MapState, root: MindNode, settings: Settings
    ): Set<Long> {
        val byParent = map.nodes.groupBy { it.parentId }
        fun children(node: MindNode): List<MindNode> =
            if (node.collapsed) emptyList()
            else byParent[node.id].orEmpty().sortedWith(compareBy<MindNode> { it.y }.thenBy { it.id })

        if (children(root).isEmpty()) return emptySet()

        val verticalGap = max(22f, settings.spacing * 0.20f)
        val horizontalGap = max(32f, settings.spacing * 0.28f)
        val box = mutableMapOf<Long, NodeGeometry.Box>()
        fun geom(node: MindNode) =
            box.getOrPut(node.id) { NodeGeometry.measure(node) }

        data class Tree(
            val relativeY: Map<Long, Float>,
            val top: Float,
            val bottom: Float
        )

        fun tidy(node: MindNode): Tree {
            val g = geom(node)
            val kids = children(node)
            if (kids.isEmpty()) return Tree(mapOf(node.id to 0f), -g.top, g.bottom)

            val placements = mutableMapOf<Long, Float>(node.id to 0f)
            var cursor = 0f
            var firstCenter = 0f
            var lastCenter = 0f
            var firstTop = 0f
            var lastBottom = 0f
            kids.forEachIndexed { index, child ->
                val tree = tidy(child)
                val shift = cursor - tree.top
                if (index == 0) {
                    firstCenter = shift
                    firstTop = tree.top + shift
                }
                lastCenter = shift
                lastBottom = tree.bottom + shift
                for ((id, y) in tree.relativeY) placements[id] = y + shift
                cursor = tree.bottom + shift + verticalGap
            }
            val parentCenter = when (settings.alignment) {
                Align.TOP -> firstCenter
                Align.CENTER -> (firstCenter + lastCenter) / 2f
                Align.BOTTOM -> lastCenter
            }
            for ((id, y) in placements.toMap()) if (id != node.id) {
                placements[id] = y - parentCenter
            }
            return Tree(
                placements,
                min(-g.top, firstTop - parentCenter),
                max(g.bottom, lastBottom - parentCenter)
            )
        }

        val layout = tidy(root)
        val depths = mutableMapOf<Long, Int>()
        fun visit(n: MindNode, depth: Int) {
            depths[n.id] = depth
            for (child in children(n)) visit(child, depth + 1)
        }
        visit(root, 0)
        val groups = depths.entries.groupBy({ it.value }, { it.key })
        val nodesById = map.nodes.associateBy { it.id }
        val columns = mutableMapOf<Int, Float>()
        var rightBoundary = root.x + geom(root).visualWidth / 2f

        for (depth in 1..(groups.keys.maxOrNull() ?: 0)) {
            val list = groups[depth].orEmpty().mapNotNull { nodesById[it] }
            if (list.isEmpty()) continue
            val anchor = when (settings.horizontal) {
                Horizontal.CENTER ->
                    rightBoundary + horizontalGap + list.maxOf { geom(it).visualWidth / 2f }
                Horizontal.LEFT ->
                    rightBoundary + horizontalGap + list.maxOf {
                        (geom(it).visualWidth - geom(it).width) / 2f
                    }
                Horizontal.RIGHT ->
                    rightBoundary + horizontalGap + list.maxOf {
                        (geom(it).visualWidth + geom(it).width) / 2f
                    }
            }
            columns[depth] = anchor
            rightBoundary = list.maxOf { node ->
                val g = geom(node)
                val center = when (settings.horizontal) {
                    Horizontal.LEFT -> anchor + g.width / 2f
                    Horizontal.CENTER -> anchor
                    Horizontal.RIGHT -> anchor - g.width / 2f
                }
                center + g.visualWidth / 2f
            }
        }

        for ((id, y) in layout.relativeY) {
            if (id == root.id) continue
            val node = nodesById[id] ?: continue
            val anchor = columns[depths[id]] ?: continue
            val g = geom(node)
            node.x = when (settings.horizontal) {
                Horizontal.LEFT -> anchor + g.width / 2f
                Horizontal.CENTER -> anchor
                Horizontal.RIGHT -> anchor - g.width / 2f
            }
            node.y = root.y + y
        }
        return layout.relativeY.keys
    }

    private fun radial(
        map: MapState, root: MindNode, settings: Settings
    ): Set<Long> {
        val byParent = map.nodes.groupBy { it.parentId }
        fun children(n: MindNode): List<MindNode> =
            if (n.collapsed) emptyList()
            else byParent[n.id].orEmpty().sortedWith(compareBy<MindNode> {
                val angle = atan2((it.y - n.y).toDouble(), (it.x - n.x).toDouble())
                if (angle < 0) angle + 2 * PI else angle
            }.thenBy { it.id })

        val weights = mutableMapOf<Long, Int>()
        fun weight(node: MindNode): Int {
            val kids = children(node)
            val n = if (kids.isEmpty()) 1 else kids.sumOf { weight(it) }
            weights[node.id] = n
            return n
        }
        weight(root)
        if (children(root).isEmpty()) return emptySet()

        val depth = mutableMapOf<Long, Int>()
        val angles = mutableMapOf<Long, Double>()
        val parent = map.find(root.parentId)
        val direction = if (parent == null) 0.0 else
            atan2((root.y - parent.y).toDouble(), (root.x - parent.x).toDouble())
        val span = if (parent == null) 2.0 * PI else 1.84
        fun assign(node: MindNode, d: Int, left: Double, right: Double) {
            val kids = children(node)
            val sum = kids.sumOf { weights[it.id] ?: 1 }.coerceAtLeast(1)
            var a = left
            for (child in kids) {
                val next = a + (right - left) * (weights[child.id] ?: 1) / sum
                angles[child.id] = (a + next) / 2.0
                depth[child.id] = d + 1
                assign(child, d + 1, a, next)
                a = next
            }
        }
        assign(root, 0, direction - span / 2.0, direction + span / 2.0)

        val nodesById = map.nodes.associateBy { it.id }
        val groups = depth.entries.groupBy({ it.value }, { it.key })
        val gap = max(24f, settings.spacing * 0.25f)
        var radius = 0f
        var prevExtent = NodeGeometry.measure(root).collisionRadius

        for (d in 1..(groups.keys.maxOrNull() ?: 0)) {
            val ids = groups[d].orEmpty().sortedBy { angles[it] ?: 0.0 }
            if (ids.isEmpty()) continue
            val extents = ids.associateWith {
                NodeGeometry.measure(nodesById.getValue(it)).collisionRadius
            }
            val extent = extents.values.maxOrNull() ?: 0f
            var required = radius + prevExtent + extent + gap
            // Adjacent items on a ring must be farther apart than their
            // complete visible bounds, including all displayed comment lines.
            for (i in ids.indices) {
                val j = if (i == ids.lastIndex && span > 6.0) 0 else i + 1
                if (j >= ids.size || i == j) continue
                var delta = abs((angles[ids[i]] ?: 0.0) - (angles[ids[j]] ?: 0.0))
                if (span > 6.0) delta = min(delta, 2.0 * PI - delta)
                val chord = (2.0 * sin(delta / 2.0)).coerceAtLeast(0.001)
                required = max(required,
                    ((extents.getValue(ids[i]) + extents.getValue(ids[j]) + gap) / chord).toFloat()
                )
            }
            radius = required.coerceAtMost(100_000f)
            prevExtent = extent
            for (id in ids) {
                val n = nodesById[id] ?: continue
                val a = angles[id] ?: 0.0
                n.x = root.x + radius * cos(a).toFloat()
                n.y = root.y + radius * sin(a).toFloat()
            }
        }
        return depth.keys
    }
}
