package si.rok.orientacija.data

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import si.rok.orientacija.R
import si.rok.orientacija.databinding.ActivityWaypointsBinding
import si.rok.orientacija.geo.CoordinateSystems
import si.rok.orientacija.util.EdgeToEdge

class WaypointsActivity : AppCompatActivity() {

    private lateinit var b: ActivityWaypointsBinding
    private lateinit var store: WaypointStore
    private var items = mutableListOf<Waypoint>()

    private val exportGpx = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri: Uri? -> uri?.let { doExport(it) } }

    private val importGpx = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { doImport(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWaypointsBinding.inflate(layoutInflater)
        setContentView(b.root)
        EdgeToEdge.apply(b.root, b.toolbar, b.list)
        b.toolbar.setNavigationOnClickListener { finish() }

        store = WaypointStore(this)
        b.btnExport.setOnClickListener {
            if (store.load().isEmpty()) {
                Toast.makeText(this, "Ni točk za izvoz.", Toast.LENGTH_SHORT).show()
            } else {
                exportGpx.launch("orientacija-tocke.gpx")
            }
        }
        // Many file pickers will not offer a file for an unknown MIME type, and GPX is
        // frequently reported as text/xml or application/octet-stream. Asking for */* is
        // the only reliable way to let the user reach their own file.
        b.btnImport.setOnClickListener { importGpx.launch("*/*") }
        b.list.setOnItemClickListener { _, _, pos, _ -> showActions(items[pos]) }

        refresh()
    }

    private fun refresh() {
        items = store.load()
        items.sortByDescending { it.createdAt }
        b.txtEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        b.list.adapter = object : ArrayAdapter<Waypoint>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val w = items[position]
                v.findViewById<TextView>(android.R.id.text1).text = w.name
                v.findViewById<TextView>(android.R.id.text2).text =
                    CoordinateSystems.formatDecimal(w.lat, w.lon) +
                        (w.elevation?.let { "  ·  %.0f m".format(it) } ?: "")
                return v
            }
        }
    }

    private fun showActions(w: Waypoint) {
        val d96 = CoordinateSystems.toD96TM(w.lat, w.lon)
        AlertDialog.Builder(this)
            .setTitle(w.name)
            .setMessage(
                "WGS84: ${CoordinateSystems.formatDecimal(w.lat, w.lon)}\n" +
                    "D96/TM: E %.0f  N %.0f\n".format(d96.easting, d96.northing) +
                    "MGRS: ${CoordinateSystems.toMgrs(w.lat, w.lon)}" +
                    (if (w.note.isNotBlank()) "\n\n${w.note}" else "")
            )
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.delete) { _, _ ->
                store.delete(w.id)
                refresh()
            }
            .show()
    }

    private fun doExport(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { GpxIo.writeWaypoints(it, store.load()) }
            Toast.makeText(this, "Izvoženo.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Izvoz ni uspel: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun doImport(uri: Uri) {
        try {
            val imported = contentResolver.openInputStream(uri)?.use { GpxIo.readWaypoints(it) }
                ?: emptyList()
            if (imported.isEmpty()) {
                Toast.makeText(this, "V datoteki ni najdenih točk.", Toast.LENGTH_LONG).show()
                return
            }
            val all = store.load()
            all.addAll(imported)
            store.save(all)
            refresh()
            Toast.makeText(this, "Uvoženih točk: ${imported.size}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Uvoz ni uspel: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
