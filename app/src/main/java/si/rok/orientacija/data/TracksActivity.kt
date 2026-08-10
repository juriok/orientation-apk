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
import si.rok.orientacija.databinding.ActivitySimpleListBinding
import si.rok.orientacija.util.EdgeToEdge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TracksActivity : AppCompatActivity() {

    private lateinit var b: ActivitySimpleListBinding
    private lateinit var store: TrackStore
    private var items = mutableListOf<Track>()
    private var pendingExport: Track? = null

    private val exportGpx = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri: Uri? -> uri?.let { doExport(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySimpleListBinding.inflate(layoutInflater)
        setContentView(b.root)
        EdgeToEdge.apply(b.root, b.toolbar, b.list)
        b.toolbar.title = getString(R.string.tracks)
        b.toolbar.setNavigationOnClickListener { finish() }

        store = TrackStore(this)
        b.txtEmpty.setText(R.string.no_tracks_yet)

        b.btnPrimary.setText(R.string.export_all_gpx)
        b.btnPrimary.setOnClickListener {
            if (items.isEmpty()) {
                Toast.makeText(this, "Ni sledi za izvoz.", Toast.LENGTH_SHORT).show()
            } else {
                pendingExport = null
                exportGpx.launch("orientacija-sledi.gpx")
            }
        }
        b.btnSecondary.setText(R.string.delete_all)
        b.btnSecondary.setOnClickListener { confirmDeleteAll() }

        b.list.setOnItemClickListener { _, _, pos, _ -> showActions(items[pos]) }
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        items = store.load()
        items.sortByDescending { it.startedAt }
        b.txtEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val fmt = SimpleDateFormat("d.M.yyyy HH:mm", Locale.getDefault())
        b.list.adapter = object : ArrayAdapter<Track>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val t = items[position]
                v.findViewById<TextView>(android.R.id.text1).text = t.name
                v.findViewById<TextView>(android.R.id.text2).text = summary(t, fmt.format(Date(t.startedAt)))
                return v
            }
        }
    }

    private fun summary(t: Track, started: String): String {
        val km = t.distanceMetres() / 1000.0
        val mins = t.durationMillis() / 60000
        return "%s · %.2f km · %d min · %.0f m vzpona · %d točk"
            .format(started, km, mins, t.ascentMetres(), t.points.size)
    }

    private fun showActions(t: Track) {
        AlertDialog.Builder(this)
            .setTitle(t.name)
            .setItems(arrayOf(getString(R.string.export_gpx), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> { pendingExport = t; exportGpx.launch("${sanitise(t.name)}.gpx") }
                    1 -> {
                        store.delete(t.id)
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun sanitise(s: String) = s.replace(Regex("[^A-Za-z0-9ČčŠšŽž _-]"), "").ifBlank { "sled" }

    private fun confirmDeleteAll() {
        if (items.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_all)
            .setMessage("Izbrišem vse shranjene sledi? Tega ni mogoče razveljaviti.")
            .setPositiveButton(R.string.delete) { _, _ -> store.save(emptyList()); refresh() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doExport(uri: Uri) {
        try {
            val toWrite = pendingExport?.let { listOf(it) } ?: store.load()
            contentResolver.openOutputStream(uri)?.use { GpxIo.writeTracks(it, toWrite) }
            Toast.makeText(this, "Izvoženo.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Izvoz ni uspel: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            pendingExport = null
        }
    }
}
