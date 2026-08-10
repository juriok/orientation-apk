package si.rok.orientacija.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Coordinate readouts an orienteer actually needs in the field.
 *
 * D96/TM (EPSG:3794) is Slovenia's current official system and the one printed on modern
 * sheets. D48/GK (EPSG:3912) is the legacy Bessel-datum system that the 1996 DTK25 rasters
 * are drawn in, so it is here for reading those older maps. UTM 33N and MGRS cover
 * cross-border and military-style grid references.
 */
object CoordinateSystems {

    /** Transverse Mercator parameters for one projected system. */
    private class TmParams(
        val a: Double,          // semi-major axis
        val invF: Double,       // inverse flattening
        val lon0: Double,       // central meridian, degrees
        val k0: Double,         // scale factor
        val falseEasting: Double,
        val falseNorthing: Double
    ) {
        val f = 1.0 / invF
        val e2 = 2 * f - f * f
        val ep2 = e2 / (1 - e2)
    }

    // GRS80 / ETRS89. WGS84 and ETRS89 differ by well under a metre in Slovenia, which is
    // far below GPS noise, so no datum shift is applied for D96.
    private val D96 = TmParams(6378137.0, 298.257222101, 15.0, 0.9999, 500_000.0, -5_000_000.0)
    private val UTM33 = TmParams(6378137.0, 298.257223563, 15.0, 0.9996, 500_000.0, 0.0)
    // Bessel 1841, used by D48/GK after the datum shift below.
    private val D48 = TmParams(6377397.155, 299.1528128, 15.0, 0.9999, 500_000.0, 0.0)

    class Projected(val easting: Double, val northing: Double)

    // ------------------------------------------------------------------ public

    fun toD96TM(lat: Double, lon: Double): Projected = project(lat, lon, D96)

    fun toUtm33N(lat: Double, lon: Double): Projected = project(lat, lon, UTM33)

    /**
     * D48/GK, via a 7-parameter Helmert shift from WGS84/ETRS89 to the Bessel datum.
     *
     * These are the nationally published D96->D48 parameters. They are a country-wide best
     * fit; the official high-accuracy route uses a distortion grid, so treat this as good to
     * roughly a metre rather than centimetre-exact.
     */
    fun toD48GK(lat: Double, lon: Double): Projected {
        val (bLat, bLon) = wgs84ToBessel(lat, lon)
        return project(bLat, bLon, D48)
    }

    /** Formats an MGRS reference at the given precision (1-5 digits per axis). */
    fun toMgrs(lat: Double, lon: Double, digits: Int = 5): String {
        val zone = floor((lon + 180.0) / 6.0).toInt() + 1
        val params = TmParams(6378137.0, 298.257223563, (zone - 1) * 6.0 - 180.0 + 3.0, 0.9996, 500_000.0, 0.0)
        val p = project(lat, lon, params)
        val band = latitudeBand(lat) ?: return "izven obsega"

        val e100k = floor(p.easting / 100_000.0).toInt()
        val n100k = floor(p.northing / 100_000.0).toInt()
        val setIdx = (zone - 1) % 6
        val colLetters = "ABCDEFGH,JKLMNPQR,STUVWXYZ".split(",")
        val col = colLetters[setIdx % 3].getOrNull(e100k - 1) ?: return "izven obsega"
        val rowLetters = if (setIdx % 2 == 0) "ABCDEFGHJKLMNPQRSTUV" else "FGHJKLMNPQRSTUVABCDE"
        val row = rowLetters[(n100k % 20 + 20) % 20]

        val d = digits.coerceIn(1, 5)
        val div = 10.0.pow(5 - d)
        val e = floor((p.easting % 100_000.0) / div).toInt()
        val n = floor((p.northing % 100_000.0) / div).toInt()
        return "%d%s %s%s %0${d}d %0${d}d".format(zone, band, col, row, e, n)
    }

    fun formatDecimal(lat: Double, lon: Double): String = "%.6f, %.6f".format(lat, lon)

    /** Degrees / decimal minutes — the format most handheld GPS units default to. */
    fun formatDegMin(lat: Double, lon: Double): String {
        fun part(v: Double, pos: String, neg: String): String {
            val h = if (v >= 0) pos else neg
            val av = abs(v)
            val d = floor(av).toInt()
            return "%s %d° %.3f'".format(h, d, (av - d) * 60.0)
        }
        return "${part(lat, "N", "S")}  ${part(lon, "E", "W")}"
    }

    fun formatDegMinSec(lat: Double, lon: Double): String {
        fun part(v: Double, pos: String, neg: String): String {
            val h = if (v >= 0) pos else neg
            val av = abs(v)
            val d = floor(av).toInt()
            val mFull = (av - d) * 60.0
            val m = floor(mFull).toInt()
            return "%s %d° %d' %.1f\"".format(h, d, m, (mFull - m) * 60.0)
        }
        return "${part(lat, "N", "S")}  ${part(lon, "E", "W")}"
    }

    // ------------------------------------------------------------------ internals

    /** Standard Transverse Mercator forward series (Snyder), accurate well inside a zone. */
    private fun project(lat: Double, lon: Double, p: TmParams): Projected {
        val phi = Math.toRadians(lat)
        val lambda = Math.toRadians(lon)
        val lambda0 = Math.toRadians(p.lon0)

        val sinPhi = sin(phi)
        val cosPhi = cos(phi)
        val tanPhi = tan(phi)

        val n = p.a / sqrt(1 - p.e2 * sinPhi * sinPhi)
        val t = tanPhi * tanPhi
        val c = p.ep2 * cosPhi * cosPhi
        val aa = (lambda - lambda0) * cosPhi

        val e2 = p.e2
        val m = p.a * (
            (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256) * phi -
            (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2 * e2 * e2 / 1024) * sin(2 * phi) +
            (15 * e2 * e2 / 256 + 45 * e2 * e2 * e2 / 1024) * sin(4 * phi) -
            (35 * e2 * e2 * e2 / 3072) * sin(6 * phi)
        )

        val a2 = aa * aa
        val easting = p.falseEasting + p.k0 * n * (
            aa + (1 - t + c) * a2 * aa / 6.0 +
            (5 - 18 * t + t * t + 72 * c - 58 * p.ep2) * a2 * a2 * aa / 120.0
        )
        val northing = p.falseNorthing + p.k0 * (
            m + n * tanPhi * (
                a2 / 2.0 +
                (5 - t + 9 * c + 4 * c * c) * a2 * a2 / 24.0 +
                (61 - 58 * t + t * t + 600 * c - 330 * p.ep2) * a2 * a2 * a2 / 720.0
            )
        )
        return Projected(easting, northing)
    }

    /**
     * 7-parameter Helmert from WGS84/ETRS89 to the Bessel 1841 datum used by D48.
     * Geodetic -> geocentric -> rotate/scale/translate -> geodetic.
     */
    private fun wgs84ToBessel(lat: Double, lon: Double): Pair<Double, Double> {
        val dx = 409.545; val dy = 72.164; val dz = 486.872
        val rx = Math.toRadians(-3.085957 / 3600.0)
        val ry = Math.toRadians(-5.469110 / 3600.0)
        val rz = Math.toRadians(11.020289 / 3600.0)
        val s = 1.0 + 17.919665e-6

        val aW = 6378137.0
        val fW = 1 / 298.257222101
        val e2W = 2 * fW - fW * fW

        val phi = Math.toRadians(lat)
        val lam = Math.toRadians(lon)
        val nW = aW / sqrt(1 - e2W * sin(phi) * sin(phi))
        val x = nW * cos(phi) * cos(lam)
        val y = nW * cos(phi) * sin(lam)
        val z = nW * (1 - e2W) * sin(phi)

        val xb = dx + s * (x + rz * y - ry * z)
        val yb = dy + s * (-rz * x + y + rx * z)
        val zb = dz + s * (ry * x - rx * y + z)

        val aB = D48.a
        val e2B = D48.e2
        val p = sqrt(xb * xb + yb * yb)
        var phiB = kotlin.math.atan2(zb, p * (1 - e2B))
        // Converges to well under a millimetre in a handful of passes at these latitudes.
        repeat(6) {
            val nB = aB / sqrt(1 - e2B * sin(phiB) * sin(phiB))
            phiB = kotlin.math.atan2(zb + e2B * nB * sin(phiB), p)
        }
        return Pair(Math.toDegrees(phiB), Math.toDegrees(kotlin.math.atan2(yb, xb)))
    }

    private fun latitudeBand(lat: Double): Char? {
        if (lat < -80 || lat > 84) return null
        val bands = "CDEFGHJKLMNPQRSTUVWXX"
        return bands[floor((lat + 80) / 8.0).toInt().coerceIn(0, 20)]
    }
}
