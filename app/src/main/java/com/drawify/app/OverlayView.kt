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
 * Draws the tracing image on top of the camera preview. While unlocked the image can be
 * dragged with one finger and zoomed/rotated with two.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val transform = Matrix()
    private var needsFit = false

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = 0xAAFFFFFF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
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

    // Gesture state
    private var lastX = 0f
    private var lastY = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var lastDist = 0f
    private var lastAngle = 0f

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

    fun resetPosition() {
        fitToView()
        invalidate()
    }

    fun flipHorizontal() {
        val b = bitmap ?: return
        transform.preScale(-1f, 1f, b.width / 2f, b.height / 2f)
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (needsFit) fitToView()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        canvas.drawBitmap(b, transform, imagePaint)
        if (!locked) {
            val w = b.width.toFloat()
            val h = b.height.toFloat()
            corners[0] = 0f; corners[1] = 0f
            corners[2] = w; corners[3] = 0f
            corners[4] = w; corners[5] = h
            corners[6] = 0f; corners[7] = h
            transform.mapPoints(corners)
            borderPath.reset()
            borderPath.moveTo(corners[0], corners[1])
            borderPath.lineTo(corners[2], corners[3])
            borderPath.lineTo(corners[4], corners[5])
            borderPath.lineTo(corners[6], corners[7])
            borderPath.close()
            canvas.drawPath(borderPath, borderPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (locked || bitmap == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) startPinch(event, 0, 1)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    val dist = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
                    val angle = angleOf(event, 0, 1)
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
                    transform.postTranslate(event.x - lastX, event.y - lastY)
                    lastX = event.x
                    lastY = event.y
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Re-anchor on the fingers that stay down so the image doesn't jump.
                val remaining = (0 until event.pointerCount).filter { it != event.actionIndex }
                if (remaining.size >= 2) {
                    startPinch(event, remaining[0], remaining[1])
                } else if (remaining.size == 1) {
                    lastX = event.getX(remaining[0])
                    lastY = event.getY(remaining[0])
                }
            }
        }
        return true
    }

    private fun startPinch(event: MotionEvent, i0: Int, i1: Int) {
        lastMidX = (event.getX(i0) + event.getX(i1)) / 2f
        lastMidY = (event.getY(i0) + event.getY(i1)) / 2f
        lastDist = hypot(event.getX(i1) - event.getX(i0), event.getY(i1) - event.getY(i0))
        lastAngle = angleOf(event, i0, i1)
    }

    private fun angleOf(event: MotionEvent, i0: Int, i1: Int): Float =
        Math.toDegrees(
            atan2(
                (event.getY(i1) - event.getY(i0)).toDouble(),
                (event.getX(i1) - event.getX(i0)).toDouble(),
            )
        ).toFloat()
}
