package si.rok.orientacija.map

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.MapTileIndex

/**
 * Serves CLSS relief to the map the same way a tile server would.
 *
 * Nothing here is downloaded as a tile — each one is drawn on the device from the official
 * square-kilometre sheets — but wrapping it as an ordinary tile provider means the relief
 * overlay, the zoom handling and the in-memory tile cache all work unchanged, and the two
 * relief sources stay interchangeable at the point where one is chosen.
 */
class ClssReliefProvider(context: Context) : MapTileProviderArray(
    SOURCE,
    SimpleRegisterReceiver(context),
    arrayOf<MapTileModuleProviderBase>(ClssTileModule(context))
) {

    companion object {
        val SOURCE: ITileSource = XYTileSource(
            "clss-relief", ClssRelief.MIN_ZOOM, ClssRelief.MAX_ZOOM, 256, ".png",
            arrayOf(""),
            "© Geodetska uprava RS — CLSS 2023-25 (CC BY 4.0)"
        )
    }
}

/**
 * Renders one tile per call, on its own small pool.
 *
 * Two threads, not the usual handful: each miss pulls a megabyte sheet, and a wider pool
 * would mostly succeed at starting several megabyte downloads at once on a phone that is
 * probably on mobile data half way up a hill.
 */
private class ClssTileModule(
    private val context: Context
) : MapTileModuleProviderBase(THREADS, QUEUE) {

    override fun getName(): String = "CLSS relief"

    override fun getThreadGroupName(): String = "clss-relief"

    override fun getUsesDataConnection(): Boolean = true

    override fun getMinimumZoomLevel(): Int = ClssRelief.MIN_ZOOM

    override fun getMaximumZoomLevel(): Int = ClssRelief.MAX_ZOOM

    /** The sheets are the source; there is no server whose address could change. */
    override fun setTileSource(tileSource: ITileSource?) = Unit

    override fun getTileLoader(): TileLoader = object : TileLoader() {
        override fun loadTile(pMapTileIndex: Long): Drawable? {
            val bitmap = ClssRelief.renderTile(
                context,
                MapTileIndex.getX(pMapTileIndex),
                MapTileIndex.getY(pMapTileIndex),
                MapTileIndex.getZoom(pMapTileIndex),
                ClssReliefProvider.SOURCE.tileSizePixels
            ) ?: return null
            return BitmapDrawable(context.resources, bitmap)
        }
    }

    companion object {
        private const val THREADS = 2
        private const val QUEUE = 40
    }
}
