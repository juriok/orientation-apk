package si.rok.orientacija.geo

import org.osmdroid.util.GeoPoint
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web Mercator (EPSG:3857) helpers.
 *
 * Calibration maths runs in Mercator metres rather than degrees: within a single map sheet
 * Mercator is locally conformal, so a projective fit there behaves sensibly. Fitting directly
 * against lat/lon degrees would bake the cos(latitude) distortion into the transform.
 */
object GeoMath {

    const val EARTH_RADIUS = 6378137.0
    const val ORIGIN_SHIFT = PI * EARTH_RADIUS   // 20037508.342789244
    const val MAX_LAT = 85.05112877980659

    fun lonToMercX(lon: Double): Double = lon * ORIGIN_SHIFT / 180.0

    fun latToMercY(lat: Double): Double {
        val clamped = lat.coerceIn(-MAX_LAT, MAX_LAT)
        return ln(tan((90.0 + clamped) * PI / 360.0)) * EARTH_RADIUS
    }

    fun mercXToLon(x: Double): Double = x * 180.0 / ORIGIN_SHIFT

    fun mercYToLat(y: Double): Double = 90.0 - 360.0 * atan(exp(-y / EARTH_RADIUS)) / PI

    fun toMercator(lat: Double, lon: Double) = doubleArrayOf(lonToMercX(lon), latToMercY(lat))

    fun toMercator(p: GeoPoint) = toMercator(p.latitude, p.longitude)

    fun fromMercator(x: Double, y: Double) = GeoPoint(mercYToLat(y), mercXToLon(x))

    /** Bounding box of an XYZ tile in Mercator metres: [minX, minY, maxX, maxY]. */
    fun tileBounds(x: Int, y: Int, z: Int): DoubleArray {
        val n = 1 shl z
        val span = 2.0 * ORIGIN_SHIFT / n
        val minX = -ORIGIN_SHIFT + x * span
        val maxY = ORIGIN_SHIFT - y * span
        return doubleArrayOf(minX, maxY - span, minX + span, maxY)
    }

    /** Great-circle distance in metres. */
    fun distance(a: GeoPoint, b: GeoPoint): Double = a.distanceToAsDouble(b)

    /** Initial true bearing from [a] to [b], degrees clockwise from north. */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Ground resolution in metres per pixel, for scale bars and download estimates. */
    fun resolution(lat: Double, zoom: Int): Double =
        cos(Math.toRadians(lat)) * 2.0 * ORIGIN_SHIFT / (256.0 * (1 shl zoom))

    fun inverseGudermannian(y: Double): Double = atan(sinh(y))
}
