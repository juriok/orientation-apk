package si.rok.orientacija.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.views.overlay.TilesOverlay

/**
 * Draws the lidar relief, either blended over the map or on its own.
 *
 * Over a base map the shading composites with MULTIPLY: the raster is light with near-white
 * flats, so multiplying darkens only shadowed slopes and leaves the map's colours and
 * contour lines intact. Plain alpha would wash the whole map toward grey and bury exactly
 * the linework you are reading.
 *
 * With no base map there is nothing to multiply against — the shading would composite
 * against an empty canvas and collapse to black — so it is drawn directly instead and
 * stretched for contrast.
 *
 * Every tone decision is anchored on the raster's measured distribution rather than on
 * mid-grey: sampled across flat, hilly and alpine ground it runs p5 = 84, median = 177,
 * p95 = 236. It is a bright image, and treating it as if it were centred on 128 pushes most
 * of every tile to white, which for MULTIPLY means no effect at all.
 */
class HillshadeOverlay(
    context: Context,
    provider: MapTileProviderBasic
) : TilesOverlay(provider, context) {

    /** Overall effect, 0-255. Over a map this is shading depth; alone it is opacity. */
    var strength: Int = DEFAULT_STRENGTH
        set(value) { field = value.coerceIn(0, 255); refreshPaint() }

    /** 0-100, 50 is neutral. Lifts or lowers the whole relief. */
    var brightness: Int = DEFAULT_BRIGHTNESS
        set(value) { field = value.coerceIn(0, 100); refreshPaint() }

    /** 0-100. Expands the raster's narrow range so fine terrain detail separates. */
    var contrast: Int = DEFAULT_CONTRAST
        set(value) { field = value.coerceIn(0, 100); refreshPaint() }

    /** True when no base map is showing, so the relief must be drawn rather than blended. */
    var standalone: Boolean = false
        set(value) { field = value; refreshPaint() }

    /** Night tint, applied only when standalone — otherwise the base map already carries it. */
    var nightMatrix: ColorMatrix? = null
        set(value) { field = value; refreshPaint() }

    private val blendPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    init {
        // The base map already fills these areas; drawing loading placeholders over it would
        // flash grey blocks across a perfectly good map while relief tiles arrive.
        loadingBackgroundColor = android.graphics.Color.TRANSPARENT
        loadingLineColor = android.graphics.Color.TRANSPARENT
        refreshPaint()
    }

    private fun refreshPaint() {
        val slope = 0.6f + (contrast / 100f) * 1.8f
        val lift = (brightness - 50) / 50f * 70f
        var matrix = if (standalone) standaloneCurve(slope, lift) else blendedCurve(slope, lift)

        // Only tint here when the relief is what you are looking at. Blended over a base map
        // the tint is already carried by the map underneath, and applying it twice would
        // push the result well past red into unreadable.
        if (standalone) nightMatrix?.let { matrix = MapFilters.compose(matrix, it) }

        blendPaint.colorFilter = ColorMatrixColorFilter(matrix)
        if (standalone) {
            blendPaint.xfermode = null
            blendPaint.alpha = strength
        } else {
            blendPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            blendPaint.alpha = 255
        }
    }

    /**
     * Anchored so a fully lit slope maps to white — the identity for MULTIPLY, leaving the
     * map untouched there — and everything below it darkens. Strength then scales the whole
     * effect back toward white, because fading a MULTIPLY source does not fade its effect.
     */
    private fun blendedCurve(slope: Float, lift: Float): ColorMatrix {
        val k = strength / 255f
        val scale = k * slope
        val offset = k * (255f + lift - slope * FULLY_LIT) + (1f - k) * 255f
        return ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, offset,
                0f, scale, 0f, 0f, offset,
                0f, 0f, scale, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    /**
     * Expands about the median so the image keeps its overall lightness as contrast rises,
     * rather than sliding toward black or white. Spends the full 0-255 range on terrain that
     * natively occupies only 84-236.
     */
    private fun standaloneCurve(slope: Float, lift: Float): ColorMatrix {
        val offset = MID_TARGET + lift - slope * MEDIAN
        return ColorMatrix(
            floatArrayOf(
                slope, 0f, 0f, 0f, offset,
                0f, slope, 0f, 0f, offset,
                0f, 0f, slope, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    override fun onTileReadyToDraw(c: Canvas, currentMapTile: Drawable, tileRect: Rect) {
        // Draw the bitmap through our own paint rather than the drawable's, so the blend
        // mode is never written onto a tile the cache may hand to someone else.
        val bmp = (currentMapTile as? BitmapDrawable)?.bitmap
        if (bmp != null && !bmp.isRecycled) {
            c.drawBitmap(bmp, null, tileRect, blendPaint)
        } else {
            currentMapTile.setBounds(tileRect)
            currentMapTile.draw(c)
        }
    }

    companion object {
        /** 95th percentile of the relief raster: treated as fully lit, so left unshaded. */
        private const val FULLY_LIT = 236f
        /** Measured median across flat, hilly and alpine ground. */
        private const val MEDIAN = 177f
        /** Where the median lands when viewing relief alone: light, with headroom. */
        private const val MID_TARGET = 172f

        const val DEFAULT_STRENGTH = 230
        const val DEFAULT_BRIGHTNESS = 50
        const val DEFAULT_CONTRAST = 45
    }
}
