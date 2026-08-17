package si.rok.orientacija.map

import android.content.Context
import org.json.JSONObject
import org.osmdroid.util.BoundingBox
import si.rok.orientacija.geo.CoordinateSystems
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.floor

/**
 * Analytical shading from Ciklično lasersko skeniranje Slovenije — the 2023-25 national
 * lidar re-scan published at clss.si.
 *
 * The new scan has no map-tile service: GURS publishes it as one GeoTIFF per square
 * kilometre, on the D96/TM grid, and the portal's own viewer is a closed application. So
 * this reads the official sheets directly and turns them into map tiles on the device.
 *
 * Two things make that affordable rather than absurd:
 *
 *  * Every sheet carries a reduced-resolution overview as a second image — 1000x1000 at
 *    1 m per pixel, which is finer than any zoom this app draws relief at, and a quarter
 *    the size of the 0.5 m original.
 *  * The overview is stored uncompressed with one row per strip, laid out contiguously at
 *    the end of the file, so a single HTTP range request fetches exactly it: one megabyte
 *    per square kilometre, once, and then it is on disk for good.
 *
 * Sheets are kept in filesDir rather than the cache directory on purpose — relief you
 * deliberately downloaded for a walk must not evaporate because the system wanted space
 * back the night before.
 */
object ClssSheets {

    /** Side of one CLSS sheet, in metres of D96/TM. */
    const val SHEET_METRES = 1000

    /** Ground sampling of the overview image, in metres. */
    const val SHEET_RESOLUTION = 1.0

    /** Pixels per side of the overview: SHEET_METRES / SHEET_RESOLUTION. */
    const val SHEET_PIXELS = 1000

    private const val INDEX_ENDPOINT = "https://lift.clss.si/geoserver/wfs"
    private const val INDEX_LAYER = "fmp_clss:tileindex_clss_zdruzeno"
    private const val ASSETS_BASE = "https://assets.flycom.si"
    private const val USER_AGENT = "si.rok.orientacija"

    private const val CONNECT_TIMEOUT = 20_000
    private const val READ_TIMEOUT = 60_000

    /** Sheets held decoded in memory. Each is a megabyte, so this is deliberately small. */
    private const val MEMORY_SHEETS = 6

    private val memory = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) =
            size > MEMORY_SHEETS
    }

    /**
     * One lock per sheet. Several tile threads routinely want the same sheet at once —
     * a sheet spans many tiles — and without this they would each fetch their own copy.
     */
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * Sheet key to asset path; an empty path records "asked, and there is no coverage".
     *
     * Concurrent because several tile threads read it while another is adding to it, and a
     * plain map read during a resize is exactly the kind of fault that shows up once a
     * fortnight on someone else's phone.
     */
    private var paths: ConcurrentHashMap<String, String>? = null

    fun key(eastKm: Int, northKm: Int): String = "${eastKm}_$northKm"

    fun eastKmOf(easting: Double): Int = floor(easting / SHEET_METRES).toInt()
    fun northKmOf(northing: Double): Int = floor(northing / SHEET_METRES).toInt()

    /**
     * The shading for one sheet as raw 8-bit grey, row-major from the north-west corner,
     * or null when the sheet is not held and could not be fetched.
     *
     * Blocking, and meant to be: it is called from tile worker threads, which exist
     * precisely so that work like this stays off the map's drawing thread.
     */
    fun sheet(context: Context, eastKm: Int, northKm: Int, allowFetch: Boolean): ByteArray? {
        val k = key(eastKm, northKm)
        synchronized(memory) { memory[k] }?.let { return it }

        // Locking per sheet rather than globally: fetching one sheet must not stall a tile
        // that only needs a different one.
        synchronized(locks.getOrPut(k) { Any() }) {
            synchronized(memory) { memory[k] }?.let { return it }

            readFromDisk(context, k)?.let { remember(k, it); return it }
            if (!allowFetch) return null
            if (pathFor(context, k) == "") return null   // known to have no coverage

            val data = fetchSheet(context, k) ?: return null
            writeToDisk(context, k, data)
            remember(k, data)
            return data
        }
    }

    /** True when the sheet is already on the device, so drawing it costs nothing. */
    fun isHeld(context: Context, eastKm: Int, northKm: Int): Boolean {
        val k = key(eastKm, northKm)
        if (synchronized(memory) { memory.containsKey(k) }) return true
        return sheetFile(context, k).exists()
    }

    /** Every sheet whose ground overlaps [box], for pre-downloading an area. */
    fun sheetsIn(box: BoundingBox): List<Pair<Int, Int>> {
        // Corners are not enough on their own: the D96/TM grid is not aligned to lat/lon,
        // so the extremes in projected metres can fall on the edges rather than the corners.
        var minE = Double.MAX_VALUE; var maxE = -Double.MAX_VALUE
        var minN = Double.MAX_VALUE; var maxN = -Double.MAX_VALUE
        val steps = 8
        for (i in 0..steps) {
            val lat = box.latSouth + (box.latNorth - box.latSouth) * i / steps
            for (j in 0..steps) {
                val lon = box.lonWest + (box.lonEast - box.lonWest) * j / steps
                val p = CoordinateSystems.toD96TM(lat, lon)
                minE = minOf(minE, p.easting); maxE = maxOf(maxE, p.easting)
                minN = minOf(minN, p.northing); maxN = maxOf(maxN, p.northing)
            }
        }
        val out = mutableListOf<Pair<Int, Int>>()
        for (e in eastKmOf(minE)..eastKmOf(maxE)) {
            for (n in northKmOf(minN)..northKmOf(maxN)) out.add(e to n)
        }
        return out
    }

    /** Bytes of relief held on disk, for the offline manager. */
    fun storedBytes(context: Context): Long =
        sheetDir(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun clearStored(context: Context) {
        sheetDir(context).listFiles()?.forEach { it.delete() }
        synchronized(memory) { memory.clear() }
    }

    // ------------------------------------------------------------------ disk

    private fun sheetDir(context: Context): File =
        File(context.filesDir, "clss").apply { mkdirs() }

    private fun sheetFile(context: Context, key: String) = File(sheetDir(context), "$key.gz")

    private fun remember(key: String, data: ByteArray) {
        synchronized(memory) { memory[key] = data }
    }

    /**
     * Shading compresses to roughly half its size, which is worth the milliseconds: a
     * region worth walking is a few hundred sheets, and that is the difference between
     * 300 MB and 150 MB of a phone's storage.
     */
    private fun readFromDisk(context: Context, key: String): ByteArray? {
        val f = sheetFile(context, key)
        if (!f.exists()) return null
        return try {
            GZIPInputStream(f.inputStream().buffered()).use { it.readBytes() }
                .takeIf { it.size == SHEET_PIXELS * SHEET_PIXELS }
        } catch (e: Exception) {
            // A sheet interrupted mid-write is worse than no sheet: drop it and refetch.
            f.delete()
            null
        }
    }

    private fun writeToDisk(context: Context, key: String, data: ByteArray) {
        val target = sheetFile(context, key)
        val tmp = File(target.parentFile, "${key}.part")
        try {
            GZIPOutputStream(tmp.outputStream().buffered()).use { it.write(data) }
            // Rename only once the bytes are down, so a kill mid-download cannot leave a
            // truncated sheet looking like a complete one.
            if (!tmp.renameTo(target)) tmp.delete()
        } catch (e: Exception) {
            tmp.delete()
        }
    }

    // ------------------------------------------------------------------ index

    private fun indexFile(context: Context) = File(sheetDir(context), "index.json")

    @Synchronized
    private fun loadPaths(context: Context): ConcurrentHashMap<String, String> {
        paths?.let { return it }
        val map = ConcurrentHashMap<String, String>()
        val f = indexFile(context)
        if (f.exists()) {
            try {
                val o = JSONObject(f.readText())
                o.keys().forEach { map[it] = o.optString(it, "") }
            } catch (e: Exception) {
                f.delete()
            }
        }
        paths = map
        return map
    }

    private fun rememberPath(context: Context, key: String, path: String) {
        val map = loadPaths(context)
        map[key] = path
        // Writing the file is serialised even though the map is not, so two threads
        // finishing at once cannot interleave into a half-written index.
        synchronized(this) {
            try {
                indexFile(context).writeText(JSONObject(map.toMap<String, Any>()).toString())
            } catch (e: Exception) {
                // The index is only ever an optimisation; losing it costs one lookup.
            }
        }
    }

    /**
     * Where a sheet lives, from the CLSS coverage index. Empty string means the index was
     * asked and answered that nothing covers this square, which is worth remembering:
     * most of what a map view touches near the border is outside the scan.
     */
    private fun pathFor(context: Context, key: String): String? {
        loadPaths(context)[key]?.let { return it }
        val fetched = queryIndex(key) ?: return null
        rememberPath(context, key, fetched)
        return fetched
    }

    private fun queryIndex(key: String): String? {
        val filter = URLEncoder.encode("ti_name='$key'", "UTF-8")
        val url = "$INDEX_ENDPOINT?service=WFS&version=2.0.0&request=GetFeature" +
            "&typeNames=${URLEncoder.encode(INDEX_LAYER, "UTF-8")}" +
            "&outputFormat=application/json&count=1&propertyName=path_pas&CQL_FILTER=$filter"
        val body = readText(url) ?: return null
        return try {
            val features = JSONObject(body).optJSONArray("features")
            if (features == null || features.length() == 0) {
                ""   // outside the scan, and now known to be
            } else {
                features.getJSONObject(0).optJSONObject("properties")
                    ?.optString("path_pas", "")?.takeIf { it.isNotBlank() } ?: ""
            }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ network

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = CONNECT_TIMEOUT
        c.readTimeout = READ_TIMEOUT
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.instanceFollowRedirects = true
        return c
    }

    private fun readText(url: String): String? = try {
        val c = open(url)
        try {
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /** Fetches [length] bytes from [from], or null on anything other than a partial reply. */
    private fun readRange(url: String, from: Long, length: Int): ByteArray? = try {
        val c = open(url)
        c.setRequestProperty("Range", "bytes=$from-${from + length - 1}")
        try {
            if (c.responseCode != HttpURLConnection.HTTP_PARTIAL) null
            else c.inputStream.use { input ->
                val buf = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(buf, read, length - read)
                    if (n < 0) break
                    read += n
                }
                if (read == length) buf else null
            }
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Pulls one sheet's overview.
     *
     * The file is read rather than assumed: eight bytes of header give the byte order and
     * where the directory starts, then the whole directory region comes down in one go and
     * the reduced-resolution image is located properly inside it. Hard-coding today's
     * layout would work until the day GURS rewrites the sheets with a different one.
     */
    private fun fetchSheet(context: Context, key: String): ByteArray? {
        val path = pathFor(context, key)
        if (path.isNullOrBlank()) return null
        val url = "$ASSETS_BASE/$path"

        val header = readRange(url, 0, 8) ?: return null
        val little = header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte()
        val big = header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte()
        if (!little && !big) return null
        if (readShort(header, 2, little) != 42) return null
        val firstIfd = readInt(header, 4, little)
        if (firstIfd <= 0 || firstIfd > MAX_TRAILER_START) return null

        // Everything from the first directory to the end of the file: both directories,
        // the palette and the overview pixels, which all live past the full-size image.
        val trailer = readTail(url, firstIfd) ?: return null

        return extractOverview(trailer, firstIfd, little)
    }

    /**
     * Reads from [from] to the end of the file, up to [MAX_TRAILER_BYTES].
     *
     * Open-ended rather than a fixed length on purpose: the tail is a little over a
     * megabyte and asking for a fixed span would either fall short or run past the end,
     * and a range that overruns still costs the whole download before it can be rejected.
     */
    private fun readTail(url: String, from: Long): ByteArray? = try {
        val c = open(url)
        c.setRequestProperty("Range", "bytes=$from-")
        try {
            if (c.responseCode != HttpURLConnection.HTTP_PARTIAL) null
            else c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(MAX_TRAILER_BYTES / 2)
                val chunk = ByteArray(64 * 1024)
                while (out.size() < MAX_TRAILER_BYTES) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    out.write(chunk, 0, n)
                }
                out.toByteArray().takeIf { it.isNotEmpty() }
            }
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Walks the directory chain for the reduced-resolution image and copies its rows out.
     *
     * Rows are addressed one strip at a time rather than as a single block: that is what
     * the format actually promises, and it costs nothing here since the strips are already
     * in the buffer.
     */
    private fun extractOverview(buf: ByteArray, bufStart: Long, little: Boolean): ByteArray? {
        var ifd = bufStart
        var guard = 0
        while (ifd > 0 && guard++ < 8) {
            val at = (ifd - bufStart).toInt()
            if (at < 0 || at + 2 > buf.size) return null
            val count = readShort(buf, at, little)
            val end = at + 2 + count * 12
            if (end + 4 > buf.size) return null

            var width = 0; var height = 0; var reduced = false
            var bits = 8; var compression = 1; var samples = 1
            var stripOffsetsAt = 0L; var stripOffsetsCount = 0; var stripOffsetsType = 0
            var rowsPerStrip = Int.MAX_VALUE

            for (i in 0 until count) {
                val e = at + 2 + i * 12
                val tag = readShort(buf, e, little)
                val type = readShort(buf, e + 2, little)
                val n = readInt(buf, e + 4, little)
                // A value short enough to fit is stored in the entry itself rather than
                // pointed at, which is why the small tags are read at a different width.
                val inline = if (type == TYPE_SHORT) readShort(buf, e + 8, little).toLong()
                             else readInt(buf, e + 8, little)
                when (tag) {
                    TAG_SUBFILE -> reduced = (inline.toInt() and 1) == 1
                    TAG_WIDTH -> width = inline.toInt()
                    TAG_HEIGHT -> height = inline.toInt()
                    TAG_BITS -> bits = inline.toInt()
                    TAG_COMPRESSION -> compression = inline.toInt()
                    TAG_SAMPLES -> samples = inline.toInt()
                    TAG_ROWS_PER_STRIP -> rowsPerStrip = inline.toInt()
                    TAG_STRIP_OFFSETS -> {
                        stripOffsetsAt = inline; stripOffsetsCount = n.toInt(); stripOffsetsType = type
                    }
                }
            }
            val next = readInt(buf, end, little)

            val usable = reduced && width == SHEET_PIXELS && height == SHEET_PIXELS &&
                bits == 8 && samples == 1 && compression == COMPRESSION_NONE &&
                rowsPerStrip == 1 && stripOffsetsCount == height
            if (usable) {
                return copyRows(buf, bufStart, stripOffsetsAt, stripOffsetsType, width, height, little)
            }
            ifd = next
        }
        return null
    }

    private fun copyRows(
        buf: ByteArray,
        bufStart: Long,
        offsetsAt: Long,
        offsetsType: Int,
        width: Int,
        height: Int,
        little: Boolean
    ): ByteArray? {
        val table = (offsetsAt - bufStart).toInt()
        val stride = if (offsetsType == TYPE_SHORT) 2 else 4
        if (table < 0 || table + height * stride > buf.size) return null

        val out = ByteArray(width * height)
        for (row in 0 until height) {
            val rowAt = if (stride == 2) readShort(buf, table + row * 2, little).toLong()
                        else readInt(buf, table + row * 4, little)
            val from = (rowAt - bufStart).toInt()
            if (from < 0 || from + width > buf.size) return null
            System.arraycopy(buf, from, out, row * width, width)
        }
        return out
    }

    // The palette these sheets carry is a plain grey ramp — entry i is i scaled to 16 bits —
    // so the stored bytes are already the grey values and no lookup is needed.

    private fun readShort(b: ByteArray, at: Int, little: Boolean): Int {
        val lo = b[at].toInt() and 0xFF
        val hi = b[at + 1].toInt() and 0xFF
        return if (little) (hi shl 8) or lo else (lo shl 8) or hi
    }

    private fun readInt(b: ByteArray, at: Int, little: Boolean): Long {
        val b0 = (b[at].toInt() and 0xFF).toLong()
        val b1 = (b[at + 1].toInt() and 0xFF).toLong()
        val b2 = (b[at + 2].toInt() and 0xFF).toLong()
        val b3 = (b[at + 3].toInt() and 0xFF).toLong()
        return if (little) (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
        else (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private const val TAG_SUBFILE = 254
    private const val TAG_WIDTH = 256
    private const val TAG_HEIGHT = 257
    private const val TAG_BITS = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_SAMPLES = 277
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TYPE_SHORT = 3
    private const val COMPRESSION_NONE = 1

    /** The overview, its palette and both directories, with room to spare. */
    private const val MAX_TRAILER_BYTES = 1_100_000
    /** Past this the file is not a CLSS sheet and is not worth reading. */
    private const val MAX_TRAILER_START = 64L * 1024 * 1024
}
