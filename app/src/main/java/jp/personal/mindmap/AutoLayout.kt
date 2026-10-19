package jp.personal.mindmap

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Explicit, undoable radial layout.
 * Only changes coordinates when the user presses "整列を実行".
 * For a selected subtree, its root and all nodes outside that subtree stay fixed.
 */
object AutoLayout {
    enum class Style { RADIAL, LEFT_TO_RIGHT }
    data class Settings(
        val selectedBranchOnly: Boolean,
        val spacing: Float,
        val style: Style = Style.RADIAL
    )

    fun apply(map: MapState, selectedId: Long?, settings: Settings): Int {
        val center = if (settings.selectedBranchOnly) map.find(selectedId) ?: return 0
                     else map.root()
        if (settings.style == Style.LEFT_TO_RIGHT) {
            return applyLeftToRight(map, center, settings.spacing)
        }
        val childrenByParent = map.nodes.groupBy { it.parentId }
        fun children(id: Long): List<MindNode> = childrenByParent[id].orEmpty()

        // The ordering is deterministic and based on the existing visual order, not IDs.
        val ordered = HashMap<Long, List<MindNode>>()
        fun orderedChildren(parent: MindNode): List<MindNode> =
            ordered.getOrPut(parent.id) {
                children(parent.id).sortedWith(compareBy<MindNode> {
                    val a = atan2((it.y - parent.y).toDouble(), (it.x - parent.x).toDouble())
                    if (a < 0.0) a + 2.0 * PI else a
                }.thenBy { it.id })
            }

        val leafWeight = HashMap<Long, Int>()
        val depthCounts = HashMap<Int, Int>()
        fun measure(n: MindNode, depth: Int): Int {
            if (depth > 100) return 1
            if (depth != 0) depthCounts[depth] = (depthCounts[depth] ?: 0) + 1
            val kids = orderedChildren(n)
            val weight = if (kids.isEmpty()) 1
                else kids.sumOf { measure(it, depth + 1) }
            leafWeight[n.id] = weight
            return weight
        }
        measure(center, 0)
        if (depthCounts.isEmpty()) return 0

        val parent = map.find(center.parentId)
        val halfSpan = if (parent != null && settings.selectedBranchOnly) 0.92 else PI
        val direction = if (parent != null && settings.selectedBranchOnly)
            atan2((center.y - parent.y).toDouble(), (center.x - parent.x).toDouble())
            else 0.0
        val minAngle = direction - halfSpan
        val maxAngle = direction + halfSpan
        val span = maxAngle - minAngle
        val baseX = center.x
        val baseY = center.y
        val gap = settings.spacing.coerceIn(140f, 330f)
        val minArc = gap * 0.48f

        var moved = 0
        fun place(parentNode: MindNode, depth: Int, left: Double, right: Double) {
            val kids = orderedChildren(parentNode)
            if (kids.isEmpty()) return
            val total = kids.sumOf { leafWeight[it.id] ?: 1 }
            var from = left
            for (child in kids) {
                val subSpan = (right - left) * (leafWeight[child.id] ?: 1) / total
                val to = from + subSpan
                val theta = (from + to) / 2.0
                val count = depthCounts[depth] ?: 1
                val radius = max(gap * depth, (count * minArc / span).toFloat())
                val x = baseX + radius * cos(theta).toFloat()
                val y = baseY + radius * sin(theta).toFloat()
                if (child.x != x || child.y != y) moved++
                child.x = x
                child.y = y
                place(child, depth + 1, from, to)
                from = to
            }
        }
        place(center, 1, minAngle, maxAngle)
        return moved
    }
    /**
     * Tidy left-to-right tree with the root at the left and descendants to the right.
     * Each leaf owns one vertical slot. Internal nodes sit midway between
     * their outermost descendant leaves. No auto layout happens on edits or launch.
     */
    private fun applyLeftToRight(map: MapState, root: MindNode, spacing: Float): Int {
        val byParent = map.nodes.groupBy { it.parentId }
        val children = HashMap<Long, List<MindNode>>()
        fun sortedChildren(node: MindNode): List<MindNode> =
            children.getOrPut(node.id) {
                byParent[node.id].orEmpty().sortedWith(
                    compareBy<MindNode> { it.y }.thenBy { it.id }
                )
            }
        if (sortedChildren(root).isEmpty()) return 0

        val horizontalGap = max(205f, spacing.coerceIn(140f, 330f))
        val verticalGap = max(74f, spacing * 0.4f)
        val slot = HashMap<Long, Float>()
        val depth = HashMap<Long, Int>()
        var nextLeaf = 0f

        fun allocate(n: MindNode, d: Int): Float {
            depth[n.id] = d
            val kids = sortedChildren(n)
            val y = if (kids.isEmpty()) {
                val value = nextLeaf
                nextLeaf += 1f
                value
            } else {
                val first = allocate(kids.first(), d + 1)
                var last = first
                for (child in kids.drop(1)) last = allocate(child, d + 1)
                (first + last) / 2f
            }
            slot[n.id] = y
            return y
        }
        allocate(root, 0)
        val baseSlot = slot[root.id] ?: 0f
        var changed = 0
        for (node in map.nodes) {
            val d = depth[node.id] ?: continue
            if (d == 0) continue  // Selected branch root and other branches remain unchanged.
            val x = root.x + d * horizontalGap
            val y = root.y + ((slot[node.id] ?: baseSlot) - baseSlot) * verticalGap
            if (node.x != x || node.y != y) changed++
            node.x = x
            node.y = y
        }
        return changed
    }

}
