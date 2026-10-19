package jp.personal.mindmap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 放射状の自由配置キャンバス。ノード座標はズームに関係なく保存する。 */
class MindMapView(context: Context) : View(context) {
    var map: MapState = MapState.blank()
        set(value) {
            field = value
            selectedId = null
            invalidate()
        }
    var selectedId: Long? = null
        private set
    var groupDrag = false
    var onSelectionChanged: (Long?) -> Unit = {}
    var onEditRequested: (Long) -> Unit = {}
    var onMoveFinished: (String) -> Unit = {}

    private val densityScale = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var zoom = 0.82f
    private var originX = 0f
    private var originY = 0f
    private var initialized = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downNodeId: Long? = null
    private var moved = false
    private var draggedNodes = false
    private var downSnapshot: String? = null
    private var previousTapTime = 0L
    private var previousTapId: Long? = null
    private var pinchDist = 0f
    private var pinchX = 0f
    private var pinchY = 0f
    private var pinchActive = false
    private var longPressed = false
    private var longPressTask: Runnable? = null

    init {
        isClickable = true
        setBackgroundColor(Color.rgb(247, 249, 251))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialized) {
            originX = w / 2f
            originY = h / 2f
            initialized = true
            invalidate()
        }
    }

    fun refresh() = invalidate()

    fun select(id: Long?) {
        if (selectedId != id) {
            selectedId = id
            onSelectionChanged(id)
        }
        invalidate()
    }

    fun fitAll() {
        if (width == 0 || height == 0 || map.nodes.isEmpty()) return
        val minX = map.nodes.minOf { it.x } - 95f
        val maxX = map.nodes.maxOf { it.x } + 95f
        val minY = map.nodes.minOf { it.y } - 60f
        val maxY = map.nodes.maxOf { it.y } + 60f
        zoom = min(
            (width - 24f * densityScale) / (maxX - minX) / densityScale,
            (height - 24f * densityScale) / (maxY - minY) / densityScale
        ).coerceIn(0.12f, 2.0f)
        val scale = zoom * densityScale
        originX = width / 2f - (minX + maxX) / 2f * scale
        originY = height / 2f - (minY + maxY) / 2f * scale
        invalidate()
    }

    fun focusOn(id: Long) {
        val n = map.find(id) ?: return
        zoom = max(zoom, 0.9f).coerceAtMost(2.0f)
        originX = width / 2f - n.x * zoom * densityScale
        originY = height / 2f - n.y * zoom * densityScale
        select(id)
    }

    private fun worldX(screenX: Float) = (screenX - originX) / (zoom * densityScale)
    private fun worldY(screenY: Float) = (screenY - originY) / (zoom * densityScale)

    private fun nodeWidth(n: MindNode): Float {
        paint.textSize = 14f
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        return (paint.measureText(n.title) + 31f).coerceIn(80f, 172f)
    }

    private fun hitNode(sx: Float, sy: Float): MindNode? {
        val x = worldX(sx)
        val y = worldY(sy)
        for (n in map.visibleNodes(zoom).asReversed()) {
            val w = nodeWidth(n)
            if (x in (n.x - w / 2f - 7f)..(n.x + w / 2f + 7f) &&
                y in (n.y - 25f)..(n.y + 25f)) return n
        }
        return null
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        paint.reset()
        paint.isAntiAlias = true
        val visible = map.visibleNodes(zoom)
        val ids = visible.mapTo(mutableSetOf()) { it.id }
        c.save()
        c.translate(originX, originY)
        c.scale(zoom * densityScale, zoom * densityScale)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.1f
        for (node in visible) {
            val p = map.find(node.parentId) ?: continue
            if (p.id !in ids) continue
            val dir = if (node.x < p.x) -1f else 1f
            val ax = p.x + dir * nodeWidth(p) / 2f
            val bx = node.x - dir * nodeWidth(node) / 2f
            val path = Path().apply {
                moveTo(ax, p.y)
                val d = max(18f, abs(bx - ax) * 0.42f)
                cubicTo(ax + dir * d, p.y, bx - dir * d, node.y, bx, node.y)
            }
            paint.color = node.color
            paint.alpha = 190
            c.drawPath(path, paint)
        }

        for (node in visible) {
            val w = nodeWidth(node)
            rect.set(node.x - w / 2f, node.y - 21f, node.x + w / 2f, node.y + 21f)
            paint.style = Paint.Style.FILL
            paint.color = node.color
            paint.alpha = 255
            c.drawRoundRect(rect, 10f, 10f, paint)
            if (selectedId == node.id) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3.2f
                paint.color = Color.rgb(20, 61, 100)
                rect.inset(-3f, -3f)
                c.drawRoundRect(rect, 12f, 12f, paint)
                rect.inset(3f, 3f)
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = 14f
            paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            val label = ellipsize(node.title, w - 20f)
            c.drawText(label, node.x - paint.measureText(label) / 2f, node.y + 5f, paint)

            val hidden = map.hiddenCount(node.id, ids)
            if (hidden > 0) {
                val badgeLabel = if (node.collapsed) "+$hidden" else "$hidden"
                paint.textSize = 11f
                val badgeW = max(26f, paint.measureText(badgeLabel) + 10f)
                paint.color = Color.rgb(33, 48, 68)
                rect.set(node.x + w / 2f - badgeW / 2f, node.y + 16f,
                    node.x + w / 2f + badgeW / 2f, node.y + 34f)
                c.drawRoundRect(rect, 9f, 9f, paint)
                paint.color = Color.WHITE
                c.drawText(badgeLabel, rect.centerX() - paint.measureText(badgeLabel) / 2f,
                    rect.centerY() + 4f, paint)
            }
        }
        c.restore()
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(84, 100, 121)
        paint.textSize = 12f * densityScale
        c.drawText("${(zoom * 100).toInt()}% · ${visible.size}/${map.nodes.size}ノード",
            12f * densityScale, 21f * densityScale, paint)
    }

    private fun ellipsize(source: String, maxWidth: Float): String {
        val clean = source.replace('\n', ' ')
        if (paint.measureText(clean) <= maxWidth) return clean
        val suffix = "…"
        var text = clean
        while (text.isNotEmpty() && paint.measureText(text + suffix) > maxWidth)
            text = text.dropLast(1)
        return text + suffix
    }

    private fun distance(e: MotionEvent): Float =
        hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    private fun midpointX(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2f
    private fun midpointY(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2f

    private fun restorePositions(beforeJson: String) {
        val before = MapState.parse(beforeJson)
        for (n in map.nodes) {
            before.find(n.id)?.let { saved ->
                n.x = saved.x
                n.y = saved.y
            }
        }
        invalidate()
    }

    private fun cancelScheduledLongPress() {
        longPressTask?.let { removeCallbacks(it) }
        longPressTask = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                downNodeId = hitNode(event.x, event.y)?.id
                moved = false
                draggedNodes = false
                pinchActive = false
                longPressed = false
                downSnapshot = null
                cancelScheduledLongPress()
                val id = downNodeId
                if (id != null) {
                    longPressTask = Runnable {
                        if (!moved && !pinchActive) {
                            longPressed = true
                            select(id)
                            onEditRequested(id)
                        }
                    }.also { postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong()) }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelScheduledLongPress()
                if (event.pointerCount >= 2) {
                    if (draggedNodes && downSnapshot != null)
                        restorePositions(downSnapshot!!)
                    draggedNodes = false
                    downSnapshot = null
                    pinchActive = true
                    pinchDist = distance(event).coerceAtLeast(1f)
                    pinchX = midpointX(event)
                    pinchY = midpointY(event)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinchActive && event.pointerCount >= 2) {
                    val midX = midpointX(event)
                    val midY = midpointY(event)
                    val newDistance = distance(event).coerceAtLeast(1f)
                    val newZoom = (zoom * (newDistance / pinchDist)).coerceIn(0.12f, 3.2f)
                    val ratio = newZoom / zoom
                    originX = midX + (originX - pinchX) * ratio
                    originY = midY + (originY - pinchY) * ratio
                    zoom = newZoom
                    pinchDist = newDistance
                    pinchX = midX
                    pinchY = midY
                    invalidate()
                    return true
                }
                if (event.pointerCount != 1 || longPressed) return true
                val x = event.x
                val y = event.y
                if (!moved && hypot(x - downX, y - downY) > touchSlop) {
                    moved = true
                    cancelScheduledLongPress()
                }
                if (moved) {
                    val dx = x - lastX
                    val dy = y - lastY
                    if (downNodeId != null) {
                        if (!draggedNodes) {
                            downSnapshot = map.asJson()
                            draggedNodes = true
                            select(downNodeId)
                        }
                        val target = map.find(downNodeId)
                        if (target != null) {
                            val moving = if (groupDrag) listOf(target) + map.descendants(target.id)
                                else listOf(target)
                            val worldDx = dx / (zoom * densityScale)
                            val worldDy = dy / (zoom * densityScale)
                            moving.forEach { it.x += worldDx; it.y += worldDy }
                        }
                    } else {
                        originX += dx
                        originY += dy
                    }
                    invalidate()
                }
                lastX = x
                lastY = y
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (pinchActive) {
                    pinchActive = false
                    moved = true
                    downNodeId = null
                    val stillDown = if (event.actionIndex == 0) 1 else 0
                    if (stillDown < event.pointerCount) {
                        lastX = event.getX(stillDown)
                        lastY = event.getY(stillDown)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                cancelScheduledLongPress()
                if (draggedNodes && downSnapshot != null) {
                    onMoveFinished(downSnapshot!!)
                } else if (!moved && !pinchActive && !longPressed) {
                    val tappedId = downNodeId
                    if (tappedId != null) {
                        val now = SystemClock.uptimeMillis()
                        if (previousTapId == tappedId && now - previousTapTime < 330L) {
                            focusOn(tappedId)
                            previousTapId = null
                        } else {
                            select(tappedId)
                            previousTapId = tappedId
                            previousTapTime = now
                        }
                    } else {
                        select(null)
                        previousTapId = null
                    }
                }
                draggedNodes = false
                downSnapshot = null
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelScheduledLongPress()
                if (draggedNodes && downSnapshot != null)
                    restorePositions(downSnapshot!!)
                draggedNodes = false
                downSnapshot = null
                pinchActive = false
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
