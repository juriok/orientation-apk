package si.rok.orientacija.map

import android.content.Context
import android.graphics.Bitmap
import si.rok.orientacija.geo.CoordinateSystems
import si.rok.orientacija.geo.GeoMath
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws CLSS shading into ordinary web-map tiles.
 *
 * The sheets are on Slovenia's D96/TM grid and the map is in Web Mercator, so every tile
 * is a reprojection rather than a copy. It is done by walking the tile's own pixels and
 * asking where each one lands on the ground — the direction that cannot leave gaps, unlike
 * pushing sheet pixels forward into the tile.
 *
 * The projection is evaluated on a coarse grid and interpolated between, rather than run
 * for all 65 536 pixels: a transverse Mercator varies smoothly, so over a sixteen-pixel
 * cell the difference is far below what a pixel can show, and this turns roughly sixty-five
 * thousand series evaluations into under three hundred.
 */
object ClssRelief {

    /** Spacing of the reprojection grid, in tile pixels. */
    private const val CELL = 16

    /**
     * Zoom from which missing sheets are fetched. Below it whatever is already on the
     * device still draws, but nothing new is pulled: one tile at z13 spans some nine
     * square kilometres and a screenful would be a hundred megabytes of sheets for a view
     * too coarse to show what they contain.
     */
    const val FETCH_MIN_ZOOM = 14

    /** Below this the relief is not drawn at all; a whole sheet is under a pixel by then. */
    const val MIN_ZOOM = 9
    const val MAX_ZOOM = 20

    /**
     * Renders one tile, or returns null when no sheet covers any of it — a blank tile and
     * an absent one are different things to the tile cache, and only the second should be
     * asked for again later.
     */
    fun renderTile(context: Context, x: Int, y: Int, zoom: Int, tileSize: Int): Bitmap? {
        val bounds = GeoMath.tileBounds(x, y, zoom)
        val minX = bounds[0]; val minY = bounds[1]
        val maxX = bounds[2]; val maxY = bounds[3]

        val nodes = tileSize / CELL + 1
        val gridE = DoubleArray(nodes * nodes)
        val gridN = DoubleArray(nodes * nodes)
        for (j in 0 until nodes) {
            val py = min(j * CELL, tileSize).toDouble()
            val mercY = maxY - (maxY - minY) * py / tileSize
            for (i in 0 until nodes) {
                val px = min(i * CELL, tileSize).toDouble()
                val mercX = minX + (maxX - minX) * px / tileSize
                val geo = GeoMath.fromMercator(mercX, mercY)
                val d96 = CoordinateSystems.toD96TM(geo.latitude, geo.longitude)
                gridE[j * nodes + i] = d96.easting
                gridN[j * nodes + i] = d96.northing
            }
        }

        // How much ground one tile pixel covers, which decides whether a single sample of
        // the 1 m shading represents it or whether it has to be averaged down.
        val centre = GeoMath.fromMercator((minX + maxX) / 2, (minY + maxY) / 2)
        val metresPerPixel = GeoMath.resolution(centre.latitude, zoom) * (256.0 / tileSize)
        val box = (metresPerPixel / ClssSheets.SHEET_RESOLUTION).roundToInt().coerceIn(1, 4)

        val allowFetch = zoom >= FETCH_MIN_ZOOM
        val sheets = SheetWindow(context, allowFetch)
        val pixels = IntArray(tileSize * tileSize)
        var drawn = 0

        for (py in 0 until tileSize) {
            val j = py / CELL
            val fy = (py % CELL) / CELL.toDouble()
            for (px in 0 until tileSize) {
                val i = px / CELL
                val fx = (px % CELL) / CELL.toDouble()

                val e = bilinear(gridE, nodes, i, j, fx, fy)
                val n = bilinear(gridN, nodes, i, j, fx, fy)
                val grey = sample(sheets, e, n, box)
                if (grey >= 0) {
                    pixels[py * tileSize + px] =
                        (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
                    drawn++
                }
                // Anything not covered stays fully transparent. The relief is composited
                // with a multiply blend, under which a transparent pixel leaves the map
                // beneath it exactly as it was.
            }
        }
        if (drawn == 0) return null

        return Bitmap.createBitmap(pixels, tileSize, tileSize, Bitmap.Config.ARGB_8888)
    }

    /** True when every sheet this tile needs is already on the device. */
    fun isTileHeld(context: Context, x: Int, y: Int, zoom: Int): Boolean {
        val b = GeoMath.tileBounds(x, y, zoom)
        val corners = listOf(
            GeoMath.fromMercator(b[0], b[1]), GeoMath.fromMercator(b[0], b[3]),
            GeoMath.fromMercator(b[2], b[1]), GeoMath.fromMercator(b[2], b[3])
        )
        var minE = Double.MAX_VALUE; var maxE = -Double.MAX_VALUE
        var minN = Double.MAX_VALUE; var maxN = -Double.MAX_VALUE
        for (c in corners) {
            val p = CoordinateSystems.toD96TM(c.latitude, c.longitude)
            minE = min(minE, p.easting); maxE = max(maxE, p.easting)
            minN = min(minN, p.northing); maxN = max(maxN, p.northing)
        }
        for (e in ClssSheets.eastKmOf(minE)..ClssSheets.eastKmOf(maxE)) {
            for (n in ClssSheets.northKmOf(minN)..ClssSheets.northKmOf(maxN)) {
                if (!ClssSheets.isHeld(context, e, n)) return false
            }
        }
        return true
    }

    /**
     * Grey at one place on the ground, averaged over [box] samples a side, or -1 where
     * there is no sheet. Averaging rather than picking one pixel matters when zoomed out:
     * metre-resolution shading sampled at thirty metres a pixel is otherwise pure noise.
     */
    private fun sample(sheets: SheetWindow, easting: Double, northing: Double, box: Int): Int {
        if (box == 1) return sheets.grey(easting, northing)
        var total = 0
        var count = 0
        val step = ClssSheets.SHEET_RESOLUTION
        val offset = (box - 1) / 2.0
        for (dy in 0 until box) {
            for (dx in 0 until box) {
                val g = sheets.grey(
                    easting + (dx - offset) * step,
                    northing + (offset - dy) * step
                )
                if (g >= 0) { total += g; count++ }
            }
        }
        return if (count == 0) -1 else total / count
    }

    private fun bilinear(grid: DoubleArray, nodes: Int, i: Int, j: Int, fx: Double, fy: Double): Double {
        val i1 = min(i + 1, nodes - 1)
        val j1 = min(j + 1, nodes - 1)
        val a = grid[j * nodes + i]
        val b = grid[j * nodes + i1]
        val c = grid[j1 * nodes + i]
        val d = grid[j1 * nodes + i1]
        val top = a + (b - a) * fx
        val bottom = c + (d - c) * fx
        return top + (bottom - top) * fy
    }

    /**
     * The handful of sheets one tile touches, held for the length of that tile.
     *
     * Every pixel of a tile asks for a sheet, so the lookup has to be a field compare
     * rather than a map probe in the common case of the previous pixel's sheet being the
     * right one again.
     */
    private class SheetWindow(private val context: Context, private val allowFetch: Boolean) {

        private val loaded = HashMap<String, ByteArray?>(8)
        private var lastEastKm = Int.MIN_VALUE
        private var lastNorthKm = Int.MIN_VALUE
        private var last: ByteArray? = null

        fun grey(easting: Double, northing: Double): Int {
            val eKm = ClssSheets.eastKmOf(easting)
            val nKm = ClssSheets.northKmOf(northing)
            if (eKm != lastEastKm || nKm != lastNorthKm) {
                lastEastKm = eKm; lastNorthKm = nKm
                val k = ClssSheets.key(eKm, nKm)
                last = if (loaded.containsKey(k)) loaded[k]
                       else ClssSheets.sheet(context, eKm, nKm, allowFetch).also { loaded[k] = it }
            }
            val data = last ?: return -1

            val px = ((easting - eKm * ClssSheets.SHEET_METRES) / ClssSheets.SHEET_RESOLUTION).toInt()
            // Rows run north to south, so northing counts down from the sheet's top edge.
            val py = (((nKm + 1) * ClssSheets.SHEET_METRES - northing) / ClssSheets.SHEET_RESOLUTION).toInt()
            if (px < 0 || py < 0 || px >= ClssSheets.SHEET_PIXELS || py >= ClssSheets.SHEET_PIXELS) return -1
            return data[py * ClssSheets.SHEET_PIXELS + px].toInt() and 0xFF
        }
    }
}
