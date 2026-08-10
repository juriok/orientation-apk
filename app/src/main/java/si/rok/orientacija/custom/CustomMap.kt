package si.rok.orientacija.custom

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import si.rok.orientacija.geo.GeoMath
import si.rok.orientacija.geo.Transform2D
import java.io.File
import java.util.UUID

/**
 * One control point: a spot the user identified both on their own map image and on the ground.
 * Stored in lat/lon so a calibration stays valid if the reference base map ever changes.
 */
data class ControlPoint(
    val imgX: Double,
    val imgY: Double,
    val lat: Double,
    val lon: Double
)

/**
 * A user-supplied map (imported file or a photo of a paper sheet) plus the transform that
 * ties its pixels to the real world.
 *
 * [matrix] maps image pixels to Web Mercator metres. Errors are in metres on the ground,
 * which is the only unit that tells the user whether the calibration is usable.
 */
class CustomMap(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val imagePath: String,
    var matrix: DoubleArray = Transform2D.identity(),
    var modelName: String = "",
    var rmsError: Double = -1.0,
    var maxError: Double = -1.0,
    /** Cross-validated error in metres; -1 when there are too few points to measure it. */
    var cvError: Double = -1.0,
    /** Image pixel dimensions, needed to check the transform stays sane across the sheet. */
    var imageWidth: Int = 0,
    var imageHeight: Int = 0,
    var controlPoints: MutableList<ControlPoint> = mutableListOf(),
    var opacity: Int = 255,
    var calibrated: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {

    /** Recomputes [matrix] from the current control points. Returns the fit, or null. */
    fun recalibrate(): Transform2D.Fit? {
        if (controlPoints.size < 2) return null
        val src = controlPoints.map { doubleArrayOf(it.imgX, it.imgY) }
        val dst = controlPoints.map { GeoMath.toMercator(it.lat, it.lon) }
        val fit = Transform2D.fit(src, dst, domain = domain(), crossValidate = true) ?: return null
        matrix = fit.matrix
        modelName = fit.model.label
        // With no more points than the model has freedom, the fit passes through every point
        // exactly and the residual is zero by construction — it measures nothing. Recording
        // it as "0 m" would advertise a perfect calibration that has never been checked.
        rmsError = if (fit.exactlyDetermined) -1.0 else fit.rmsError
        maxError = if (fit.exactlyDetermined) -1.0 else fit.maxError
        cvError = fit.cvError
        calibrated = true
        return fit
    }

    /** Image extent for the sanity check, or null if the dimensions were never recorded. */
    fun domain(): DoubleArray? =
        if (imageWidth > 0 && imageHeight > 0)
            doubleArrayOf(0.0, 0.0, imageWidth.toDouble(), imageHeight.toDouble())
        else null

    /** The figure to quote to the user: cross-validated if available, else in-sample. */
    fun bestErrorEstimate(): Double = if (cvError >= 0) cvError else rmsError

    /**
     * A plain-language verdict on the calibration, since an RMS in metres means little
     * without a sense of what is good. Thresholds are chosen for foot navigation: under
     * 10 m you cannot tell the difference on the ground; past ~50 m you will walk to the
     * wrong side of a feature.
     */
    fun qualityLabel(): String {
        if (!calibrated) return "ni umerjeno"
        val e = bestErrorEstimate()
        if (e < 0) return "natančnost nepreverjena"
        return when {
            e < 10 -> "odlično (±%.0f m)".format(e)
            e < 25 -> "dobro (±%.0f m)".format(e)
            e < 50 -> "zadovoljivo (±%.0f m)".format(e)
            else -> "slabo (±%.0f m)".format(e)
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("imagePath", imagePath)
        put("matrix", JSONArray().apply { matrix.forEach { put(it) } })
        put("modelName", modelName)
        put("rmsError", rmsError)
        put("maxError", maxError)
        put("cvError", cvError)
        put("imageWidth", imageWidth)
        put("imageHeight", imageHeight)
        put("opacity", opacity)
        put("calibrated", calibrated)
        put("createdAt", createdAt)
        put("controlPoints", JSONArray().apply {
            controlPoints.forEach {
                put(JSONObject().apply {
                    put("imgX", it.imgX); put("imgY", it.imgY)
                    put("lat", it.lat); put("lon", it.lon)
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): CustomMap {
            val m = DoubleArray(9)
            val arr = o.optJSONArray("matrix")
            if (arr != null && arr.length() == 9) for (i in 0..8) m[i] = arr.getDouble(i)
            else Transform2D.identity().copyInto(m)

            val cps = mutableListOf<ControlPoint>()
            o.optJSONArray("controlPoints")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.getJSONObject(i)
                    cps.add(ControlPoint(p.getDouble("imgX"), p.getDouble("imgY"), p.getDouble("lat"), p.getDouble("lon")))
                }
            }
            return CustomMap(
                id = o.getString("id"),
                name = o.getString("name"),
                imagePath = o.getString("imagePath"),
                matrix = m,
                modelName = o.optString("modelName", ""),
                rmsError = o.optDouble("rmsError", -1.0),
                maxError = o.optDouble("maxError", -1.0),
                cvError = o.optDouble("cvError", -1.0),
                imageWidth = o.optInt("imageWidth", 0),
                imageHeight = o.optInt("imageHeight", 0),
                controlPoints = cps,
                opacity = o.optInt("opacity", 255),
                calibrated = o.optBoolean("calibrated", false),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}

/** Flat-file store. The dataset is a handful of maps, so JSON beats pulling in a database. */
class CustomMapStore(private val context: Context) {

    private val indexFile: File get() = File(context.filesDir, "custom_maps.json")
    val imageDir: File get() = File(context.filesDir, "maps").apply { mkdirs() }

    fun load(): MutableList<CustomMap> {
        if (!indexFile.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(indexFile.readText())
            MutableList(arr.length()) { CustomMap.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(maps: List<CustomMap>) {
        val arr = JSONArray()
        maps.forEach { arr.put(it.toJson()) }
        indexFile.writeText(arr.toString())
    }

    fun upsert(map: CustomMap) {
        val all = load()
        val i = all.indexOfFirst { it.id == map.id }
        if (i >= 0) all[i] = map else all.add(map)
        save(all)
    }

    fun delete(map: CustomMap) {
        save(load().filter { it.id != map.id })
        runCatching { File(map.imagePath).delete() }
    }

    fun newImageFile(ext: String = "jpg") = File(imageDir, "${UUID.randomUUID()}.$ext")
}
