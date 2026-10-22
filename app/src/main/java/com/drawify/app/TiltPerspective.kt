package com.drawify.app

import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Builds the perspective that makes a flat image look like it lies on a horizontal sheet of paper
 * when the phone is tilted.
 *
 * Idea: a virtual camera at the same spot as the real one but looking straight down would see the
 * paper undistorted. Both cameras share a center, so the two views differ only by a rotation Q and
 * map onto each other with the homography H = K * Q * K^-1 (K = camera intrinsics in screen
 * pixels). Gravity gives us Q.
 */
object TiltPerspective {

    /** Tilts steeper than this (from looking straight down) can't be corrected sensibly. */
    const val MAX_TILT_DEGREES = 72f

    /** Angle in degrees between the camera axis and straight down, from a device-frame "up" vector. */
    fun tiltDegrees(up: FloatArray): Float {
        val n = norm(up)
        if (n == 0f) return 0f
        return Math.toDegrees(acos((up[2] / n).coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    /**
     * Writes into [out] the matrix that maps "paper space" (screen coordinates as if the phone were
     * held flat above the paper) to the real screen. [up] is the gravity sensor reading in device
     * coordinates, [focalPx] the camera focal length in screen pixels.
     *
     * Returns false (leaving [out] untouched) when the phone is tilted too far.
     */
    fun compute(up: FloatArray, viewW: Int, viewH: Int, focalPx: Float, out: Matrix): Boolean {
        val n = norm(up)
        if (n == 0f || viewW == 0 || viewH == 0) return false
        // "Down" in camera coordinates: X right, Y down the screen, Z out through the back camera.
        val dx = -up[0] / n
        val dy = up[1] / n
        val dz = up[2] / n
        if (tiltDegrees(up) > MAX_TILT_DEGREES) return false

        // Q = rotation taking the camera axis (0,0,1) onto d (Rodrigues, v = z x d, c = z . d).
        val vx = -dy
        val vy = dx
        val f = 1f / (1f + dz)
        val q = floatArrayOf(
            1f - vy * vy * f, vx * vy * f, vy,
            vx * vy * f, 1f - vx * vx * f, -vx,
            -vy, vx, 1f - (vx * vx + vy * vy) * f,
        )

        val cx = viewW / 2f
        val cy = viewH / 2f
        // H = K * Q * K^-1
        val kq = floatArrayOf(
            focalPx * q[0] + cx * q[6], focalPx * q[1] + cx * q[7], focalPx * q[2] + cx * q[8],
            focalPx * q[3] + cy * q[6], focalPx * q[4] + cy * q[7], focalPx * q[5] + cy * q[8],
            q[6], q[7], q[8],
        )
        val h = FloatArray(9)
        for (r in 0 until 3) {
            val a = kq[r * 3]
            val b = kq[r * 3 + 1]
            val c = kq[r * 3 + 2]
            h[r * 3] = a / focalPx
            h[r * 3 + 1] = b / focalPx
            h[r * 3 + 2] = c - (a * cx + b * cy) / focalPx
        }
        if (abs(h[8]) < 1e-6f) return false
        for (i in 0 until 9) h[i] /= h[8]
        val hm = Matrix().apply { setValues(h) }

        // The virtual camera looks at the spot under the phone, not where the real camera points.
        // Shift paper space so its center lands where the real screen center hits the paper, and
        // scale it so the image keeps its on-screen size there.
        val inv = Matrix()
        if (!hm.invert(inv)) return false
        val v0 = floatArrayOf(cx, cy)
        inv.mapPoints(v0)
        val probe = floatArrayOf(v0[0], v0[1], v0[0] + 10f, v0[1], v0[0], v0[1] + 10f)
        hm.mapPoints(probe)
        val area = abs(
            (probe[2] - probe[0]) * (probe[5] - probe[1]) - (probe[3] - probe[1]) * (probe[4] - probe[0])
        ) / 100f
        if (area < 1e-4f) return false
        val k = 1f / sqrt(area)

        out.setTranslate(-cx, -cy)
        out.postScale(k, k)
        out.postTranslate(v0[0], v0[1])
        out.postConcat(hm)
        return true
    }

    private fun norm(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
}
