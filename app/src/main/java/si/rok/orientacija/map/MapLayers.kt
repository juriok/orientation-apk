package si.rok.orientacija.map

import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import si.rok.orientacija.geo.GeoMath
import java.util.Locale

private const val GURS_DK = "https://storitve.eprostor.gov.si/ows-pub-wms/SI.GURS.DK/ows"
private const val GURS_DTS = "https://ipi.eprostor.gov.si/wms-si-gurs-dts/wms"
private const val ATTRIBUTION_GURS = "© Geodetska uprava RS (CC BY 4.0)"

/** Builds the WMS GetMap URL for one XYZ tile, in EPSG:3857. */
private fun wmsUrl(endpoint: String, layer: String, x: Int, y: Int, z: Int, transparent: Boolean): String {
    val b = GeoMath.tileBounds(x, y, z)
    fun f(v: Double) = String.format(Locale.US, "%.4f", v)
    return "$endpoint?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=$layer" +
        "&STYLES=&CRS=EPSG:3857" +
        "&BBOX=${f(b[0])},${f(b[1])},${f(b[2])},${f(b[3])}" +
        "&WIDTH=256&HEIGHT=256&FORMAT=image/png" +
        "&TRANSPARENT=${if (transparent) "TRUE" else "FALSE"}"
}

/**
 * Turns a WMS endpoint into something osmdroid can treat as an ordinary XYZ tile pyramid.
 *
 * GURS publishes its maps as WMS rather than pre-cut tiles. Since the service advertises
 * EPSG:3857, each XYZ tile is exactly one GetMap call for that tile's bounding box.
 */
class WmsTileSource(
    name: String,
    private val endpoint: String,
    private val layer: String,
    minZoom: Int,
    maxZoom: Int,
    private val transparent: Boolean = false,
    copyright: String = ATTRIBUTION_GURS
) : OnlineTileSourceBase(name, minZoom, maxZoom, 256, ".png", arrayOf(endpoint), copyright) {

    override fun getTileURLString(pMapTileIndex: Long): String = wmsUrl(
        endpoint, layer,
        MapTileIndex.getX(pMapTileIndex),
        MapTileIndex.getY(pMapTileIndex),
        MapTileIndex.getZoom(pMapTileIndex),
        transparent
    )
}

/**
 * The Slovenian state topographic map as one continuous layer.
 *
 * Each GURS layer is only drawn inside its own scale band — the server returns a blank
 * image outside it rather than an error. DTK50 in particular renders only at z14-16, so a
 * plain DTK50 source turns the screen white the moment you zoom out, and DPK250 (z12-13)
 * is blank at every zoom you would normally navigate at.
 *
 * Measured bands, probed against the live service:
 *   DPK1000/750 z9-13 · DPK500 z11-13 · DPK250 z12-13 · DTK50 z14-16
 *
 * Picking the layer per tile gives an unbroken map from country view down to 1:50,000,
 * with the most detailed product available at every step.
 */
class GursTopoTileSource : OnlineTileSourceBase(
    "gurs-topo", MIN_ZOOM, MAX_ZOOM, 256, ".png", arrayOf(GURS_DK),
    "$ATTRIBUTION_GURS · pri majhnih merilih © OpenTopoMap (CC-BY-SA), © OpenStreetMap"
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val z = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)

        // Below z9 the GURS catalogue has nothing at all, so hand off to OpenTopoMap rather
        // than let the map go blank. The status line names the active source either way.
        if (z < GURS_MIN_ZOOM) {
            val sub = SUBDOMAINS[((x + y) % SUBDOMAINS.size + SUBDOMAINS.size) % SUBDOMAINS.size]
            return "https://$sub.tile.opentopomap.org/$z/$x/$y.png"
        }

        val layer = when {
            z >= 14 -> "DTK50"    // 1:50,000 — the real topographic sheet
            z >= 12 -> "DPK250"
            z >= 11 -> "DPK500"
            else -> "DPK750"
        }
        return wmsUrl(GURS_DK, layer, x, y, z, false)
    }

    companion object {
        private val SUBDOMAINS = arrayOf("a", "b", "c")
        /** Below this, no GURS product renders and OpenTopoMap takes over. */
        const val GURS_MIN_ZOOM = 9
        const val MIN_ZOOM = 4
        const val MAX_ZOOM = 16

        /** Names the product actually being drawn, for the status readout. */
        fun sourceLabelFor(zoom: Int): String = when {
            zoom < GURS_MIN_ZOOM -> "OpenTopoMap"
            zoom >= 14 -> "DTK 50"
            zoom >= 12 -> "DPK 250"
            zoom >= 11 -> "DPK 500"
            else -> "DPK 750"
        }
    }
}

/** One selectable base map, with the zoom range the map view should clamp to. */
data class LayerDef(
    val id: String,
    val label: String,
    val description: String,
    val source: ITileSource,
    val minZoom: Double,
    val maxZoom: Double,
    /**
     * Last zoom at which this source still carries genuine detail. Past it the map is only
     * being enlarged, so another layer may be sharper — see auto-sharpening in MainActivity.
     */
    val nativeMaxZoom: Int
)

object MapLayers {

    /** Primary layer: DTK50 up close, overview sheets when zoomed out. */
    val TOPO = LayerDef(
        id = "dtk50",
        label = "DTK 50",
        description = "Topografska karta 1:50.000, pri oddaljenem pogledu pregledne karte",
        source = GursTopoTileSource(),
        minZoom = GursTopoTileSource.MIN_ZOOM.toDouble(),
        // Past z16 osmdroid upscales the z16 tile. The source has no more detail to give,
        // and asking the server for it just returns the same image enlarged.
        maxZoom = 19.0,
        nativeMaxZoom = 16
    )

    /** 25 cm aerial imagery — invaluable for matching a photographed map to the ground. */
    val ORTHOPHOTO = LayerDef(
        id = "dof025",
        label = "Ortofoto",
        description = "Državni ortofoto, ločljivost 25 cm",
        source = WmsTileSource("gurs-dof025", GURS_DTS, "SI.GURS.ZPDZ:DOF025", 9, 18),
        minZoom = 9.0,
        maxZoom = 20.0,
        // 25 cm ground resolution still resolves detail at z18, which is why it is the layer
        // auto-sharpening reaches for once the topo sheets have run out.
        nativeMaxZoom = 18
    )

    /**
     * Worldwide fallback. Covers the whole zoom range, works past the border, and keeps
     * the app usable if the government service is down.
     */
    val OPENTOPO = LayerDef(
        id = "opentopo",
        label = "OpenTopoMap",
        description = "Odprta topografska karta, ves svet",
        source = XYTileSource(
            "OpenTopoMap", 3, 17, 256, ".png",
            arrayOf(
                "https://a.tile.opentopomap.org/",
                "https://b.tile.opentopomap.org/",
                "https://c.tile.opentopomap.org/"
            ),
            "© OpenTopoMap (CC-BY-SA), © OpenStreetMap contributors"
        ),
        minZoom = 3.0,
        maxZoom = 19.0,
        nativeMaxZoom = 17
    )

    /**
     * Lidar-derived relief. Not a base layer — it is blended over whatever else is showing,
     * so contours and true terrain shape can be read at the same time.
     *
     * The service 504s on a cold tile and then serves the same tile in well under a second,
     * so failures here are worth retrying rather than treating as absent.
     */
    val HILLSHADE = WmsTileSource("gurs-lidar", GURS_DTS, "SI.GURS.ZPDZ:LIDAR", 11, 19)

    const val HILLSHADE_MIN_ZOOM = 11

    val ALL = listOf(TOPO, ORTHOPHOTO, OPENTOPO)

    fun byId(id: String?): LayerDef = ALL.firstOrNull { it.id == id } ?: TOPO
}

/**
 * Which lidar scan the relief is drawn from.
 *
 * Slovenia has been scanned twice. The 2011-2014 pass is what the national WMS still
 * serves — the layer's own metadata calls it LIDAR_20112014 — and it covers the country
 * evenly at a resolution that suits a map. The 2023-25 re-scan published at clss.si is
 * sharper and current, but is distributed as files rather than as a map service, so it
 * costs about a megabyte for each square kilometre it is first shown over.
 *
 * Neither supersedes the other in the field, which is why both stay: the old one is
 * instant and everywhere, the new one shows ground that has since moved — landslides,
 * new forest tracks, quarries, the 2023 floods.
 */
enum class ReliefSource(val id: String, val label: String, val description: String) {

    GURS(
        id = "gurs",
        label = "Lidar 2011–2014",
        description = "Državna storitev senčenja, hitra in za vso Slovenijo"
    ),

    CLSS(
        id = "clss",
        label = "CLSS 2023–25",
        description = "Novo ciklično lasersko skeniranje, ostrejše; prenese ~1 MB/km²"
    );

    /** Zoom below which this source has nothing useful to draw. */
    val minZoom: Int
        get() = if (this == CLSS) ClssRelief.MIN_ZOOM else MapLayers.HILLSHADE_MIN_ZOOM

    companion object {
        fun byId(id: String?): ReliefSource = entries.firstOrNull { it.id == id } ?: GURS
    }
}

