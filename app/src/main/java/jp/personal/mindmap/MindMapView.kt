package jp.personal.mindmap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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
            metricsCache.clear()
            invalidate()
        }
    var selectedId: Long? = null
        private set
    var groupDrag = false
    var onSelectionChanged: (Long?) -> Unit = {}
    var onEditRequested: (Long) -> Unit = {}
    var onDoubleTap: (Long) -> Unit = {}
    var onFoldClicked: (Long) -> Unit = {}
    var onCommentClicked: (Long) -> Unit = {}
    var onMoveFinished: (String) -> Unit = {}

    private val densityScale = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val titleTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    }
    private val commentTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(209, 222, 236)
        textSize = 12f
    }
    private data class NodeMetrics(val width: Float, val height: Float, val title: StaticLayout)
    private val metricsCache = mutableMapOf<Long, Pair<String, NodeMetrics>>()
    private enum class HitControl { COMMENT, FOLD }
    private var downControl: HitControl? = null
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
        setBackgroundColor(Color.rgb(16, 22, 32))
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
        // Hidden descendants should not force an unnecessarily tiny fit.
        val shown = map.visibleNodes(3.2f)
        val minX = shown.minOf { it.x } - 95f
        val maxX = shown.maxOf { it.x } + 95f
        val minY = shown.minOf { it.y } - 60f
        val maxY = shown.maxOf { it.y } + 60f
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

    private fun buildLayout(text: String, p: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width.coerceAtLeast(24))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()

    private fun metrics(node: MindNode): NodeMetrics {
        val cached = metricsCache[node.id]
        if (cached != null && cached.first == node.title) return cached.second
        val longest = node.title.split('\n').maxOfOrNull { titleTextPaint.measureText(it) } ?: 0f
        val width = (longest + 70f).coerceIn(116f, 318f)
        val title = buildLayout(node.title, titleTextPaint, (width - 68f).toInt())
        val result = NodeMetrics(width, max(44f, title.height + 20f), title)
        metricsCache[node.id] = node.title to result
        return result
    }

    private fun commentLayout(node: MindNode): StaticLayout {
        val lines = node.note.split('\n')
        val longest = lines.maxOfOrNull { commentTextPaint.measureText(it) } ?: 0f
        val width = (longest + 8f).coerceIn(110f, 310f).toInt()
        return buildLayout(node.note, commentTextPaint, width)
    }

    private fun inBody(n: MindNode, wx: Float, wy: Float): Boolean {
        val m = metrics(n)
        return wx >= n.x - m.width / 2f - 5f &&
            wx <= n.x + m.width / 2f + 5f &&
            wy >= n.y - m.height / 2f - 5f &&
            wy <= n.y + m.height / 2f + 5f
    }

    private fun hitNode(sx: Float, sy: Float): MindNode? {
        val x = worldX(sx)
        val y = worldY(sy)
        return map.visibleNodes(zoom).asReversed().firstOrNull { inBody(it, x, y) }
    }

    private fun hitControl(n: MindNode, screenX: Float, screenY: Float): HitControl? {
        if (zoom < 0.45f) return null
        val m = metrics(n)
        val x = worldX(screenX)
        val y = worldY(screenY)
        if (abs(y - n.y) > max(22f, m.height / 2f)) return null
        return when {
            abs(x - (n.x - m.width / 2f + 14f)) < 15f -> HitControl.COMMENT
            map.children(n.id).isNotEmpty() &&
                abs(x - (n.x + m.width / 2f - 14f)) < 15f -> HitControl.FOLD
            else -> null
        }
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
        paint.strokeWidth = 2.0f
        paint.color = Color.rgb(106, 125, 149)
        for (node in visible) {
            val parentNode = map.find(node.parentId) ?: continue
            if (parentNode.id !in ids) continue
            val from = metrics(parentNode)
            val to = metrics(node)
            val dir = if (node.x < parentNode.x) -1f else 1f
            val ax = parentNode.x + dir * from.width / 2f
            val bx = node.x - dir * to.width / 2f
            val bend = max(18f, abs(bx - ax) * 0.42f)
            val path = Path().apply {
                moveTo(ax, parentNode.y)
                cubicTo(ax + dir * bend, parentNode.y, bx - dir * bend, node.y, bx, node.y)
            }
            c.drawPath(path, paint)
        }

        for (n in visible) {
            val m = metrics(n)
            val left = n.x - m.width / 2f
            val top = n.y - m.height / 2f
            rect.set(left, top, left + m.width, top + m.height)
            paint.style = Paint.Style.FILL
            paint.color = n.color
            c.drawRoundRect(rect, 10f, 10f, paint)
            if (selectedId == n.id) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.8f
                paint.color = Color.rgb(231, 242, 253)
                rect.inset(-2f, -2f)
                c.drawRoundRect(rect, 11f, 11f, paint)
                rect.inset(2f, 2f)
            }

            c.save()
            c.translate(n.x - m.title.width / 2f, n.y - m.title.height / 2f)
            m.title.draw(c)
            c.restore()

            // Left button: comment visibility. Right button: children visibility.
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(25, 36, 52)
            val lx = left + 14f
            val rx = left + m.width - 14f
            c.drawCircle(lx, n.y, 10f, paint)
            paint.color = if (n.note.isNotEmpty()) Color.rgb(248, 223, 150)
                else Color.rgb(154, 174, 195)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 12f
            c.drawText(if (n.commentVisible) "≡" else "×", lx, n.y + 4f, paint)
            if (map.children(n.id).isNotEmpty()) {
                paint.color = Color.rgb(25, 36, 52)
                c.drawCircle(rx, n.y, 10f, paint)
                paint.color = Color.WHITE
                paint.textSize = 16f
                c.drawText(if (n.collapsed) "+" else "−", rx, n.y + 5.5f, paint)
            }
            paint.textAlign = Paint.Align.LEFT

            if (n.note.isNotBlank() && n.commentVisible) {
                val comment = commentLayout(n)
                c.save()
                c.translate(n.x - comment.width / 2f, top + m.height + 7f)
                comment.draw(c)
                c.restore()
            }
            if (n.collapsed) {
                val hidden = map.descendants(n.id).size
                if (hidden > 0) {
                    paint.color = Color.rgb(178, 202, 226)
                    paint.textAlign = Paint.Align.CENTER
                    paint.textSize = 11f
                    c.drawText("+" + hidden, rx, top - 5f, paint)
                    paint.textAlign = Paint.Align.LEFT
                }
            }
        }
        c.restore()
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(194, 207, 227)
        paint.textSize = 12f * densityScale
        c.drawText((zoom * 100).toInt().toString() + "% · " +
            visible.size + "/" + map.nodes.size + "ノード",
            12f * densityScale, 21f * densityScale, paint)
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
                downControl = map.find(downNodeId)?.let { hitControl(it, event.x, event.y) }
                moved = false
                draggedNodes = false
                pinchActive = false
                longPressed = false
                downSnapshot = null
                cancelScheduledLongPress()
                val id = downNodeId
                if (id != null && downControl == null) {
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
                    if (downControl != null) {
                        lastX = x
                        lastY = y
                        return true
                    }
                    if (downNodeId != null) {
                        if (!draggedNodes) {
                            downSnapshot = map.asJson()
                            draggedNodes = true
                            select(downNodeId)
                        }
                        val target = map.find(downNodeId)
                        if (target != null) {
                            val moving = if (groupDrag || target.collapsed)
                                listOf(target) + map.descendants(target.id)
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
                    if (tappedId != null && downControl != null) {
                        select(tappedId)
                        when (downControl) {
                            HitControl.COMMENT -> onCommentClicked(tappedId)
                            HitControl.FOLD -> onFoldClicked(tappedId)
                            null -> {}
                        }
                        previousTapId = null
                    } else if (tappedId != null) {
                        val now = SystemClock.uptimeMillis()
                        if (previousTapId == tappedId && now - previousTapTime < 330L) {
                            select(tappedId)
                            onDoubleTap(tappedId)
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
                downControl = null
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
                downControl = null
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
