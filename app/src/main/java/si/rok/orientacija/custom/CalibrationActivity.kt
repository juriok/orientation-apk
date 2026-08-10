package si.rok.orientacija.custom

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import si.rok.orientacija.R
import si.rok.orientacija.databinding.ActivityCalibrationBinding
import si.rok.orientacija.geo.GeoMath
import si.rok.orientacija.geo.Transform2D
import si.rok.orientacija.map.MapLayers
import si.rok.orientacija.util.BitmapUtils
import si.rok.orientacija.util.EdgeToEdge
import java.io.File

/**
 * Ties a user's map image to real-world coordinates by matching features between the image
 * and a reference map.
 *
 * The two-pane, control-point approach is used rather than dragging the image over the map
 * because a photograph of a paper sheet carries perspective distortion. Dragging can only
 * apply scale, rotation and translation, so a photo taken at even a modest angle will look
 * aligned at the edges while being badly wrong in the middle. Four or more matched points
 * let us solve the full projective transform that actually undoes the camera angle.
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var b: ActivityCalibrationBinding
    private lateinit var store: CustomMapStore
    private var customMap: CustomMap? = null
    private var bitmap: Bitmap? = null

    private val points = mutableListOf<ControlPoint>()
    private var previewOverlay: CalibratedMapOverlay? = null
    private var previewing = false
    private var baseIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        Configuration.getInstance().load(this, prefs)
        Configuration.getInstance().userAgentValue = packageName

        b = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(b.root)
        EdgeToEdge.apply(b.root, b.toolbar, b.buttonRow)
        b.toolbar.setNavigationOnClickListener { finish() }

        store = CustomMapStore(this)
        val id = intent.getStringExtra(EXTRA_MAP_ID)
        customMap = store.load().firstOrNull { it.id == id }
        val map = customMap
        if (map == null) {
            Toast.makeText(this, "Karta ni najdena.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        b.toolbar.title = map.name

        bitmap = BitmapUtils.loadScaled(File(map.imagePath))
        if (bitmap == null) {
            Toast.makeText(this, "Slike ni mogoče odpreti.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        b.imageView.bitmap = bitmap
        points.addAll(map.controlPoints)
        refreshImagePoints()

        setupMap()
        wireButtons()
        updateStats()
    }

    private fun setupMap() {
        b.map.setTileSource(MapLayers.ALL[baseIndex].source)
        b.map.setMultiTouchControls(true)
        b.map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        b.map.minZoomLevel = 6.0
        b.map.maxZoomLevel = 20.0

        // Start where the user already is if we have anything to go on, otherwise Slovenia.
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val start = points.lastOrNull()?.let { GeoPoint(it.lat, it.lon) }
            ?: GeoPoint(
                prefs.getFloat("last_lat", 46.12f).toDouble(),
                prefs.getFloat("last_lon", 14.82f).toDouble()
            )
        b.map.controller.setZoom(if (points.isEmpty()) 10.0 else 16.0)
        b.map.controller.setCenter(start)
    }

    private fun wireButtons() {
        b.imageView.onPointPicked = { _, _ ->
            b.txtStep.setText(R.string.cal_step_map)
        }

        b.btnAdd.setOnClickListener { addPair() }
        b.btnUndo.setOnClickListener { undo() }
        b.btnPreview.setOnClickListener { togglePreview() }
        b.btnSave.setOnClickListener { save() }

        b.btnBase.setOnClickListener {
            baseIndex = (baseIndex + 1) % MapLayers.ALL.size
            val layer = MapLayers.ALL[baseIndex]
            b.map.setTileSource(layer.source)
            b.btnBase.text = layer.label
        }

        b.seekOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                previewOverlay?.opacity = value
                b.map.invalidate()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // ------------------------------------------------------------------ control points

    private fun addPair() {
        val cursor = b.imageView.cursor
        if (cursor == null) {
            Toast.makeText(this, "Najprej tapnite točko na sliki.", Toast.LENGTH_SHORT).show()
            return
        }
        val centre = b.map.mapCenter
        points.add(ControlPoint(cursor.first, cursor.second, centre.latitude, centre.longitude))
        b.imageView.cursor = null
        refreshImagePoints()
        b.txtStep.setText(R.string.cal_step_image)
        updateStats()
        if (previewing) refreshPreview()
    }

    private fun undo() {
        if (points.isEmpty()) return
        points.removeAt(points.size - 1)
        refreshImagePoints()
        updateStats()
        if (previewing) refreshPreview()
    }

    private fun refreshImagePoints() {
        b.imageView.committedPoints = points.map { Pair(it.imgX, it.imgY) }
    }

    private fun currentFit(): Transform2D.Fit? {
        if (points.size < 2) return null
        val src = points.map { doubleArrayOf(it.imgX, it.imgY) }
        val dst = points.map { GeoMath.toMercator(it.lat, it.lon) }
        val bmp = bitmap
        val domain = if (bmp != null)
            doubleArrayOf(0.0, 0.0, bmp.width.toDouble(), bmp.height.toDouble()) else null
        // Cross-validation costs N refits of an 8x8 system — trivial at these point counts,
        // and it runs only when a point is added, not per frame.
        return Transform2D.fit(src, dst, domain = domain, crossValidate = true)
    }

    /**
     * Reports fit quality in metres on the ground, plus what the next point would buy.
     * A user cannot judge a calibration from a matrix, but they can judge "±18 m".
     */
    private fun updateStats() {
        val n = points.size
        val fit = currentFit()
        b.txtStats.text = when {
            n == 0 -> "Ni kontrolnih točk. Potrebujete vsaj 2, priporočeno 4 ali več."
            n == 1 -> "1 točka — dodajte še vsaj eno (samo premik, brez merila in zasuka)."
            fit == null -> "$n točk — točke so preblizu skupaj ali v isti liniji. Razporedite jih."
            else -> buildString {
                append("$n točk · model: ${fit.model.label}")
                // The quoted figure is cross-validated, not the in-sample residual. The
                // residual only says how well the transform reproduces the points it was
                // built from, which it can always do — at the model minimum it is exactly
                // zero regardless of how wrong the map is.
                val cv = fit.cvError
                if (cv >= 0) {
                    append(" · pričakovana napaka ±%.0f m".format(cv))
                    when {
                        cv > 50 -> append("\nVelika napaka — preverite ujemanje točk ali dodajte nove v vogalih.")
                        n < fit.model.minPoints + 3 ->
                            append("\nPri malo točkah je ocena pesimistična; dodajte še kakšno za zanesljivejšo številko.")
                        else -> append("\nRazporedite točke čim bolj po vseh vogalih karte.")
                    }
                } else {
                    append(" · natančnosti še ni mogoče oceniti")
                    append("\nDodajte še eno točko (skupaj ${fit.model.minPoints + 1}), da aplikacija izmeri napako.")
                }
            }
        }
    }

    // ------------------------------------------------------------------ preview

    private fun togglePreview() {
        previewing = !previewing
        if (previewing) {
            refreshPreview()
            b.seekOpacity.visibility = android.view.View.VISIBLE
        } else {
            previewOverlay?.let { b.map.overlays.remove(it) }
            previewOverlay = null
            b.seekOpacity.visibility = android.view.View.GONE
            b.map.invalidate()
        }
    }

    private fun refreshPreview() {
        val fit = currentFit()
        if (fit == null) {
            Toast.makeText(this, "Za predogled potrebujete vsaj 2 točki.", Toast.LENGTH_SHORT).show()
            previewing = false
            return
        }
        previewOverlay?.let { b.map.overlays.remove(it) }
        previewOverlay = CalibratedMapOverlay(bitmap, fit.matrix).apply {
            opacity = b.seekOpacity.progress
            b.map.overlays.add(0, this)
        }
        b.map.invalidate()
    }

    // ------------------------------------------------------------------ save

    private fun save() {
        val map = customMap ?: return
        if (points.size < 2) {
            Toast.makeText(this, "Potrebujete vsaj 2 kontrolni točki.", Toast.LENGTH_LONG).show()
            return
        }
        map.controlPoints = points.toMutableList()
        val fit = map.recalibrate()
        if (fit == null) {
            Toast.makeText(this, "Umerjanje ni uspelo — točke so degenerirane.", Toast.LENGTH_LONG).show()
            return
        }
        map.opacity = b.seekOpacity.progress.coerceAtLeast(80)
        store.upsert(map)

        // Warn rather than block: the user may know the map is a rough sketch and still
        // want it, and a loud number is more useful than a refusal.
        val estimate = map.bestErrorEstimate()
        if (estimate > 50) {
            AlertDialog.Builder(this)
                .setTitle("Shranjeno, a nenatančno")
                .setMessage(
                    "Pričakovana napaka je %.0f m.\n\nPogosti vzroki: točke so preblizu skupaj, "
                        .format(estimate) +
                        "napačno ujemanje, ali močno ukrivljen list papirja. Dodajte točke v vogalih."
                )
                .setPositiveButton(R.string.ok) { _, _ -> finishOk() }
                .show()
        } else {
            Toast.makeText(this, "Umerjeno: ${map.qualityLabel()}", Toast.LENGTH_LONG).show()
            finishOk()
        }
    }

    private fun finishOk() {
        setResult(Activity.RESULT_OK)
        finish()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onResume() { super.onResume(); b.map.onResume() }
    override fun onPause() { super.onPause(); b.map.onPause() }

    override fun onDestroy() {
        super.onDestroy()
        b.map.onDetach()
        b.imageView.bitmap = null
        bitmap?.recycle()
        bitmap = null
    }

    companion object {
        const val EXTRA_MAP_ID = "map_id"
    }
}
