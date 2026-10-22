package com.drawify.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the tracing image on top of the camera preview.
 *
 * The image is placed by two matrices: [transform] positions it in "paper space" (drag with one
 * finger, zoom/rotate with two) and [perspective] maps paper space onto the screen, so the image
 * can look like it lies on paper the phone is tilted towards. In corner mode the four corners are
 * dragged directly instead.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val transform = Matrix()
    private val perspective = Matrix()
    private val perspectiveInverse = Matrix()
    private val full = Matrix()
    private var needsFit = false

    private val density = resources.displayMetrics.density
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xAAFFFFFF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3D6E.toInt() }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = 0xFFFFFFFF.toInt()
    }
    private val borderPath = Path()
    private val corners = FloatArray(8)

    var locked = false
        set(value) {
            field = value
            invalidate()
        }

    var opacity = 0.5f
        set(value) {
            field = value
            imagePaint.alpha = (value * 255).roundToInt()
            invalidate()
        }

    /** When on, the four image corners can be dragged individually to match the paper. */
    var cornerMode = false
        set(value) {
            field = value && bitmap != null
            if (field) captureCorners()
            invalidate()
        }

    // Gesture state
    private val pts = FloatArray(4)
    private var lastX = 0f
    private var lastY = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var lastDist = 0f
    private var lastAngle = 0f
    private var activeHandle = NO_HANDLE
    private val handles = FloatArray(8)
    private val handlesBackup = FloatArray(8)

    init {
        imagePaint.alpha = (opacity * 255).roundToInt()
    }

    val hasImage: Boolean get() = bitmap != null

    /** Replaces the image. With [fit] the image is re-centered, otherwise its placement is kept. */
    fun setBitmap(bmp: Bitmap, fit: Boolean) {
        bitmap = bmp
        if (fit) {
            if (width > 0) fitToView() else needsFit = true
        }
        invalidate()
    }

    fun setPerspective(m: Matrix) {
        perspective.set(m)
        if (!perspective.invert(perspectiveInverse)) {
            perspective.reset()
            perspectiveInverse.reset()
        }
        invalidate()
    }

    fun clearPerspective() = setPerspective(Matrix())

    fun resetPosition() {
        fitToView()
        if (cornerMode) captureCorners()
        invalidate()
    }

    fun flipHorizontal() {
        val b = bitmap ?: return
        transform.preScale(-1f, 1f, b.width / 2f, b.height / 2f)
        if (cornerMode) captureCorners()
        invalidate()
    }

    private fun fitToView() {
        val b = bitmap ?: return
        val scale = min(width * 0.85f / b.width, height * 0.6f / b.height)
        transform.reset()
        transform.postScale(scale, scale)
        transform.postTranslate((width - b.width * scale) / 2f, (height - b.height * scale) / 2f)
        needsFit = false
    }

    private fun updateFull() {
        full.set(transform)
        full.postConcat(perspective)
    }

    private fun mapCorners(b: Bitmap, out: FloatArray) {
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        out[0] = 0f; out[1] = 0f
        out[2] = w; out[3] = 0f
        out[4] = w; out[5] = h
        out[6] = 0f; out[7] = h
        updateFull()
        full.mapPoints(out)
    }

    private fun captureCorners() {
        val b = bitmap ?: return
        mapCorners(b, handles)
    }

    /** Rebuilds the placement so the bitmap corners land exactly on [handles]. */
    private fun applyHandles(): Boolean {
        val b = bitmap ?: return false
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        val src = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        val m = Matrix()
        if (!m.setPolyToPoly(src, 0, handles, 0, 4)) return false
        val inv = Matrix()
        if (!m.invert(inv)) return false
        transform.reset()
        perspective.set(m)
        perspectiveInverse.set(inv)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (needsFit) fitToView()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        updateFull()
        canvas.drawBitmap(b, full, imagePaint)
        if (locked) return
        mapCorners(b, corners)
        borderPath.reset()
        borderPath.moveTo(corners[0], corners[1])
        borderPath.lineTo(corners[2], corners[3])
        borderPath.lineTo(corners[4], corners[5])
        borderPath.lineTo(corners[6], corners[7])
        borderPath.close()
        canvas.drawPath(borderPath, borderPaint)
        if (cornerMode) {
            val r = HANDLE_RADIUS_DP * density
            for (i in 0 until 4) {
                canvas.drawCircle(corners[i * 2], corners[i * 2 + 1], r, handleFill)
                canvas.drawCircle(corners[i * 2], corners[i * 2 + 1], r, handleStroke)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (locked || bitmap == null) return false
        return if (cornerMode) handleCornerTouch(event) else handleTransformTouch(event)
    }

    /** Drag a corner handle, or drag anywhere else to move all four corners together. */
    private fun handleCornerTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                captureCorners()
                activeHandle = ALL_HANDLES
                var best = TOUCH_RADIUS_DP * density
                for (i in 0 until 4) {
                    val d = hypot(event.x - handles[i * 2], event.y - handles[i * 2 + 1])
                    if (d < best) {
                        best = d
                        activeHandle = i
                    }
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeHandle == NO_HANDLE) return true
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                handles.copyInto(handlesBackup)
                if (activeHandle == ALL_HANDLES) {
                    for (i in 0 until 4) {
                        handles[i * 2] += dx
                        handles[i * 2 + 1] += dy
                    }
                } else {
                    handles[activeHandle * 2] += dx
                    handles[activeHandle * 2 + 1] += dy
                }
                // A degenerate quad has no valid mapping; keep the last good one.
                if (!applyHandles()) handlesBackup.copyInto(handles)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> activeHandle = NO_HANDLE
        }
        return true
    }

    /**
     * One finger drags, two fingers zoom and rotate. Touch points are mapped back into paper space
     * first so the image slides along the (possibly tilted) paper.
     */
    private fun handleTransformTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                paperPoint(event, 0)
                lastX = pts[0]
                lastY = pts[1]
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) startPinch(event, 0, 1)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    paperPoints(event, 0, 1)
                    val midX = (pts[0] + pts[2]) / 2f
                    val midY = (pts[1] + pts[3]) / 2f
                    val dist = hypot(pts[2] - pts[0], pts[3] - pts[1])
                    val angle = angleOf()
                    transform.postTranslate(midX - lastMidX, midY - lastMidY)
                    if (lastDist > 10f) {
                        val s = dist / lastDist
                        transform.postScale(s, s, midX, midY)
                    }
                    var delta = angle - lastAngle
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    transform.postRotate(delta, midX, midY)
                    lastMidX = midX
                    lastMidY = midY
                    lastDist = dist
                    lastAngle = angle
                } else {
                    paperPoint(event, 0)
                    transform.postTranslate(pts[0] - lastX, pts[1] - lastY)
                    lastX = pts[0]
                    lastY = pts[1]
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Re-anchor on the fingers that stay down so the image doesn't jump.
                val remaining = (0 until event.pointerCount).filter { it != event.actionIndex }
                if (remaining.size >= 2) {
                    startPinch(event, remaining[0], remaining[1])
                } else if (remaining.size == 1) {
                    paperPoint(event, remaining[0])
                    lastX = pts[0]
                    lastY = pts[1]
                }
            }
        }
        return true
    }

    private fun paperPoint(event: MotionEvent, i: Int) {
        pts[0] = event.getX(i)
        pts[1] = event.getY(i)
        perspectiveInverse.mapPoints(pts, 0, pts, 0, 1)
    }

    private fun paperPoints(event: MotionEvent, i0: Int, i1: Int) {
        pts[0] = event.getX(i0)
        pts[1] = event.getY(i0)
        pts[2] = event.getX(i1)
        pts[3] = event.getY(i1)
        perspectiveInverse.mapPoints(pts)
    }

    private fun startPinch(event: MotionEvent, i0: Int, i1: Int) {
        paperPoints(event, i0, i1)
        lastMidX = (pts[0] + pts[2]) / 2f
        lastMidY = (pts[1] + pts[3]) / 2f
        lastDist = hypot(pts[2] - pts[0], pts[3] - pts[1])
        lastAngle = angleOf()
    }

    private fun angleOf(): Float =
        Math.toDegrees(atan2((pts[3] - pts[1]).toDouble(), (pts[2] - pts[0]).toDouble())).toFloat()

    private companion object {
        const val NO_HANDLE = -1
        const val ALL_HANDLES = 4
        const val HANDLE_RADIUS_DP = 12f
        const val TOUCH_RADIUS_DP = 48f
    }
}
