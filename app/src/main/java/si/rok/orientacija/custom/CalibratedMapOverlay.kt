package si.rok.orientacija.custom

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import si.rok.orientacija.geo.GeoMath
import si.rok.orientacija.geo.Transform2D

/**
 * Draws a calibrated user map image warped onto the live map.
 *
 * The transform is built in two hops. The stored calibration maps image pixels to Web
 * Mercator metres; osmdroid's projection maps the world to screen pixels. We recover the
 * second hop as an affine transform by projecting three reference points each frame, then
 * compose the two into a single matrix and hand it to the canvas.
 *
 * Deriving the screen transform from reference points rather than from the image corners
 * matters: at high zoom the corners of a large map sit millions of pixels off-screen, where
 * osmdroid's integer pixel conversion loses accuracy and drags visible error into the part
 * you are actually looking at.
 */
class CalibratedMapOverlay(
    var bitmap: Bitmap?,
    /** Image pixels -> Web Mercator metres. */
    var pixelToMercator: DoubleArray
) : Overlay() {

    var opacity: Int = 255
        set(value) { field = value.coerceIn(0, 255); paint.alpha = field }

    /** Night tint, so a user's own sheet dims with the rest of the map rather than glaring. */
    var nightMatrix: android.graphics.ColorMatrix? = null
        set(value) {
            field = value
            paint.colorFilter = value?.let { android.graphics.ColorMatrixColorFilter(it) }
        }

    private val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
        isDither = true
        alpha = 255
    }

    private val androidMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val scratch = Point()

    override fun draw(canvas: Canvas, projection: Projection) {
        val bmp = bitmap ?: return
        if (bmp.isRecycled) return

        val mercToScreen = deriveMercatorToScreen(projection) ?: return
        val pixelToScreen = Transform2D.multiply(mercToScreen, pixelToMercator)

        // Skip the draw entirely when the image cannot touch the viewport. drawBitmap with a
        // wildly off-screen matrix is not free, and this runs on every frame of a pan.
        if (!intersectsViewport(pixelToScreen, bmp.width, bmp.height, canvas.width, canvas.height)) return

        for (i in 0..8) matrixValues[i] = pixelToScreen[i].toFloat()
        androidMatrix.setValues(matrixValues)
        canvas.drawBitmap(bmp, androidMatrix, paint)
    }

    /**
     * Recovers the Mercator-metres-to-screen-pixels transform from the live projection.
     *
     * Three points fix an affine exactly. They are spread across the visible area rather than
     * bunched together, because a short baseline would amplify osmdroid's integer pixel
     * rounding into a visible skew across the whole image.
     */
    private fun deriveMercatorToScreen(projection: Projection): DoubleArray? {
        val bb = projection.boundingBox ?: return null
        val cLat = bb.centerLatitude
        val cLon = bb.centerLongitude
        val center = GeoMath.toMercator(cLat, cLon)

        // Baseline of roughly a viewport width, floored so a tiny bounding box still works.
        val spanX = GeoMath.lonToMercX(bb.lonEast) - GeoMath.lonToMercX(bb.lonWest)
        val d = (Math.abs(spanX) * 0.4).coerceAtLeast(50.0)

        val src = listOf(
            doubleArrayOf(center[0], center[1]),
            doubleArrayOf(center[0] + d, center[1]),
            doubleArrayOf(center[0], center[1] + d)
        )
        val dst = ArrayList<DoubleArray>(3)
        for (p in src) {
            val gp = GeoMath.fromMercator(p[0], p[1])
            projection.toPixels(gp, scratch)
            dst.add(doubleArrayOf(scratch.x.toDouble(), scratch.y.toDouble()))
        }
        // Affine, not homography: the map projection to screen has no perspective component.
        return Transform2D.fit(src, dst, Transform2D.Model.AFFINE)?.matrix
    }

    private fun intersectsViewport(m: DoubleArray, w: Int, h: Int, vw: Int, vh: Int): Boolean {
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        val corners = arrayOf(
            doubleArrayOf(0.0, 0.0), doubleArrayOf(w.toDouble(), 0.0),
            doubleArrayOf(w.toDouble(), h.toDouble()), doubleArrayOf(0.0, h.toDouble())
        )
        for (c in corners) {
            val p = Transform2D.apply(m, c[0], c[1])
            if (p[0].isNaN() || p[1].isNaN()) return true // degenerate: let the canvas clip it
            if (p[0] < minX) minX = p[0]; if (p[0] > maxX) maxX = p[0]
            if (p[1] < minY) minY = p[1]; if (p[1] > maxY) maxY = p[1]
        }
        return maxX >= 0 && minX <= vw && maxY >= 0 && minY <= vh
    }

    override fun onDetach(mapView: org.osmdroid.views.MapView?) {
        // The bitmap is owned by the activity, which may reuse it across overlays.
        bitmap = null
        super.onDetach(mapView)
    }
}
