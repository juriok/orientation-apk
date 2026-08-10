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
 * GPX 1.1 read/write, so waypoints move between this app and Garmin units, Locus,
 * OruxMaps and the rest of the ecosystem.
 */
object GpxIo {

    private const val NS = "http://www.topografix.com/GPX/1/1"

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

    /**
     * Reads waypoints from a GPX file.
     *
     * Namespace awareness is switched off deliberately: plenty of real-world GPX files in the
     * wild declare the wrong namespace or none at all, and rejecting those would be pedantic
     * when the structure is unambiguous.
     */
    fun readWaypoints(input: InputStream): List<Waypoint> {
        val result = mutableListOf<Waypoint>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var lat = 0.0; var lon = 0.0
        var name = ""; var desc = ""; var ele: Double? = null
        var inWpt = false
        var text = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (parser.name.lowercase()) {
                        "wpt" -> {
                            inWpt = true
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            name = ""; desc = ""; ele = null
                        }
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    when (parser.name.lowercase()) {
                        "name" -> if (inWpt) name = text.toString().trim()
                        "desc" -> if (inWpt) desc = text.toString().trim()
                        "ele" -> if (inWpt) ele = text.toString().trim().toDoubleOrNull()
                        "wpt" -> {
                            if (inWpt && (lat != 0.0 || lon != 0.0)) {
                                result.add(
                                    Waypoint(
                                        name = name.ifBlank { "Uvožena točka" },
                                        lat = lat, lon = lon, elevation = ele, note = desc
                                    )
                                )
                            }
                            inWpt = false
                        }
                    }
                    text = StringBuilder()
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
