package si.rok.orientacija.map

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.BoundingBox
import java.io.File
import java.util.UUID

/**
 * A record of one offline download.
 *
 * osmdroid keeps every cached tile in a single database with no notion of which download it
 * came from, so deleting "the Pohorje area" is impossible without keeping our own note of
 * what was fetched. Storing the bounding box and zoom range lets a pack be removed later via
 * the cache manager's area clean.
 */
class OfflinePack(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val north: Double,
    val east: Double,
    val south: Double,
    val west: Double,
    val zoomMin: Int,
    val zoomMax: Int,
    val layerLabel: String,
    val tileCount: Int,
    val includesRelief: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun boundingBox() = BoundingBox(north, east, south, west)

    /** Rough footprint, using the same ~60 KB per tile the download estimate assumes. */
    fun approxBytes(): Long = tileCount.toLong() * 60L * 1024L * (if (includesRelief) 2 else 1)

    fun widthKm(): Double {
        val midLat = Math.toRadians((north + south) / 2)
        return (east - west) * 111.32 * Math.cos(midLat)
    }

    fun heightKm(): Double = (north - south) * 110.57

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name)
        put("north", north); put("east", east); put("south", south); put("west", west)
        put("zoomMin", zoomMin); put("zoomMax", zoomMax)
        put("layerLabel", layerLabel); put("tileCount", tileCount)
        put("includesRelief", includesRelief); put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = OfflinePack(
            id = o.getString("id"),
            name = o.optString("name", "Območje"),
            north = o.getDouble("north"), east = o.getDouble("east"),
            south = o.getDouble("south"), west = o.getDouble("west"),
            zoomMin = o.optInt("zoomMin", 9), zoomMax = o.optInt("zoomMax", 16),
            layerLabel = o.optString("layerLabel", ""),
            tileCount = o.optInt("tileCount", 0),
            includesRelief = o.optBoolean("includesRelief", false),
            createdAt = o.optLong("createdAt", System.currentTimeMillis())
        )
    }
}

class OfflinePackStore(private val context: Context) {

    private val file: File get() = File(context.filesDir, "offline_packs.json")

    fun load(): MutableList<OfflinePack> {
        if (!file.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(file.readText())
            MutableList(arr.length()) { OfflinePack.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(list: List<OfflinePack>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString())
    }

    fun add(pack: OfflinePack) = save(load().also { it.add(pack) })

    fun delete(id: String) = save(load().filter { it.id != id })
}
