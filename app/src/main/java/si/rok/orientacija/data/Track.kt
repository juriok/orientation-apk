package si.rok.orientacija.data

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val elevation: Double?,
    val time: Long,
    val accuracy: Float
)

class Track(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val points: MutableList<TrackPoint> = mutableListOf(),
    val startedAt: Long = System.currentTimeMillis(),
    var endedAt: Long = 0L
) {

    /**
     * Cumulative ground distance in metres.
     *
     * Points closer together than the noise floor are skipped: a phone sitting still on a
     * rock still reports jitter of a few metres, and summing that unfiltered inflates a
     * stationary hour into a kilometre of "distance".
     */
    fun distanceMetres(): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            val d = haversine(points[i - 1], points[i])
            if (d >= MIN_STEP_METRES) total += d
        }
        return total
    }

    fun durationMillis(): Long =
        (if (endedAt > 0) endedAt else System.currentTimeMillis()) - startedAt

    /**
     * Total climb in metres. GPS altitude is far noisier than position, so only rises above
     * a threshold count — otherwise standing still accumulates hundreds of phantom metres.
     */
    fun ascentMetres(): Double {
        var gain = 0.0
        var reference: Double? = null
        for (p in points) {
            val e = p.elevation ?: continue
            val r = reference
            if (r == null) { reference = e; continue }
            val delta = e - r
            if (abs(delta) >= ELEVATION_NOISE) {
                if (delta > 0) gain += delta
                reference = e
            }
        }
        return gain
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("startedAt", startedAt)
        put("endedAt", endedAt)
        put("points", JSONArray().apply {
            points.forEach { p ->
                put(JSONObject().apply {
                    put("lat", p.lat); put("lon", p.lon)
                    p.elevation?.let { put("ele", it) }
                    put("t", p.time); put("acc", p.accuracy.toDouble())
                })
            }
        })
    }

    companion object {
        private const val MIN_STEP_METRES = 2.0
        private const val ELEVATION_NOISE = 4.0

        fun fromJson(o: JSONObject): Track {
            val pts = mutableListOf<TrackPoint>()
            o.optJSONArray("points")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.getJSONObject(i)
                    pts.add(
                        TrackPoint(
                            lat = p.getDouble("lat"),
                            lon = p.getDouble("lon"),
                            elevation = if (p.has("ele")) p.getDouble("ele") else null,
                            time = p.optLong("t", 0L),
                            accuracy = p.optDouble("acc", 0.0).toFloat()
                        )
                    )
                }
            }
            return Track(
                id = o.getString("id"),
                name = o.optString("name", "Sled"),
                points = pts,
                startedAt = o.optLong("startedAt", System.currentTimeMillis()),
                endedAt = o.optLong("endedAt", 0L)
            )
        }

        fun haversine(a: TrackPoint, b: TrackPoint): Double {
            val r = FloatArray(1)
            Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, r)
            return r[0].toDouble()
        }
    }
}

class TrackStore(private val context: Context) {

    private val file: File get() = File(context.filesDir, "tracks.json")

    fun load(): MutableList<Track> {
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            MutableList(arr.length()) { Track.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(list: List<Track>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString())
    }

    fun upsert(track: Track) {
        val all = load()
        val i = all.indexOfFirst { it.id == track.id }
        if (i >= 0) all[i] = track else all.add(track)
        save(all)
    }

    fun delete(id: String) = save(load().filter { it.id != id })
}
