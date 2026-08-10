package si.rok.orientacija.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

object BitmapUtils {

    /**
     * Phone cameras produce images far larger than any phone can hold as a decoded ARGB
     * bitmap — a 48 MP shot would need well over 100 MB. Calibration and display need
     * detail, not full resolution, so images are capped at this dimension.
     */
    const val MAX_DIMENSION = 2560

    /**
     * Decodes an image at a sane size, with EXIF rotation already applied.
     *
     * Rotation must be baked in here rather than handled at draw time: the calibration
     * records control points in image pixel coordinates, so every later stage has to agree
     * on which way is up.
     */
    fun loadScaled(context: Context, uri: Uri, maxDim: Int = MAX_DIMENSION): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
            inPreferredConfig = Bitmap.Config.RGB_565  // map images have no alpha; halves memory
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val orientation = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        return applyOrientation(decoded, orientation)
    }

    fun loadScaled(file: File, maxDim: Int = MAX_DIMENSION): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun sampleSizeFor(w: Int, h: Int, maxDim: Int): Int {
        var sample = 1
        var cw = w
        var ch = h
        while (cw / 2 >= maxDim || ch / 2 >= maxDim) {
            cw /= 2; ch /= 2; sample *= 2
        }
        return sample
    }

    private fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return src
        }
        return try {
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            if (out != src) src.recycle()
            out
        } catch (e: OutOfMemoryError) {
            src
        }
    }

    /** Persists a bitmap into app storage so the calibration survives the source Uri going away. */
    fun saveJpeg(bitmap: Bitmap, dest: File, quality: Int = 92): Boolean = try {
        FileOutputStream(dest).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        true
    } catch (e: Exception) {
        false
    }
}
