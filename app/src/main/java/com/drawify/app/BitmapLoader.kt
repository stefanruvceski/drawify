package com.drawify.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import kotlin.math.max

object BitmapLoader {

    private const val MAX_SIZE = 2048
    private const val LAST_IMAGE = "last_image.png"

    /** Decodes [uri] downsampled to at most [MAX_SIZE] px and rotated per its EXIF data. */
    fun load(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unsupported image")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIZE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Could not decode image")

        val rotation = try {
            resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        } catch (e: Exception) {
            0
        }
        if (rotation == 0) return decoded
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
    }

    fun saveLast(context: Context, bitmap: Bitmap) {
        File(context.filesDir, LAST_IMAGE).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    fun loadLast(context: Context): Bitmap? {
        val file = File(context.filesDir, LAST_IMAGE)
        return if (file.exists()) BitmapFactory.decodeFile(file.path) else null
    }
}
