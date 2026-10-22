package com.drawify.app

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.sqrt

enum class ImageFilter(val labelRes: Int) {
    ORIGINAL(R.string.filter_original),
    NO_BACKGROUND(R.string.filter_no_background),
    OUTLINE(R.string.filter_outline),
}

object ImageFilters {

    fun apply(src: Bitmap, filter: ImageFilter): Bitmap = when (filter) {
        ImageFilter.ORIGINAL -> src
        ImageFilter.NO_BACKGROUND -> removeWhite(src)
        ImageFilter.OUTLINE -> outline(src)
    }

    private fun luminance(c: Int): Float =
        0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)

    /** Makes white/near-white pixels transparent so only the drawing itself is overlaid. */
    private fun removeWhite(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val keep = ((245f - luminance(c)) / 40f).coerceIn(0f, 1f)
            val a = (Color.alpha(c) * keep).toInt()
            px[i] = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Sobel edge detection: black lines on a transparent background, handy for photos. */
    private fun outline(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // Grayscale with transparent areas treated as white, then a 3x3 box blur to cut noise.
        val gray = FloatArray(w * h) { i ->
            val c = px[i]
            val a = Color.alpha(c) / 255f
            luminance(c) * a + 255f * (1f - a)
        }
        val blur = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var n = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        sum += gray[yy * w + xx]
                        n++
                    }
                }
                blur[y * w + x] = sum / n
            }
        }

        val out = IntArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val tl = blur[i - w - 1]; val t = blur[i - w]; val tr = blur[i - w + 1]
                val l = blur[i - 1]; val r = blur[i + 1]
                val bl = blur[i + w - 1]; val b = blur[i + w]; val br = blur[i + w + 1]
                val gx = (tr + 2 * r + br) - (tl + 2 * l + bl)
                val gy = (bl + 2 * b + br) - (tl + 2 * t + tr)
                val mag = sqrt(gx * gx + gy * gy)
                val a = ((mag - 40f) * 2f).coerceIn(0f, 255f).toInt()
                out[i] = Color.argb(a, 0, 0, 0)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
