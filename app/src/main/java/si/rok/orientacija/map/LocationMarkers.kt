package si.rok.orientacija.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.DisplayMetrics

/**
 * High-contrast position markers.
 *
 * The stock osmdroid arrow is a flat light shape, which vanishes over snow, bare rock, pale
 * fields and the white paper of a scanned map — exactly the surfaces this app spends its
 * time over. These markers are drawn as three stacked strokes: a white halo, a near-black
 * outline, then a saturated orange fill. Something in that stack contrasts against any
 * background, light or dark, so the marker never disappears.
 */
object LocationMarkers {

    private const val FILL = 0xFFFF6D00.toInt()      // saturated orange, absent from topo maps
    private const val OUTLINE = 0xFF15181C.toInt()   // near-black, reads on light ground
    private const val HALO = 0xFFFFFFFF.toInt()      // white, reads on dark ground

    /** Arrow shown when a bearing is known. Points up; osmdroid rotates it. */
    fun directionArrow(dm: DisplayMetrics): Bitmap = draw(dm) { c, s, cx, cy ->
        val path = Path().apply {
            moveTo(cx, cy - 15f * s)              // tip
            lineTo(cx + 11f * s, cy + 13f * s)    // right shoulder
            lineTo(cx, cy + 6f * s)               // notch, so it reads as an arrow not a triangle
            lineTo(cx - 11f * s, cy + 13f * s)    // left shoulder
            close()
        }
        c.drawPath(path, stroke(HALO, 8f * s))
        c.drawPath(path, stroke(OUTLINE, 3.5f * s))
        c.drawPath(path, fill(FILL))
    }

    /** Dot shown when there is a fix but no reliable heading. */
    fun positionDot(dm: DisplayMetrics): Bitmap = draw(dm) { c, s, cx, cy ->
        val r = 9f * s
        c.drawCircle(cx, cy, r, stroke(HALO, 8f * s))
        c.drawCircle(cx, cy, r, stroke(OUTLINE, 3.5f * s))
        c.drawCircle(cx, cy, r, fill(FILL))
    }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    /**
     * Allocates a square canvas sized in density-independent units, so the marker is the
     * same physical size on every screen. osmdroid rotates about the bitmap centre, so the
     * shape must be centred and the canvas square or the arrow wobbles as it turns.
     */
    private inline fun draw(dm: DisplayMetrics, body: (Canvas, Float, Float, Float) -> Unit): Bitmap {
        val s = dm.density
        val size = (44f * s).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        val centre = size / 2f
        body(Canvas(bmp), s, centre, centre)
        return bmp
    }
}
