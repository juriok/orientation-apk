package si.rok.orientacija.custom

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import si.rok.orientacija.MainActivity
import si.rok.orientacija.R
import si.rok.orientacija.databinding.ActivityCustomMapsBinding
import si.rok.orientacija.util.BitmapUtils
import si.rok.orientacija.util.EdgeToEdge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages user-supplied maps: photographs of paper sheets, scanned DTK25 sheets, hunting
 * ground plans — anything raster. Each one is imported, then calibrated, then can be made
 * the active overlay on the main map.
 */
class CustomMapsActivity : AppCompatActivity() {

    private lateinit var b: ActivityCustomMapsBinding
    private lateinit var store: CustomMapStore
    private var maps = mutableListOf<CustomMap>()
    private var pendingCaptureFile: File? = null

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingCaptureFile
        pendingCaptureFile = null
        if (ok && file != null && file.exists()) {
            importFrom(Uri.fromFile(file), "Fotografija ${timestamp()}")
            file.delete()
        }
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importFrom(it, "Karta ${timestamp()}") }
    }

    private val calibrate = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCustomMapsBinding.inflate(layoutInflater)
        setContentView(b.root)
        EdgeToEdge.apply(b.root, b.toolbar, b.list)
        b.toolbar.setNavigationOnClickListener { finish() }

        store = CustomMapStore(this)

        b.btnPhoto.setOnClickListener { capturePhoto() }
        b.btnImport.setOnClickListener { pickImage.launch("image/*") }
        b.list.setOnItemClickListener { _, _, pos, _ -> showActions(maps[pos]) }

        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun timestamp() = SimpleDateFormat("d.M. HH:mm", Locale.getDefault()).format(Date())

    // ------------------------------------------------------------------ import

    private fun capturePhoto() {
        val dir = File(cacheDir, "capture").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        pendingCaptureFile = file
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        try {
            takePicture.launch(uri)
        } catch (e: Exception) {
            pendingCaptureFile = null
            Toast.makeText(this, "Ni aplikacije za fotografiranje.", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Copies the image into app storage at a workable resolution.
     *
     * Re-encoding rather than keeping the original matters for two reasons: the source Uri
     * from a gallery pick is not guaranteed to stay readable, and a full-resolution phone
     * photo is too large to decode repeatedly while panning the map.
     */
    private fun importFrom(uri: Uri, defaultName: String) {
        val bmp = BitmapUtils.loadScaled(this, uri)
        if (bmp == null) {
            Toast.makeText(this, "Slike ni mogoče prebrati.", Toast.LENGTH_LONG).show()
            return
        }
        val dest = store.newImageFile()
        val saved = BitmapUtils.saveJpeg(bmp, dest)
        // Capture the dimensions before recycling: the calibration needs the image extent to
        // verify the fitted transform stays well-behaved across the whole sheet.
        val w = bmp.width
        val h = bmp.height
        bmp.recycle()
        if (!saved) {
            Toast.makeText(this, "Shranjevanje ni uspelo.", Toast.LENGTH_LONG).show()
            return
        }
        val map = CustomMap(
            name = defaultName,
            imagePath = dest.absolutePath,
            imageWidth = w,
            imageHeight = h
        )
        store.upsert(map)
        refresh()
        openCalibration(map)
    }

    private fun openCalibration(map: CustomMap) {
        calibrate.launch(
            Intent(this, CalibrationActivity::class.java)
                .putExtra(CalibrationActivity.EXTRA_MAP_ID, map.id)
        )
    }

    // ------------------------------------------------------------------ list

    private fun refresh() {
        maps = store.load()
        val activeId = prefs.getString(MainActivity.KEY_ACTIVE_MAP, null)
        b.txtEmpty.visibility = if (maps.isEmpty()) View.VISIBLE else View.GONE

        b.list.adapter = object : ArrayAdapter<CustomMap>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, maps
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val m = maps[position]
                val active = m.id == activeId
                v.findViewById<TextView>(android.R.id.text1).text =
                    if (active) "● ${m.name}" else m.name
                v.findViewById<TextView>(android.R.id.text2).text =
                    "${m.qualityLabel()} · ${m.controlPoints.size} točk" +
                        if (active) " · aktivna" else ""
                return v
            }
        }
    }

    private fun showActions(map: CustomMap) {
        val activeId = prefs.getString(MainActivity.KEY_ACTIVE_MAP, null)
        val isActive = map.id == activeId
        val items = mutableListOf(
            if (isActive) "Odstrani s karte" else "Prikaži na karti",
            "Umeri (kontrolne točke)",
            "Prosojnost",
            "Preimenuj",
            getString(R.string.delete)
        )

        AlertDialog.Builder(this)
            .setTitle(map.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> toggleActive(map, isActive)
                    1 -> openCalibration(map)
                    2 -> showOpacity(map)
                    3 -> showRename(map)
                    4 -> confirmDelete(map)
                }
            }
            .show()
    }

    private fun toggleActive(map: CustomMap, isActive: Boolean) {
        if (!isActive && !map.calibrated) {
            Toast.makeText(this, "Karto je treba najprej umeriti.", Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit()
            .putString(MainActivity.KEY_ACTIVE_MAP, if (isActive) null else map.id)
            .apply()
        refresh()
        if (!isActive) {
            Toast.makeText(
                this,
                "Prikazano. Podlago lahko izklopite v Sloji → Brez podlage.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showOpacity(map: CustomMap) {
        val seek = SeekBar(this).apply {
            max = 255
            progress = map.opacity
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.opacity)
            .setView(seek)
            .setPositiveButton(R.string.ok) { _, _ ->
                map.opacity = seek.progress.coerceAtLeast(30)
                store.upsert(map)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRename(map: CustomMap) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(map.name)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Preimenuj")
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                map.name = input.text.toString().ifBlank { map.name }
                store.upsert(map)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(map: CustomMap) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage("Izbrišem \"${map.name}\"? Slike in umerjanja ni mogoče povrniti.")
            .setPositiveButton(R.string.delete) { _, _ ->
                if (prefs.getString(MainActivity.KEY_ACTIVE_MAP, null) == map.id) {
                    prefs.edit().putString(MainActivity.KEY_ACTIVE_MAP, null).apply()
                }
                store.delete(map)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
