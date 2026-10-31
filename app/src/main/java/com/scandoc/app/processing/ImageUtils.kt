package com.scandoc.app.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImageUtils {

    /** Carica un JPEG rispettando l'orientamento EXIF e limitando il lato lungo a [maxSide]. */
    fun loadBitmap(file: File, maxSide: Int = 3200): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw IllegalArgumentException("Immagine non leggibile")

        val longest = max(bmp.width, bmp.height)
        if (longest > maxSide) {
            val k = maxSide.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * k).roundToInt(), (bmp.height * k).roundToInt(), true)
            if (scaled != bmp) bmp.recycle()
            bmp = scaled
        }

        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return ensureArgb(bmp)
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated != bmp) bmp.recycle()
        return ensureArgb(rotated)
    }

    private fun ensureArgb(b: Bitmap): Bitmap =
        if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val k = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src, max(1, (src.width * k).roundToInt()), max(1, (src.height * k).roundToInt()), true
        )
    }

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int = 90) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }
}
