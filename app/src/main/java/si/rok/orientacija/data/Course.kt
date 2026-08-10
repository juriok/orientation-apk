package si.rok.orientacija.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import si.rok.orientacija.geo.GeoMath
import java.io.File
import java.util.UUID

data class Control(val name: String, val lat: Double, val lon: Double) {
    fun point() = GeoPoint(lat, lon)
}

class Course(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val controls: MutableList<Control> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis()
) {
    /** Straight-line length through all controls in order — the theoretical minimum. */
    fun lengthMetres(): Double {
        var total = 0.0
        for (i in 1 until controls.size) {
            total += GeoMath.distance(controls[i - 1].point(), controls[i].point())
        }
        return total
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
        put("controls", JSONArray().apply {
            controls.forEach {
                put(JSONObject().apply {
                    put("name", it.name); put("lat", it.lat); put("lon", it.lon)
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Course {
            val list = mutableListOf<Control>()
            o.optJSONArray("controls")?.let { a ->
                for (i in 0 until a.length()) {
                    val c = a.getJSONObject(i)
                    list.add(Control(c.optString("name", "KT"), c.getDouble("lat"), c.getDouble("lon")))
                }
            }
            return Course(
                id = o.getString("id"),
                name = o.optString("name", "Proga"),
                controls = list,
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}

class CourseStore(private val context: Context) {

    private val file: File get() = File(context.filesDir, "courses.json")

    fun load(): MutableList<Course> {
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            MutableList(arr.length()) { Course.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(list: List<Course>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString())
    }

    fun upsert(course: Course) {
        val all = load()
        val i = all.indexOfFirst { it.id == course.id }
        if (i >= 0) all[i] = course else all.add(course)
        save(all)
    }

    fun delete(id: String) = save(load().filter { it.id != id })
}

/**
 * Live state while running a course: which control is next, and the split times.
 *
 * Held process-wide alongside the track recorder so a run survives the activity being
 * recreated on rotation or a trip to another screen.
 */
object CourseRunner {

    var course: Course? = null
        private set
    var index: Int = 0
        private set
    var startedAt: Long = 0L
        private set

    /** Elapsed milliseconds at the moment each control was reached. */
    val splits = mutableListOf<Long>()

    /**
     * How close counts as punched. An orienteering control circle is about 30 m on the
     * ground at common scales, and a phone fix is good to a few metres under trees, so 25 m
     * registers reliably without firing while you are still running past the previous one.
     */
    var punchRadiusMetres: Double = 25.0

    val isRunning: Boolean get() = course != null
    val isFinished: Boolean get() = course?.let { index >= it.controls.size } ?: false
    val current: Control? get() = course?.controls?.getOrNull(index)

    fun start(c: Course) {
        course = c
        index = 0
        startedAt = System.currentTimeMillis()
        splits.clear()
    }

    fun stop() {
        course = null
        index = 0
        splits.clear()
    }

    fun elapsedMillis(): Long = if (startedAt == 0L) 0 else System.currentTimeMillis() - startedAt

    /** Advances if [here] is inside the punch radius. Returns the control just reached. */
    fun checkPunch(here: GeoPoint): Control? {
        val target = current ?: return null
        if (GeoMath.distance(here, target.point()) > punchRadiusMetres) return null
        splits.add(elapsedMillis())
        index++
        return target
    }

    fun advanceManually(): Control? {
        val target = current ?: return null
        splits.add(elapsedMillis())
        index++
        return target
    }

    fun formatElapsed(ms: Long = elapsedMillis()): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }
}
