package si.rok.orientacija.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * GPX 1.1 read/write, so waypoints, tracks and routes move between this app and Garmin
 * units, Locus, OruxMaps and the rest of the ecosystem.
 */
object GpxIo {

    private const val NS = "http://www.topografix.com/GPX/1/1"

    /**
     * Everything one GPX file holds, kept apart because the three mean different things
     * here: points become waypoints, tracks become recorded sledi, and a route — an
     * ordered list of places to visit — is exactly a course.
     */
    class Contents(
        val waypoints: List<Waypoint>,
        val tracks: List<Track>,
        val routes: List<Course>
    ) {
        val isEmpty: Boolean get() = waypoints.isEmpty() && tracks.isEmpty() && routes.isEmpty()
    }

    private val iso: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    fun writeWaypoints(out: OutputStream, waypoints: List<Waypoint>) {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="Orientacija" xmlns="$NS">""").append('\n')
        sb.append("  <metadata><time>").append(iso.format(Date())).append("</time></metadata>\n")
        for (w in waypoints) {
            sb.append(String.format(Locale.US, "  <wpt lat=\"%.7f\" lon=\"%.7f\">\n", w.lat, w.lon))
            w.elevation?.let { sb.append(String.format(Locale.US, "    <ele>%.1f</ele>\n", it)) }
            sb.append("    <time>").append(iso.format(Date(w.createdAt))).append("</time>\n")
            sb.append("    <name>").append(escape(w.name)).append("</name>\n")
            if (w.note.isNotBlank()) sb.append("    <desc>").append(escape(w.note)).append("</desc>\n")
            sb.append("  </wpt>\n")
        }
        sb.append("</gpx>\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Writes recorded tracks as GPX `<trk>` segments, readable by Garmin, Locus and the rest. */
    fun writeTracks(out: OutputStream, tracks: List<Track>) {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="Orientacija" xmlns="$NS">""").append('\n')
        sb.append("  <metadata><time>").append(iso.format(Date())).append("</time></metadata>\n")
        for (t in tracks) {
            sb.append("  <trk>\n    <name>").append(escape(t.name)).append("</name>\n")
            sb.append("    <trkseg>\n")
            for (p in t.points) {
                sb.append(String.format(Locale.US, "      <trkpt lat=\"%.7f\" lon=\"%.7f\">\n", p.lat, p.lon))
                p.elevation?.let { sb.append(String.format(Locale.US, "        <ele>%.1f</ele>\n", it)) }
                if (p.time > 0) sb.append("        <time>").append(iso.format(Date(p.time))).append("</time>\n")
                sb.append("      </trkpt>\n")
            }
            sb.append("    </trkseg>\n  </trk>\n")
        }
        sb.append("</gpx>\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    fun readWaypoints(input: InputStream): List<Waypoint> = read(input).waypoints

    /**
     * Reads a whole GPX file in one pass: waypoints, tracks and routes.
     *
     * Namespace awareness is switched off deliberately: plenty of real-world GPX files in the
     * wild declare the wrong namespace or none at all, and rejecting those would be pedantic
     * when the structure is unambiguous. Element names are matched on their local part for
     * the same reason, since some writers prefix everything.
     *
     * Elements are read by context rather than by position, because `<name>` appears at four
     * different levels and the innermost open container is the one it belongs to.
     */
    fun read(input: InputStream): Contents {
        val waypoints = mutableListOf<Waypoint>()
        val tracks = mutableListOf<Track>()
        val routes = mutableListOf<Course>()

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var lat = 0.0; var lon = 0.0
        var name = ""; var desc = ""; var ele: Double? = null; var time = 0L

        var inWpt = false; var inTrkpt = false; var inRtept = false
        // Depth inside <extensions>. Everything in there belongs to whoever wrote the file
        // — heart rate, display colour, Garmin's own naming — and some of it uses the same
        // element names we care about, so it is skipped wholesale rather than half-read.
        var extensions = 0
        var trackName = ""; var routeName = ""
        var trackPoints = mutableListOf<TrackPoint>()
        var routePoints = mutableListOf<Control>()
        var inTrk = false; var inRte = false
        var text = StringBuilder()

        fun startPoint() {
            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
            name = ""; desc = ""; ele = null; time = 0L
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    if (extensions > 0 || local(parser.name) == "extensions") {
                        extensions++
                        event = parser.next()
                        continue
                    }
                    when (local(parser.name)) {
                        "wpt" -> { inWpt = true; startPoint() }
                        "trk" -> {
                            inTrk = true; trackName = ""
                            trackPoints = mutableListOf()
                        }
                        "trkpt" -> { inTrkpt = true; startPoint() }
                        "rte" -> {
                            inRte = true; routeName = ""
                            routePoints = mutableListOf()
                        }
                        "rtept" -> { inRtept = true; startPoint() }
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (extensions > 0) {
                        extensions--
                        text = StringBuilder()
                        event = parser.next()
                        continue
                    }
                    val value = text.toString().trim()
                    when (local(parser.name)) {
                        // The innermost open container owns the element, so a track's own
                        // name is never overwritten by the name of a point inside it.
                        "name" -> when {
                            inWpt || inTrkpt || inRtept -> name = value
                            inRte -> routeName = value
                            inTrk -> trackName = value
                        }
                        "desc" -> if (inWpt) desc = value
                        "ele" -> if (inWpt || inTrkpt || inRtept) ele = value.toDoubleOrNull()
                        "time" -> if (inTrkpt) time = parseTime(value)

                        "wpt" -> {
                            if (inWpt && hasPosition(lat, lon)) {
                                waypoints.add(
                                    Waypoint(
                                        name = name.ifBlank { "Uvožena točka ${waypoints.size + 1}" },
                                        lat = lat, lon = lon, elevation = ele, note = desc
                                    )
                                )
                            }
                            inWpt = false
                        }
                        "trkpt" -> {
                            if (inTrkpt && hasPosition(lat, lon)) {
                                // Accuracy is a property of a live fix, not of a file, so an
                                // imported point carries none rather than a made-up figure.
                                trackPoints.add(TrackPoint(lat, lon, ele, time, 0f))
                            }
                            inTrkpt = false
                        }
                        "rtept" -> {
                            if (inRtept && hasPosition(lat, lon)) {
                                routePoints.add(
                                    Control(
                                        name = name.ifBlank { "${routePoints.size + 1}. KT" },
                                        lat = lat, lon = lon
                                    )
                                )
                            }
                            inRtept = false
                        }
                        // Segments within one track are joined: they mark pauses in
                        // recording, and the app models a track as a single line.
                        "trk" -> {
                            if (inTrk && trackPoints.size >= 2) {
                                val stamps = trackPoints.map { it.time }.filter { it > 0 }
                                tracks.add(
                                    Track(
                                        name = trackName.ifBlank { "Uvožena sled ${tracks.size + 1}" },
                                        points = trackPoints,
                                        startedAt = stamps.minOrNull() ?: System.currentTimeMillis(),
                                        endedAt = stamps.maxOrNull() ?: 0L
                                    )
                                )
                            }
                            inTrk = false
                        }
                        "rte" -> {
                            if (inRte && routePoints.size >= 2) {
                                routes.add(
                                    Course(
                                        name = routeName.ifBlank { "Uvožena proga ${routes.size + 1}" },
                                        controls = routePoints
                                    )
                                )
                            }
                            inRte = false
                        }
                    }
                    text = StringBuilder()
                }
            }
            event = parser.next()
        }
        return Contents(waypoints, tracks, routes)
    }

    /** Local part of a possibly prefixed element name, lowercased. */
    private fun local(tag: String): String = tag.substringAfterLast(':').lowercase()

    /** Null Island is very nearly always a writer that omitted the position, not a real fix. */
    private fun hasPosition(lat: Double, lon: Double): Boolean = lat != 0.0 || lon != 0.0

    /**
     * GPX timestamps are ISO 8601 in UTC, but writers vary: some add fractional seconds,
     * some use a numeric offset instead of Z. A time that cannot be read is not worth
     * failing an import over — the geometry is what matters — so it comes back as 0.
     */
    private fun parseTime(value: String): Long {
        if (value.isBlank()) return 0L
        return try {
            java.time.Instant.parse(value).toEpochMilli()
        } catch (e: Exception) {
            try {
                java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                0L
            }
        }
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
