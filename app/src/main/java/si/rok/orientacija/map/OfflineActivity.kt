package si.rok.orientacija.map

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import si.rok.orientacija.R
import si.rok.orientacija.databinding.ActivitySimpleListBinding
import si.rok.orientacija.util.EdgeToEdge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists what has been downloaded for offline use and allows removing it again.
 *
 * osmdroid stores every tile in one database with no record of which download produced it,
 * so the packs listed here come from our own bookkeeping; deleting one clears the tiles in
 * its bounding box and zoom range.
 */
class OfflineActivity : AppCompatActivity() {

    private lateinit var b: ActivitySimpleListBinding
    private lateinit var store: OfflinePackStore
    private var items = mutableListOf<OfflinePack>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySimpleListBinding.inflate(layoutInflater)
        setContentView(b.root)
        EdgeToEdge.apply(b.root, b.toolbar, b.list)
        b.toolbar.setNavigationOnClickListener { finish() }

        store = OfflinePackStore(this)
        b.txtEmpty.setText(R.string.no_packs_yet)

        b.btnPrimary.setText(R.string.delete_all)
        b.btnPrimary.setOnClickListener { confirmDeleteAll() }
        b.btnSecondary.setText(R.string.purge_cache)
        b.btnSecondary.setOnClickListener { confirmPurge() }

        b.list.setOnItemClickListener { _, _, pos, _ -> showActions(items[pos]) }
        refresh()
    }

    private fun refresh() {
        items = store.load()
        items.sortByDescending { it.createdAt }
        b.txtEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        val used = OfflineDownloader.cacheUsage()
        b.toolbar.title = if (used >= 0) {
            "%s · %.0f MB".format(getString(R.string.offline), used / 1024.0 / 1024.0)
        } else getString(R.string.offline)

        val fmt = SimpleDateFormat("d.M.yyyy", Locale.getDefault())
        b.list.adapter = object : ArrayAdapter<OfflinePack>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val p = items[position]
                v.findViewById<TextView>(android.R.id.text1).text = p.name
                v.findViewById<TextView>(android.R.id.text2).text =
                    "%s · %.1f × %.1f km · z%d–%d · %d ploščic · ~%d MB%s".format(
                        fmt.format(Date(p.createdAt)),
                        p.widthKm(), p.heightKm(), p.zoomMin, p.zoomMax,
                        p.tileCount, p.approxBytes() / (1024 * 1024),
                        if (p.includesRelief) " · z reliefom" else ""
                    )
                return v
            }
        }
    }

    private fun showActions(pack: OfflinePack) {
        AlertDialog.Builder(this)
            .setTitle(pack.name)
            .setMessage(
                "Sloj: ${pack.layerLabel}\nPribližno ${pack.approxBytes() / (1024 * 1024)} MB\n\n" +
                    "Brisanje odstrani prenesene ploščice tega območja."
            )
            .setPositiveButton(R.string.delete) { _, _ -> delete(pack) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun delete(pack: OfflinePack) {
        val sources = mutableListOf(MapLayers.byId(null).source)
        // Which base the pack used is not recorded precisely enough to be certain, so clear
        // the area for every source it could plausibly have come from. Clearing a range that
        // was never downloaded is harmless.
        MapLayers.ALL.forEach { if (it.source !in sources) sources.add(it.source) }
        if (pack.includesRelief) sources.add(MapLayers.HILLSHADE)

        var remaining = sources.size
        sources.forEach { src ->
            OfflineDownloader.clearArea(this, src, pack.boundingBox(), pack.zoomMin, pack.zoomMax) {
                remaining--
                if (remaining <= 0) {
                    store.delete(pack.id)
                    runOnUiThread { refresh(); Toast.makeText(this, "Izbrisano.", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun confirmDeleteAll() {
        if (items.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_all)
            .setMessage("Izbrišem vse prenesene ploščice iz seznama?")
            .setPositiveButton(R.string.delete) { _, _ ->
                items.toList().forEach { delete(it) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Wipes the whole tile database, including tiles cached simply by panning around.
     * Offered separately because the pack list only knows about deliberate downloads.
     */
    private fun confirmPurge() {
        AlertDialog.Builder(this)
            .setTitle(R.string.purge_cache)
            .setMessage(
                "Počisti celoten predpomnilnik ploščic, tudi tiste, ki so se shranile med " +
                    "običajno uporabo. Karte se bodo znova prenesle, ko boste imeli signal."
            )
            .setPositiveButton(R.string.ok) { _, _ ->
                val ok = runCatching {
                    org.osmdroid.tileprovider.modules.SqlTileWriter().purgeCache()
                }.getOrDefault(false)
                store.save(emptyList())
                refresh()
                Toast.makeText(
                    this,
                    if (ok) "Predpomnilnik počiščen." else "Čiščenje ni uspelo.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
