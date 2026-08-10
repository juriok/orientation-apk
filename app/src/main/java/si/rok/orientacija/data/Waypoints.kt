package si.rok.orientacija.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Waypoint(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val lat: Double,
    val lon: Double,
    var elevation: Double? = null,
    var note: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("lat", lat); put("lon", lon)
        elevation?.let { put("elevation", it) }
        put("note", note); put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = Waypoint(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", "Točka"),
            lat = o.getDouble("lat"),
            lon = o.getDouble("lon"),
            elevation = if (o.has("elevation")) o.getDouble("elevation") else null,
            note = o.optString("note", ""),
            createdAt = o.optLong("createdAt", System.currentTimeMillis())
        )
    }
}

class WaypointStore(private val context: Context) {

    private val file: File get() = File(context.filesDir, "waypoints.json")

    fun load(): MutableList<Waypoint> {
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            MutableList(arr.length()) { Waypoint.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(list: List<Waypoint>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString())
    }

    fun add(w: Waypoint) = save(load().also { it.add(w) })

    fun delete(id: String) = save(load().filter { it.id != id })

    fun update(w: Waypoint) {
        val all = load()
        val i = all.indexOfFirst { it.id == w.id }
        if (i >= 0) { all[i] = w; save(all) }
    }
}
