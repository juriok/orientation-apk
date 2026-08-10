package si.rok.orientacija.map

import android.content.Context
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.BoundingBox
import si.rok.orientacija.geo.GeoMath
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Pre-fetches tiles into osmdroid's on-device cache.
 *
 * Downloads run one source after another rather than in parallel: the relief endpoint 504s
 * under cold load as it is, and hammering it alongside the base map would turn a slow
 * download into a failed one.
 */
object OfflineDownloader {

    interface Listener {
        fun onProgress(sourceLabel: String, done: Int, total: Int)
        fun onFinished()
        fun onFailed(reason: String)
    }

    /**
     * Counts tiles before committing. Worth showing: the count grows fourfold per zoom
     * level, so one extra level on a large box turns 200 MB into 800 MB.
     */
    fun estimate(box: BoundingBox, zoomMin: Int, zoomMax: Int, sources: Int = 1): Pair<Int, Long> {
        var tiles = 0L
        for (z in zoomMin..zoomMax) {
            val x0 = lonToTileX(box.lonWest, z)
            val x1 = lonToTileX(box.lonEast, z)
            val y0 = latToTileY(box.latNorth, z)
            val y1 = latToTileY(box.latSouth, z)
            tiles += (x1 - x0 + 1).coerceAtLeast(1).toLong() * (y1 - y0 + 1).coerceAtLeast(1).toLong()
        }
        tiles *= sources
        // GURS PNGs measured around 60 KB; a rough figure beats none.
        return Pair(tiles.toInt(), tiles * 60L * 1024L)
    }

    /** Downloads [sources] in turn, reporting progress against each. */
    fun download(
        context: Context,
        box: BoundingBox,
        zoomMin: Int,
        zoomMax: Int,
        sources: List<Pair<String, ITileSource>>,
        listener: Listener
    ) {
        if (sources.isEmpty()) { listener.onFinished(); return }
        downloadOne(context, box, zoomMin, zoomMax, sources, 0, listener)
    }

    private fun downloadOne(
        context: Context,
        box: BoundingBox,
        zoomMin: Int,
        zoomMax: Int,
        sources: List<Pair<String, ITileSource>>,
        index: Int,
        listener: Listener
    ) {
        if (index >= sources.size) { listener.onFinished(); return }
        val (label, source) = sources[index]
        // The relief only exists from z11; asking below that would queue thousands of
        // requests that can only come back blank.
        val lo = if (source === MapLayers.HILLSHADE) maxOf(zoomMin, MapLayers.HILLSHADE_MIN_ZOOM) else zoomMin
        if (lo > zoomMax) {
            downloadOne(context, box, zoomMin, zoomMax, sources, index + 1, listener)
            return
        }
        try {
            val manager = CacheManager(source, SqlTileWriter(), lo, zoomMax)
            manager.downloadAreaAsync(context, box, lo, zoomMax,
                object : CacheManager.CacheManagerCallback {
                    override fun onTaskComplete() =
                        downloadOne(context, box, zoomMin, zoomMax, sources, index + 1, listener)

                    override fun onTaskFailed(errors: Int) {
                        // Individual tile failures are expected against the relief service;
                        // carry on to the next source rather than abandoning the download.
                        downloadOne(context, box, zoomMin, zoomMax, sources, index + 1, listener)
                    }

                    override fun updateProgress(progress: Int, zoom: Int, zMin: Int, zMax: Int) =
                        listener.onProgress(label, progress, 0)

                    override fun downloadStarted() {}
                    override fun setPossibleTilesInArea(total: Int) = listener.onProgress(label, 0, total)
                })
        } catch (e: Exception) {
            listener.onFailed(e.message ?: "Napaka pri prenosu")
        }
    }

    /** Removes every cached tile inside [box] for [source], for the pack manager. */
    fun clearArea(
        context: Context,
        source: ITileSource,
        box: BoundingBox,
        zoomMin: Int,
        zoomMax: Int,
        onDone: () -> Unit
    ) {
        try {
            val manager = CacheManager(source, SqlTileWriter(), zoomMin, zoomMax)
            manager.cleanAreaAsync(context, box, zoomMin, zoomMax)
            onDone()
        } catch (e: Exception) {
            onDone()
        }
    }

    /** Bytes currently held by the tile cache, or -1 if it cannot be read. */
    fun cacheUsage(): Long = try {
        SqlTileWriter().let { writer ->
            val used = CacheManager(MapLayers.TOPO.source, writer, 4, 19).currentCacheUsage()
            used
        }
    } catch (e: Exception) {
        -1L
    }

    private fun lonToTileX(lon: Double, z: Int): Int =
        floor((lon + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    private fun latToTileY(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat.coerceIn(-GeoMath.MAX_LAT, GeoMath.MAX_LAT))
        return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / Math.PI) / 2.0 * (1 shl z))
            .toInt().coerceIn(0, (1 shl z) - 1)
    }
}
