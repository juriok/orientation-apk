package si.rok.orientacija

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.preference.PreferenceManager
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import si.rok.orientacija.custom.CalibratedMapOverlay
import si.rok.orientacija.custom.CustomMap
import si.rok.orientacija.custom.CustomMapStore
import si.rok.orientacija.custom.CustomMapsActivity
import si.rok.orientacija.data.Course
import si.rok.orientacija.data.CourseRunner
import si.rok.orientacija.data.CourseStore
import si.rok.orientacija.data.CoursesActivity
import si.rok.orientacija.data.GpxIo
import si.rok.orientacija.data.Track
import si.rok.orientacija.data.TrackRecorder
import si.rok.orientacija.data.TrackRecordingService
import si.rok.orientacija.data.TrackStore
import si.rok.orientacija.data.TracksActivity
import si.rok.orientacija.data.Waypoint
import si.rok.orientacija.data.WaypointStore
import si.rok.orientacija.data.WaypointsActivity
import si.rok.orientacija.databinding.ActivityMainBinding
import si.rok.orientacija.geo.CoordinateSystems
import si.rok.orientacija.geo.GeoMath
import si.rok.orientacija.geo.Transform2D
import si.rok.orientacija.map.ClssRelief
import si.rok.orientacija.map.ClssReliefProvider
import si.rok.orientacija.map.ClssSheets
import si.rok.orientacija.map.GursTopoTileSource
import si.rok.orientacija.map.HillshadeOverlay
import si.rok.orientacija.map.LayerDef
import si.rok.orientacija.map.LocationMarkers
import si.rok.orientacija.map.MapFilters
import si.rok.orientacija.map.MapLayers
import si.rok.orientacija.map.OfflineActivity
import si.rok.orientacija.map.OfflineDownloader
import si.rok.orientacija.map.OfflinePack
import si.rok.orientacija.map.OfflinePackStore
import si.rok.orientacija.map.ReliefSource
import si.rok.orientacija.util.BitmapUtils
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var b: ActivityMainBinding

    // Overlays, kept as fields so the stack can be rebuilt in a fixed order.
    private var locationOverlay: MyLocationNewOverlay? = null
    private var compassOverlay: CompassOverlay? = null
    private var scaleBar: ScaleBarOverlay? = null
    private var eventsOverlay: MapEventsOverlay? = null
    private var customOverlay: CalibratedMapOverlay? = null
    private var hillshadeOverlay: HillshadeOverlay? = null
    private var trackLine: Polyline? = null
    private var courseLine: Polyline? = null
    /** Saved or imported tracks the user has asked to see, drawn under the live one. */
    private val shownTrackLines = mutableListOf<Polyline>()
    private val waypointMarkers = mutableListOf<Marker>()
    private val controlMarkers = mutableListOf<Marker>()

    private var activeCustomMap: CustomMap? = null
    private var customMapBounds: BoundingBox? = null

    private val waypointStore by lazy { WaypointStore(this) }
    private val customStore by lazy { CustomMapStore(this) }
    private val trackStore by lazy { TrackStore(this) }
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    private var currentLayer: LayerDef = MapLayers.TOPO
    /** Base map brightness, 0 (off) to 100 (untouched). */
    private var baseBrightness = 100
    private var autoSharpen = true
    /** Set when auto-sharpening swapped the layer, so it can be swapped back. */
    private var sharpenedFrom: LayerDef? = null
    private var coordFormat = 0
    private var hillshadeOn = false
    private var reliefSource = ReliefSource.GURS
    /** Source the live overlay was built for, so a change can be noticed and rebuilt. */
    private var reliefOverlaySource: ReliefSource? = null
    private var hillshadeStrength = HillshadeOverlay.DEFAULT_STRENGTH
    private var hillshadeContrast = HillshadeOverlay.DEFAULT_CONTRAST
    private var hillshadeBrightness = HillshadeOverlay.DEFAULT_BRIGHTNESS
    private var mapSaturation = 100
    private var nightMode = false
    private var rotateWithHeading = false
    private var heading: Float? = null
    private var appliedRotation = 0f
    private var target: Waypoint? = null

    private lateinit var sensorManager: SensorManager
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private val ui = Handler(Looper.getMainLooper())
    private val refreshTick = object : Runnable {
        override fun run() {
            updateReadout()
            updateLiveOverlays()
            ui.postDelayed(this, 1000)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) enableLocation()
        else Toast.makeText(this, R.string.loc_permission_rationale, Toast.LENGTH_LONG).show()
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Recording works either way; without it there is simply no ongoing notification. */ }

    private val courseLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { rebuildOverlays(); updateReadout() }

    // GPX is routinely reported as text/xml or application/octet-stream, and a picker will
    // hide a file whose type it cannot match, so the filter has to stay wide open.
    private val importGpx = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { importGpxFile(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureOsmdroid()

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        coordFormat = prefs.getInt(KEY_COORD_FORMAT, 0)
        currentLayer = MapLayers.byId(prefs.getString(KEY_LAYER, MapLayers.TOPO.id))
        baseBrightness = prefs.getInt(KEY_BASE_BRIGHTNESS, 100)
        autoSharpen = prefs.getBoolean(KEY_AUTO_SHARPEN, true)
        hillshadeOn = prefs.getBoolean(KEY_HILLSHADE, false)
        reliefSource = ReliefSource.byId(prefs.getString(KEY_RELIEF_SOURCE, ReliefSource.GURS.id))
        hillshadeStrength = prefs.getInt(KEY_HILLSHADE_STRENGTH, HillshadeOverlay.DEFAULT_STRENGTH)
        hillshadeContrast = prefs.getInt(KEY_HILLSHADE_CONTRAST, HillshadeOverlay.DEFAULT_CONTRAST)
        hillshadeBrightness = prefs.getInt(KEY_HILLSHADE_BRIGHTNESS, HillshadeOverlay.DEFAULT_BRIGHTNESS)
        mapSaturation = prefs.getInt(KEY_MAP_SATURATION, 100)
        nightMode = prefs.getBoolean(KEY_NIGHT_MODE, false)
        rotateWithHeading = prefs.getBoolean(KEY_ROTATE, false)
        CourseRunner.punchRadiusMetres =
            getSharedPreferences("orientacija", MODE_PRIVATE).getInt("punch_radius", 25).toDouble()

        applyWindowInsets()
        setupMap()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        b.fabLocation.setOnClickListener { centreOnLocation() }
        b.fabLayers.setOnClickListener { showLayerDialog() }
        b.fabAdd.setOnClickListener { addWaypointHere() }
        b.fabMenu.setOnClickListener { showMenu() }
        b.infoPanel.setOnClickListener { cycleCoordFormat() }

        requestLocationPermission()
    }

    /**
     * Keeps the overlay panels clear of the status bar, notch and gesture bar.
     *
     * Targeting SDK 35 means Android 15 draws the app edge to edge and no longer reserves
     * that space, so without this the coordinate readout sits underneath the clock. The map
     * itself is deliberately left full-bleed.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            b.infoPanel.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top + dp(8)
                leftMargin = bars.left + dp(8)
                rightMargin = bars.right + dp(8)
            }
            b.fabColumn.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom + dp(16)
                rightMargin = bars.right + dp(16)
            }
            // These two are drawn by overlays rather than laid out, so they need the insets
            // folded into their own offsets or they sit under the status and gesture bars.
            scaleBar?.setScaleBarOffset(bars.left + dp(14), bars.bottom + dp(44))
            val density = resources.displayMetrics.density
            compassOverlay?.setCompassCenter(36f + bars.left / density, 36f + bars.top / density)
            insets
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun configureOsmdroid() {
        val cfg = Configuration.getInstance()
        cfg.load(this, prefs)
        cfg.userAgentValue = packageName
        val base = File(filesDir, "osmdroid").apply { mkdirs() }
        cfg.osmdroidBasePath = base
        cfg.osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
        cfg.tileFileSystemCacheMaxBytes = 3L * 1024 * 1024 * 1024
        cfg.tileFileSystemCacheTrimBytes = (2.6 * 1024 * 1024 * 1024).toLong()
        cfg.expirationOverrideDuration = 30L * 24 * 3600 * 1000
        // Applies to every provider created afterwards, the base map included: the stock
        // value is small enough that a tall phone screen cannot hold one full view.
        cfg.cacheMapTileCount = tileCacheCapacityForScreen().toShort()
    }

    /**
     * Tiles needed to cover the screen, with a margin so panning does not immediately evict
     * what is still on show. At 256 KB a decoded tile this is a few tens of megabytes.
     */
    private fun tileCacheCapacityForScreen(): Int {
        val dm = resources.displayMetrics
        val cols = dm.widthPixels / 256 + 3
        val rows = dm.heightPixels / 256 + 3
        return (cols * rows).coerceIn(32, 96)
    }

    // ------------------------------------------------------------------ map setup

    private fun setupMap() {
        val map = b.map
        map.setTileSource(currentLayer.source)
        map.setMultiTouchControls(true)
        // White behind the tiles, because the relief composites with MULTIPLY and white is
        // its identity: with the base map switched off entirely, the shading then renders as
        // itself on a clean sheet instead of dissolving into a black background.
        map.setBackgroundColor(Color.WHITE)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.isTilesScaledToDpi = true
        applyLayerZoomRange(currentLayer)

        val lat = prefs.getFloat(KEY_LAT, 46.12f).toDouble()
        val lon = prefs.getFloat(KEY_LON, 14.82f).toDouble()
        map.controller.setZoom(
            prefs.getFloat(KEY_ZOOM, 10f).toDouble().coerceIn(currentLayer.minZoom, currentLayer.maxZoom)
        )
        map.controller.setCenter(GeoPoint(lat, lon))

        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map).apply {
            val dm = resources.displayMetrics
            setPersonIcon(LocationMarkers.positionDot(dm))
            setDirectionIcon(LocationMarkers.directionArrow(dm))
            setPersonAnchor(0.5f, 0.5f)
            setDirectionAnchor(0.5f, 0.5f)
            enableMyLocation()
        }

        scaleBar = ScaleBarOverlay(map).apply {
            setCentred(false)
            setAlignBottom(true)
            setAlignRight(false)
            setTextSize(resources.displayMetrics.density * 13f)
            setScaleBarOffset(dp(14), dp(44))
        }

        compassOverlay = CompassOverlay(this, InternalCompassOrientationProvider(this), map).apply {
            enableCompass()
        }

        eventsOverlay = MapEventsOverlay(object : org.osmdroid.events.MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p?.let { promptWaypoint(it) }
                return true
            }
        })

        trackLine = Polyline(map).apply {
            outlinePaint.color = Color.parseColor("#E53935")
            outlinePaint.strokeWidth = resources.displayMetrics.density * 4f
            outlinePaint.isAntiAlias = true
        }
        courseLine = Polyline(map).apply {
            outlinePaint.color = Color.parseColor("#6A1B9A")
            outlinePaint.strokeWidth = resources.displayMetrics.density * 3f
            outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 12f), 0f)
            outlinePaint.isAntiAlias = true
        }

        map.addMapListener(object : MapListener {
            override fun onScroll(e: ScrollEvent?): Boolean { updateReadout(); return false }
            override fun onZoom(e: ZoomEvent?): Boolean { maybeAutoSharpen(); updateReadout(); return false }
        })

        loadCustomOverlay()
        applyHillshade()
        applyBaseBrightness()
        rebuildOverlays()
    }

    /**
     * Rebuilds the overlay stack bottom-to-top in one place.
     *
     * Ordering matters twice over: it decides what is drawn on top of what, and osmdroid
     * dispatches touches from the top down. Assembling the list here rather than inserting
     * at hard-coded indices from a dozen call sites is what keeps the relief under the
     * user's own map, and the long-press handler beneath the markers.
     */
    private fun rebuildOverlays() {
        val map = b.map
        map.overlays.clear()

        eventsOverlay?.let { map.overlays.add(it) }          // bottom: long-press to add points
        if (hillshadeOn) hillshadeOverlay?.let { map.overlays.add(it) }
        customOverlay?.let { map.overlays.add(it) }
        shownTrackLines.forEach { map.overlays.add(it) }
        courseLine?.let { if (CourseRunner.isRunning) map.overlays.add(it) }
        trackLine?.let { if (it.actualPoints.isNotEmpty()) map.overlays.add(it) }
        controlMarkers.forEach { map.overlays.add(it) }
        waypointMarkers.forEach { map.overlays.add(it) }
        scaleBar?.let { map.overlays.add(it) }
        compassOverlay?.let { map.overlays.add(it) }
        locationOverlay?.let { map.overlays.add(it) }        // top: always visible

        map.invalidate()
    }

    // ------------------------------------------------------------------ layers

    /**
     * One panel for layer, brightness and relief, each independent.
     *
     * They used to share a single radio list, so choosing "dimmed" quietly discarded the
     * layer selection — and because dimming multiplies the base down before the relief
     * multiplies again, it made the shading look broken rather than dim.
     */
    private fun showLayerDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_layers, null)
        val group = view.findViewById<android.widget.RadioGroup>(R.id.layerGroup)
        val seekBrightness = view.findViewById<SeekBar>(R.id.seekBrightness)
        val txtBrightness = view.findViewById<android.widget.TextView>(R.id.txtBrightness)
        val chkRelief = view.findViewById<android.widget.CheckBox>(R.id.chkRelief)
        val reliefGroup = view.findViewById<android.widget.RadioGroup>(R.id.reliefGroup)
        val seekRelief = view.findViewById<SeekBar>(R.id.seekRelief)
        val txtRelief = view.findViewById<android.widget.TextView>(R.id.txtRelief)
        val seekSaturation = view.findViewById<SeekBar>(R.id.seekSaturation)
        val txtSaturation = view.findViewById<android.widget.TextView>(R.id.txtSaturation)
        val seekContrast = view.findViewById<SeekBar>(R.id.seekReliefContrast)
        val txtContrast = view.findViewById<android.widget.TextView>(R.id.txtReliefContrast)
        val seekReliefBright = view.findViewById<SeekBar>(R.id.seekReliefBrightness)
        val txtReliefBright = view.findViewById<android.widget.TextView>(R.id.txtReliefBrightness)
        val btnReset = view.findViewById<android.widget.Button>(R.id.btnResetRelief)
        val chkNight = view.findViewById<android.widget.CheckBox>(R.id.chkNight)
        val chkAuto = view.findViewById<android.widget.CheckBox>(R.id.chkAutoSharp)

        // Built from the layer list rather than fixed in XML, so the two cannot drift apart.
        MapLayers.ALL.forEachIndexed { i, layer ->
            group.addView(android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = "${layer.label} — ${layer.description}"
                tag = i
                isChecked = baseBrightness > 0 && layer.id == currentLayer.id
            })
        }
        // An explicit "no base" entry. Turning the visibility slider to zero does the same
        // thing, but nobody finds a feature by dragging a slider to its end.
        group.addView(android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.no_base_map)
            tag = NO_BASE_TAG
            isChecked = baseBrightness == 0
        })

        // Both sliders run 0-100 so the two can be read against each other directly: turn the
        // map down and the relief up, or the reverse, to favour whichever you want to see.
        fun brightnessLabel(v: Int) = getString(R.string.base_brightness) + when (v) {
            0 -> ": skrita"
            100 -> ": polna"
            else -> ": $v %"
        }
        seekBrightness.progress = baseBrightness
        txtBrightness.text = brightnessLabel(baseBrightness)
        seekBrightness.setOnSeekBarChangeListener(simpleSeek { txtBrightness.text = brightnessLabel(it) })

        fun saturationLabel(v: Int) = getString(R.string.map_saturation) + when (v) {
            0 -> ": sivinsko"
            100 -> ": polna"
            else -> ": $v %"
        }
        seekSaturation.progress = mapSaturation
        txtSaturation.text = saturationLabel(mapSaturation)
        seekSaturation.setOnSeekBarChangeListener(simpleSeek { txtSaturation.text = saturationLabel(it) })

        fun reliefLabel(v: Int) = getString(R.string.relief_visibility) + ": $v %"
        fun contrastLabel(v: Int) = getString(R.string.relief_contrast) + ": $v %"
        fun reliefBrightLabel(v: Int) = getString(R.string.relief_brightness) +
            ": " + (if (v == 50) "nevtralno" else "%+d".format(v - 50))

        chkRelief.isChecked = hillshadeOn
        // Built from the source list for the same reason the base layers are.
        ReliefSource.entries.forEachIndexed { i, src ->
            reliefGroup.addView(android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = "${src.label} — ${src.description}"
                tag = i
                isChecked = src == reliefSource
            })
        }
        seekRelief.progress = strengthToPercent(hillshadeStrength)
        txtRelief.text = reliefLabel(seekRelief.progress)
        seekRelief.setOnSeekBarChangeListener(simpleSeek { txtRelief.text = reliefLabel(it) })

        seekContrast.progress = hillshadeContrast
        txtContrast.text = contrastLabel(hillshadeContrast)
        seekContrast.setOnSeekBarChangeListener(simpleSeek { txtContrast.text = contrastLabel(it) })

        seekReliefBright.progress = hillshadeBrightness
        txtReliefBright.text = reliefBrightLabel(hillshadeBrightness)
        seekReliefBright.setOnSeekBarChangeListener(simpleSeek { txtReliefBright.text = reliefBrightLabel(it) })

        // The three relief sliders interact, so it is easy to end up somewhere odd with no
        // memory of the way back.
        btnReset.setOnClickListener {
            seekRelief.progress = strengthToPercent(HillshadeOverlay.DEFAULT_STRENGTH)
            seekContrast.progress = HillshadeOverlay.DEFAULT_CONTRAST
            seekReliefBright.progress = HillshadeOverlay.DEFAULT_BRIGHTNESS
            txtRelief.text = reliefLabel(seekRelief.progress)
            txtContrast.text = contrastLabel(seekContrast.progress)
            txtReliefBright.text = reliefBrightLabel(seekReliefBright.progress)
        }

        fun setReliefEnabled(on: Boolean) {
            seekRelief.isEnabled = on
            seekContrast.isEnabled = on
            seekReliefBright.isEnabled = on
            btnReset.isEnabled = on
            for (i in 0 until reliefGroup.childCount) reliefGroup.getChildAt(i).isEnabled = on
        }
        setReliefEnabled(hillshadeOn)
        chkRelief.setOnCheckedChangeListener { _, checked -> setReliefEnabled(checked) }

        chkNight.isChecked = nightMode
        chkAuto.isChecked = autoSharpen

        AlertDialog.Builder(this)
            .setTitle(R.string.layers)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                val checkedTag = (0 until group.childCount)
                    .map { group.getChildAt(it) as android.widget.RadioButton }
                    .firstOrNull { it.isChecked }?.tag as? Int ?: 0

                if (checkedTag == NO_BASE_TAG) {
                    // Keep currentLayer as it was, so switching the base back on returns to
                    // the layer that was in use rather than resetting to the first one.
                    baseBrightness = 0
                } else {
                    val chosen = MapLayers.ALL[checkedTag]
                    if (chosen.id != currentLayer.id) {
                        currentLayer = chosen
                        sharpenedFrom = null
                        b.map.setTileSource(currentLayer.source)
                        applyLayerZoomRange(currentLayer)
                    }
                    // A layer was picked deliberately, so do not leave it invisible because
                    // the slider happened to still be at zero from the previous mode.
                    baseBrightness = seekBrightness.progress.coerceAtLeast(10)
                }
                hillshadeOn = chkRelief.isChecked
                val previousSource = reliefSource
                reliefSource = (0 until reliefGroup.childCount)
                    .map { reliefGroup.getChildAt(it) as android.widget.RadioButton }
                    .firstOrNull { it.isChecked }
                    ?.let { ReliefSource.entries[it.tag as Int] } ?: reliefSource
                hillshadeStrength = percentToStrength(seekRelief.progress.coerceAtLeast(8))
                hillshadeContrast = seekContrast.progress
                hillshadeBrightness = seekReliefBright.progress
                mapSaturation = seekSaturation.progress
                nightMode = chkNight.isChecked
                autoSharpen = chkAuto.isChecked

                prefs.edit()
                    .putString(KEY_LAYER, currentLayer.id)
                    .putString(KEY_RELIEF_SOURCE, reliefSource.id)
                    .putInt(KEY_BASE_BRIGHTNESS, baseBrightness)
                    .putBoolean(KEY_HILLSHADE, hillshadeOn)
                    .putInt(KEY_HILLSHADE_STRENGTH, hillshadeStrength)
                    .putInt(KEY_HILLSHADE_CONTRAST, hillshadeContrast)
                    .putInt(KEY_HILLSHADE_BRIGHTNESS, hillshadeBrightness)
                    .putInt(KEY_MAP_SATURATION, mapSaturation)
                    .putBoolean(KEY_NIGHT_MODE, nightMode)
                    .putBoolean(KEY_AUTO_SHARPEN, autoSharpen)
                    .apply()

                applyHillshade()
                applyBaseBrightness()
                rebuildOverlays()
                updateReadout()
                if (hillshadeOn) announceRelief(sourceChanged = reliefSource != previousSource)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The overlay works in 0-255 alpha terms; the UI shows plain percentages. */
    private fun strengthToPercent(v: Int) = (v * 100 / 255).coerceIn(0, 100)
    private fun percentToStrength(p: Int) = (p * 255 / 100).coerceIn(0, 255)

    private inline fun simpleSeek(crossinline onChange: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) = onChange(value)
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }

    private fun applyBaseBrightness() {
        val tiles = b.map.overlayManager.tilesOverlay
        val visible = baseBrightness > 0
        tiles.isEnabled = visible
        b.map.setUseDataConnection(visible)
        // Fade toward white rather than scale toward black. Darkening fought everything drawn
        // on top — the relief multiplies, so a dark base only got darker and the shading
        // disappeared into mud. Washing out to white leaves contours and colour legible while
        // letting the relief and any custom sheet take the foreground.
        //
        // White specifically, not transparency: tile bitmaps are decoded without an alpha
        // channel, so a filter that lowers alpha may simply be ignored, whereas a scale and
        // offset on the colour channels always applies.
        //
        // Order matters: desaturate and fade the map first, then apply the night tint, so the
        // tint acts on the finished image rather than being washed out by the fade.
        var matrix: ColorMatrix? = null
        if (mapSaturation != 100) matrix = MapFilters.saturation(mapSaturation / 100f)
        if (baseBrightness in 1..99) {
            val fade = MapFilters.fadeToWhite(baseBrightness / 100f)
            matrix = matrix?.let { MapFilters.compose(it, fade) } ?: fade
        }
        nightFilterMatrix()?.let { night ->
            matrix = matrix?.let { MapFilters.compose(it, night) } ?: night
        }
        tiles.setColorFilter(matrix?.let { ColorMatrixColorFilter(it) })

        // Switching the base off changes how the relief has to be composited.
        hillshadeOverlay?.standalone = !visible
        applyNightTint()
        if (!visible) {
            b.map.minZoomLevel = 6.0
            b.map.maxZoomLevel = 20.0
        } else {
            applyLayerZoomRange(currentLayer)
        }
        b.map.invalidate()
    }

    /**
     * Swaps to the orthophoto once the topographic sheets have run out of real detail.
     *
     * DTK50 stops at z16 and OpenTopoMap at z17; past that osmdroid is only enlarging the
     * last tile, so the map turns to mush exactly when you are looking closest. The 25 cm
     * orthophoto is native to z18 and stays sharp. A one-zoom hysteresis band stops it
     * flapping back and forth while pinching.
     */
    private fun maybeAutoSharpen() {
        if (!autoSharpen || baseBrightness == 0) return
        val zoom = b.map.zoomLevelDouble
        val previous = sharpenedFrom
        if (previous == null) {
            if (currentLayer.id == MapLayers.ORTHOPHOTO.id) return
            if (zoom > currentLayer.nativeMaxZoom + 0.6) {
                sharpenedFrom = currentLayer
                currentLayer = MapLayers.ORTHOPHOTO
                b.map.setTileSource(currentLayer.source)
                applyLayerZoomRange(currentLayer)
                updateReadout()
            }
        } else if (zoom < previous.nativeMaxZoom - 0.4) {
            currentLayer = previous
            sharpenedFrom = null
            b.map.setTileSource(currentLayer.source)
            applyLayerZoomRange(currentLayer)
            updateReadout()
        }
    }

    /**
     * Clamps the map to the zoom range the layer can serve. The GURS WMS answers
     * out-of-band requests with a blank white image rather than an error, so there is
     * nothing for the client to detect — the screen simply goes white.
     */
    private fun applyLayerZoomRange(layer: LayerDef) {
        b.map.minZoomLevel = layer.minZoom
        b.map.maxZoomLevel = layer.maxZoom
        val z = b.map.zoomLevelDouble
        if (z < layer.minZoom || z > layer.maxZoom) {
            b.map.controller.setZoom(z.coerceIn(layer.minZoom, layer.maxZoom))
        }
    }

    private fun applyHillshade() {
        // The two scans are served in completely different ways, so switching between them
        // means a new provider rather than a new URL.
        if (hillshadeOn && (hillshadeOverlay == null || reliefOverlaySource != reliefSource)) {
            hillshadeOverlay?.onDetach(b.map)
            val provider = when (reliefSource) {
                // Drawn on the device from the CLSS sheets; see ClssReliefProvider.
                ReliefSource.CLSS -> ClssReliefProvider(applicationContext)
                // A stock provider, deliberately. osmdroid resolves a tile by walking its
                // provider chain — filesystem, archive, approximator, downloader — and
                // signals "try the next one" through mapTileRequestFailed. Overriding that
                // to retry, as an earlier version did, stopped requests ever reaching the
                // downloader and wedged every tile as permanently in-progress. The 504s are
                // handled instead by the once-a-second redraw below, which re-requests
                // whatever is still missing.
                ReliefSource.GURS -> MapTileProviderBasic(applicationContext, MapLayers.HILLSHADE)
            }
            // Size the cache for the screen. Nothing in osmdroid's draw path does this for a
            // provider you create yourself — the MapView only sizes its own — and the default
            // holds barely a third of a phone screen. Under-sized, the relief renders as one
            // contiguous block with the rest blank, and vanishes entirely on zooming out
            // because every tile at the new level is fresh at once.
            provider.tileCache.setAutoEnsureCapacity(true)
            provider.ensureCapacity(tileCacheCapacityForScreen())
            hillshadeOverlay = HillshadeOverlay(this, provider).apply { strength = hillshadeStrength }
            reliefOverlaySource = reliefSource
        }
        hillshadeOverlay?.apply {
            tones = when (reliefSource) {
                ReliefSource.CLSS -> HillshadeOverlay.Tones.CLSS_2023
                ReliefSource.GURS -> HillshadeOverlay.Tones.GURS_2011
            }
            strength = hillshadeStrength
            contrast = hillshadeContrast
            brightness = hillshadeBrightness
            // With no base map there is nothing to multiply against, so the relief switches
            // to being drawn directly and contrast-stretched instead.
            standalone = baseBrightness == 0
        }
        applyNightTint()
    }

    /**
     * Says what the relief will and will not show right now.
     *
     * Worth being explicit about: with CLSS the map can look broken otherwise. It draws
     * nothing at all until the sheets under the view have come down, and zoomed out it
     * deliberately draws only what is already stored, so silence would read as a fault
     * rather than as the app declining to pull a hundred megabytes.
     */
    private fun announceRelief(sourceChanged: Boolean) {
        val zoom = b.map.zoomLevelDouble
        if (reliefSource == ReliefSource.CLSS && sourceChanged &&
            !prefs.getBoolean(KEY_CLSS_EXPLAINED, false)
        ) {
            prefs.edit().putBoolean(KEY_CLSS_EXPLAINED, true).apply()
            AlertDialog.Builder(this)
                .setTitle(ReliefSource.CLSS.label)
                .setMessage(getString(R.string.clss_data_warning))
                .setPositiveButton(R.string.download_clss) { _, _ ->
                    downloadClssArea(b.map.boundingBox, "Vidno območje")
                }
                .setNegativeButton(R.string.ok, null)
                .show()
            return
        }
        when {
            zoom < reliefSource.minZoom ->
                Toast.makeText(this, "Relief se prikaže pri večji približavi.", Toast.LENGTH_LONG).show()
            reliefSource == ReliefSource.CLSS && zoom < ClssRelief.FETCH_MIN_ZOOM ->
                Toast.makeText(this, R.string.clss_zoom_hint, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Pulls every CLSS sheet covering [box] so the relief is there without a signal.
     *
     * Sheets already held are skipped rather than refetched, which is what makes widening
     * an area cheap: only the new edge is downloaded.
     */
    private fun downloadClssArea(box: BoundingBox, areaName: String) {
        val missing = ClssSheets.sheetsIn(box).filterNot { (e, n) -> ClssSheets.isHeld(this, e, n) }
        if (missing.isEmpty()) {
            Toast.makeText(this, "CLSS relief za to območje je že shranjen.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Prenesi CLSS relief: $areaName")
            .setMessage(
                "%d listov po 1 km², približno %d MB.\n\nZunaj obsega skeniranja se listi preskočijo."
                    .format(missing.size, missing.size)
            )
            .setPositiveButton(R.string.ok) { _, _ -> runClssDownload(missing, areaName) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runClssDownload(sheets: List<Pair<Int, Int>>, areaName: String) {
        var cancelled = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("Prenašam CLSS relief…")
            .setMessage("0 / ${sheets.size}")
            .setNegativeButton(R.string.cancel) { _, _ -> cancelled = true }
            .setCancelable(false)
            .create()
        dialog.show()

        Thread {
            var done = 0
            var stored = 0
            for ((e, n) in sheets) {
                if (cancelled) break
                if (ClssSheets.sheet(this, e, n, allowFetch = true) != null) stored++
                done++
                ui.post { dialog.setMessage("$done / ${sheets.size}") }
            }
            ui.post {
                dialog.dismiss()
                b.map.invalidate()
                Toast.makeText(
                    this,
                    if (cancelled) "Prenos prekinjen — shranjenih listov: $stored"
                    else "$areaName: shranjenih listov CLSS: $stored",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun nightFilterMatrix(): ColorMatrix? =
        if (nightMode) MapFilters.night(1f) else null

    /**
     * Pushes the night tint to the layers that carry their own paint.
     *
     * The relief is tinted only when it stands alone; blended over a map it inherits the
     * tint from the base underneath, and applying it twice would drive the result past red
     * into unreadable.
     */
    private fun applyNightTint() {
        val night = nightFilterMatrix()
        hillshadeOverlay?.nightMatrix = night
        customOverlay?.nightMatrix = night
        b.map.setBackgroundColor(if (nightMode) Color.rgb(26, 10, 8) else Color.WHITE)

        // The panel is the brightest thing on screen once the map is dimmed, so it has to
        // come down with it — a white readout would undo the dark adaptation the rest of
        // the mode is protecting.
        b.txtCoords.setTextColor(if (nightMode) Color.rgb(255, 150, 110) else Color.WHITE)
        b.txtStatus.setTextColor(
            if (nightMode) Color.argb(200, 220, 120, 90) else Color.argb(179, 255, 255, 255)
        )
    }

    // ------------------------------------------------------------------ custom maps

    private fun loadCustomOverlay() {
        val id = prefs.getString(KEY_ACTIVE_MAP, null)
        // Nothing changed since the last time: keep the bitmap we already decoded rather
        // than paying for it again on every return to this screen.
        if (id != null && id == activeCustomMap?.id && customOverlay?.bitmap != null) return

        // A calibrated sheet can be ~13 MB decoded, and this runs on every onResume, so the
        // previous one has to be released explicitly rather than left for the collector.
        customOverlay?.bitmap?.recycle()
        customOverlay = null
        activeCustomMap = null
        customMapBounds = null
        if (id == null) return

        val map = customStore.load().firstOrNull { it.id == id && it.calibrated } ?: return
        val bmp = BitmapUtils.loadScaled(File(map.imagePath)) ?: return

        activeCustomMap = map
        customOverlay = CalibratedMapOverlay(bmp, map.matrix).apply { opacity = map.opacity }
        customMapBounds = boundsOf(map.matrix, bmp.width, bmp.height)
    }

    /** Geographic extent of a calibrated image, from its four transformed corners. */
    private fun boundsOf(matrix: DoubleArray, w: Int, h: Int): BoundingBox? {
        val corners = arrayOf(
            doubleArrayOf(0.0, 0.0), doubleArrayOf(w.toDouble(), 0.0),
            doubleArrayOf(w.toDouble(), h.toDouble()), doubleArrayOf(0.0, h.toDouble())
        )
        var north = -90.0; var south = 90.0; var east = -180.0; var west = 180.0
        for (c in corners) {
            val m = Transform2D.apply(matrix, c[0], c[1])
            if (m[0].isNaN() || m[1].isNaN()) return null
            val p = GeoMath.fromMercator(m[0], m[1])
            north = maxOf(north, p.latitude); south = minOf(south, p.latitude)
            east = maxOf(east, p.longitude); west = minOf(west, p.longitude)
        }
        return BoundingBox(north, east, south, west)
    }

    private fun zoomToCustomMap() {
        val box = customMapBounds
        if (box == null) {
            Toast.makeText(this, "Ni aktivne lastne karte.", Toast.LENGTH_SHORT).show()
            return
        }
        locationOverlay?.disableFollowLocation()
        b.map.zoomToBoundingBox(box, true, dp(24))
    }

    // ------------------------------------------------------------------ waypoints & course

    private fun reloadWaypointMarkers() {
        waypointMarkers.clear()
        for (w in waypointStore.load()) {
            waypointMarkers.add(Marker(b.map).apply {
                position = GeoPoint(w.lat, w.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = w.name
                snippet = CoordinateSystems.formatDecimal(w.lat, w.lon)
                setOnMarkerClickListener { _, _ ->
                    target = if (target?.id == w.id) null else w
                    updateReadout()
                    Toast.makeText(
                        this@MainActivity,
                        if (target != null) "Cilj: ${w.name}" else "Cilj odstranjen",
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            })
        }
    }

    private fun reloadCourseOverlays() {
        controlMarkers.clear()
        courseLine?.setPoints(emptyList())
        val course = CourseRunner.course ?: return

        courseLine?.setPoints(course.controls.map { it.point() })
        course.controls.forEachIndexed { i, c ->
            controlMarkers.add(Marker(b.map).apply {
                position = c.point()
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = c.name
                // Reached controls stay on the map but faded, so the run reads as a sequence
                // rather than a scatter of identical circles.
                alpha = if (i < CourseRunner.index) 0.35f else 1f
                icon = ContextCompat.getDrawable(
                    this@MainActivity,
                    if (i == CourseRunner.index) R.drawable.ic_control_active else R.drawable.ic_control
                )
            })
        }
    }

    /**
     * Draws the saved tracks the user has picked out, in a colour of their own.
     *
     * Deliberately not the recording red: a line you are laying down now and a line
     * somebody else walked last year mean opposite things when you are standing on a
     * junction deciding which way to go.
     */
    private fun reloadShownTracks() {
        shownTrackLines.clear()
        val shown = prefs.getStringSet(KEY_SHOWN_TRACKS, emptySet()).orEmpty()
        if (shown.isEmpty()) return
        val recording = TrackRecorder.active?.id
        for (t in trackStore.load()) {
            if (t.id !in shown || t.id == recording || t.points.size < 2) continue
            shownTrackLines.add(Polyline(b.map).apply {
                outlinePaint.color = Color.parseColor("#1565C0")
                outlinePaint.strokeWidth = resources.displayMetrics.density * 3f
                outlinePaint.isAntiAlias = true
                title = t.name
                setPoints(t.points.map { GeoPoint(it.lat, it.lon) })
            })
        }
    }

    // ------------------------------------------------------------------ GPX import

    /**
     * Takes in a GPX file whole.
     *
     * A GPX can hold three quite different things at once, and which of them you actually
     * wanted depends on where the file came from — a planner's route, a friend's recorded
     * walk, a list of springs. So the file is read first and its contents offered, rather
     * than guessing and quietly importing the wrong part of it.
     */
    private fun importGpxFile(uri: Uri) {
        val contents = try {
            contentResolver.openInputStream(uri)?.use { GpxIo.read(it) }
        } catch (e: Exception) {
            Toast.makeText(this, "Uvoz ni uspel: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        if (contents == null || contents.isEmpty) {
            Toast.makeText(this, "V datoteki ni točk, sledi ali prog.", Toast.LENGTH_LONG).show()
            return
        }

        // Only what the file actually holds is offered, so the choice is never a list of
        // mostly-greyed-out things that cannot be picked. Each entry carries its own import
        // and its own extent, so nothing here depends on the order they were built in.
        val options = mutableListOf<ImportOption>()
        if (contents.waypoints.isNotEmpty()) {
            options.add(
                ImportOption(
                    label = "Točke: ${contents.waypoints.size}",
                    extent = contents.waypoints.map { GeoPoint(it.lat, it.lon) },
                    apply = { importWaypoints(contents.waypoints) }
                )
            )
        }
        if (contents.tracks.isNotEmpty()) {
            val points = contents.tracks.sumOf { it.points.size }
            options.add(
                ImportOption(
                    label = "Sledi: ${contents.tracks.size}  ($points točk)",
                    extent = contents.tracks.flatMap { t -> t.points.map { GeoPoint(it.lat, it.lon) } },
                    apply = { importTracks(contents.tracks) }
                )
            )
        }
        if (contents.routes.isNotEmpty()) {
            options.add(
                ImportOption(
                    label = "Proge: ${contents.routes.size}",
                    extent = contents.routes.flatMap { c -> c.controls.map { it.point() } },
                    apply = { importRoutes(contents.routes) }
                )
            )
        }

        val checked = BooleanArray(options.size) { true }
        AlertDialog.Builder(this)
            .setTitle(R.string.import_gpx)
            .setMultiChoiceItems(options.map { it.label }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.ok) { _, _ ->
                val chosen = options.filterIndexed { i, _ -> checked[i] }
                if (chosen.isEmpty()) return@setPositiveButton
                val done = chosen.map { it.apply() }

                reloadWaypointMarkers()
                reloadShownTracks()
                rebuildOverlays()
                updateReadout()
                frame(chosen.flatMap { it.extent })
                Toast.makeText(this, "Uvoženo — ${done.joinToString(", ")}", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** One offerable part of a GPX file: what to call it, what it covers, how to take it in. */
    private class ImportOption(
        val label: String,
        val extent: List<GeoPoint>,
        val apply: () -> String
    )

    private fun importWaypoints(imported: List<Waypoint>): String {
        val all = waypointStore.load()
        all.addAll(imported)
        waypointStore.save(all)
        return "točk: ${imported.size}"
    }

    /**
     * Imported tracks are shown straight away. Importing one is a request to look at it;
     * having to then find it in a list and switch it on would be a step with no decision in it.
     */
    private fun importTracks(imported: List<Track>): String {
        imported.forEach { trackStore.upsert(it) }
        val shown = prefs.getStringSet(KEY_SHOWN_TRACKS, emptySet()).orEmpty().toMutableSet()
        shown.addAll(imported.map { it.id })
        prefs.edit().putStringSet(KEY_SHOWN_TRACKS, shown).apply()
        return "sledi: ${imported.size}"
    }

    private fun importRoutes(imported: List<Course>): String {
        val store = CourseStore(this)
        imported.forEach { store.upsert(it) }
        return "prog: ${imported.size}"
    }

    /**
     * Frames what just arrived, since an imported file is rarely over the part of the
     * country the map happens to be showing. Following the GPS is switched off first, or
     * the next fix would immediately pull the view back again.
     */
    private fun frame(points: List<GeoPoint>) {
        if (points.isEmpty()) return
        locationOverlay?.disableFollowLocation()
        if (points.size < 2) {
            b.map.controller.animateTo(points.first())
            return
        }
        b.map.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(points), true, dp(32))
    }

    private fun addWaypointHere() {
        val fix = locationOverlay?.myLocation
        val p = fix ?: b.map.mapCenter.let { GeoPoint(it.latitude, it.longitude) }
        promptWaypoint(p, fromGps = fix != null)
    }

    private fun promptWaypoint(p: GeoPoint, fromGps: Boolean = false) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText("Točka ${waypointStore.load().size + 1}")
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(if (fromGps) "Shrani trenutno lokacijo" else "Nova točka")
            .setMessage(CoordinateSystems.formatDecimal(p.latitude, p.longitude))
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                waypointStore.add(
                    Waypoint(
                        name = input.text.toString().ifBlank { "Točka" },
                        lat = p.latitude, lon = p.longitude,
                        elevation = locationOverlay?.lastFix?.altitude
                    )
                )
                reloadWaypointMarkers()
                rebuildOverlays()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ recording

    private fun toggleRecording() {
        if (TrackRecorder.isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermission()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        TrackRecorder.begin("Sled ${trackStore.load().size + 1}")
        ContextCompat.startForegroundService(this, Intent(this, TrackRecordingService::class.java))
        applyKeepScreenOn()
        Toast.makeText(this, "Snemanje se je začelo.", Toast.LENGTH_SHORT).show()
        updateReadout()
    }

    private fun stopRecording() {
        val finished = TrackRecorder.finish()
        stopService(Intent(this, TrackRecordingService::class.java))
        applyKeepScreenOn()
        if (finished == null || finished.points.size < 2) {
            Toast.makeText(this, "Sled je prekratka in ni bila shranjena.", Toast.LENGTH_LONG).show()
            finished?.let { trackStore.delete(it.id) }
        } else {
            trackStore.upsert(finished)
            Toast.makeText(
                this,
                "Shranjeno: %.2f km v %d min".format(
                    finished.distanceMetres() / 1000, finished.durationMillis() / 60000
                ),
                Toast.LENGTH_LONG
            ).show()
        }
        trackLine?.setPoints(emptyList())
        rebuildOverlays()
        updateReadout()
    }

    /** Stops the display sleeping mid-run, when the map is the thing being used. */
    private fun applyKeepScreenOn() {
        if (TrackRecorder.isRecording || CourseRunner.isRunning) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ------------------------------------------------------------------ menu

    /**
     * Menu entries are paired with their action rather than matched back by label, so adding
     * an item cannot silently rewire an existing one.
     */
    private fun showMenu() {
        val entries = mutableListOf<Pair<String, () -> Unit>>(
            getString(R.string.custom_maps) to { startActivity(Intent(this, CustomMapsActivity::class.java)) },
            getString(R.string.waypoints) to { startActivity(Intent(this, WaypointsActivity::class.java)) },
            getString(R.string.tracks) to { startActivity(Intent(this, TracksActivity::class.java)) },
            getString(R.string.courses) to { courseLauncher.launch(Intent(this, CoursesActivity::class.java)) },
            getString(R.string.import_gpx_all) to { importGpx.launch("*/*") },
            (if (TrackRecorder.isRecording) getString(R.string.stop_recording)
             else getString(R.string.start_recording)) to { toggleRecording() },
            (getString(R.string.rotate_map) + if (rotateWithHeading) "  ✓" else "") to { toggleRotation() }
        )
        if (activeCustomMap != null) {
            entries.add(getString(R.string.zoom_to_map) to { zoomToCustomMap() })
            entries.add(getString(R.string.download_custom_area) to { downloadCustomMapArea() })
        }
        if (CourseRunner.isRunning) {
            entries.add(getString(R.string.stop_course) to {
                CourseRunner.stop(); reloadCourseOverlays(); rebuildOverlays()
                applyKeepScreenOn(); updateReadout()
            })
        }
        if (hillshadeOn && reliefSource == ReliefSource.CLSS) {
            entries.add(getString(R.string.download_clss) to {
                downloadClssArea(b.map.boundingBox, "Vidno območje")
            })
        }
        entries.add(getString(R.string.download_area) to { showOfflineDialog() })
        entries.add(getString(R.string.offline_manager) to {
            startActivity(Intent(this, OfflineActivity::class.java))
        })
        entries.add(getString(R.string.help) to { startActivity(Intent(this, HelpActivity::class.java)) })
        entries.add("O aplikaciji" to { showAbout() })

        AlertDialog.Builder(this)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .show()
    }

    private fun toggleRotation() {
        rotateWithHeading = !rotateWithHeading
        prefs.edit().putBoolean(KEY_ROTATE, rotateWithHeading).apply()
        if (!rotateWithHeading) {
            b.map.setMapOrientation(0f)
            appliedRotation = 0f
        }
        Toast.makeText(
            this,
            if (rotateWithHeading) "Karta se vrti po smeri hoje." else "Sever je zgoraj.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showOfflineDialog() {
        if (baseBrightness == 0) {
            Toast.makeText(this, "Najprej vklopite podlago.", Toast.LENGTH_SHORT).show()
            return
        }
        promptDownload(b.map.boundingBox, "Vidno območje")
    }

    /**
     * Downloads exactly the ground a calibrated sheet covers — the area you will actually be
     * navigating on, and the one place a missing tile matters most.
     */
    private fun downloadCustomMapArea() {
        val box = customMapBounds
        val map = activeCustomMap
        if (box == null || map == null) {
            Toast.makeText(this, "Ni aktivne lastne karte.", Toast.LENGTH_SHORT).show()
            return
        }
        promptDownload(box, map.name)
    }

    private fun promptDownload(box: BoundingBox, areaName: String) {
        val zoomMin = b.map.zoomLevelDouble.roundToInt().coerceIn(9, 15)
        val choices = intArrayOf(zoomMin + 1, zoomMin + 2, zoomMin + 3)
        val withRelief = hillshadeOn

        // CLSS relief is not counted here: it is not tiles, and its size is reported in
        // sheets when that download is offered.
        val tileSources = if (withRelief && reliefSource == ReliefSource.GURS) 2 else 1

        val labels = choices.map { z ->
            val zMax = z.coerceAtMost(16)
            val (tiles, bytes) = OfflineDownloader.estimate(box, zoomMin, zMax, tileSources)
            "Do z%d — %d ploščic, ~%d MB".format(zMax, tiles, bytes / (1024 * 1024))
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Prenesi: $areaName")
            .setMessage(
                when {
                    withRelief && reliefSource == ReliefSource.CLSS ->
                        "Prenesel bom podlago, nato pa še liste CLSS reliefa."
                    withRelief -> "Prenesel bom podlago in senčenje reliefa."
                    else -> "Prenesel bom samo podlago. Za relief ga najprej vklopite v Slojih."
                }
            )
            .setItems(labels) { _, which ->
                startDownload(box, areaName, zoomMin, choices[which].coerceAtMost(16), withRelief)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startDownload(
        box: BoundingBox,
        areaName: String,
        zMin: Int,
        zMax: Int,
        withRelief: Boolean
    ) {
        val sources = mutableListOf(currentLayer.label to currentLayer.source)
        // Only the WMS relief can be pre-fetched as tiles. CLSS is stored as whole sheets
        // instead, so it follows once the base map is down rather than riding along.
        if (withRelief && reliefSource == ReliefSource.GURS) {
            sources.add("relief" to MapLayers.HILLSHADE)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Prenašam…").setMessage("Pripravljam.").setCancelable(false).create()
        dialog.show()

        val (estimatedTiles, _) = OfflineDownloader.estimate(box, zMin, zMax, sources.size)
        OfflineDownloader.download(this, box, zMin, zMax, sources, object : OfflineDownloader.Listener {
            override fun onProgress(sourceLabel: String, done: Int, total: Int) {
                dialog.setMessage(
                    if (total > 0) "$sourceLabel: $total ploščic." else "$sourceLabel: preneseno $done"
                )
            }
            override fun onFinished() {
                dialog.dismiss()
                OfflinePackStore(this@MainActivity).add(
                    OfflinePack(
                        name = areaName,
                        north = box.latNorth, east = box.lonEast,
                        south = box.latSouth, west = box.lonWest,
                        zoomMin = zMin, zoomMax = zMax,
                        layerLabel = currentLayer.label,
                        tileCount = estimatedTiles,
                        includesRelief = withRelief
                    )
                )
                Toast.makeText(this@MainActivity, "Prenos končan.", Toast.LENGTH_LONG).show()
                if (withRelief && reliefSource == ReliefSource.CLSS) {
                    downloadClssArea(box, areaName)
                }
            }
            override fun onFailed(reason: String) {
                dialog.dismiss()
                Toast.makeText(this@MainActivity, reason, Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(
                "Kartografske podlage: © Geodetska uprava RS, CC BY 4.0.\n" +
                    "Senčenje reliefa: lidar 2011–2014 (državna storitev) ali " +
                    "ciklično lasersko skeniranje 2023–25 (clss.si). Oboje © GURS, CC BY 4.0.\n" +
                    "OpenTopoMap: CC-BY-SA, podatki © OpenStreetMap.\n\n" +
                    "DTK 25 se javno ne streže več — uvozite jo kot lastno karto.\n\n" +
                    "Dolg pritisk na karto doda točko."
            )
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------------ live updates

    private fun cycleCoordFormat() {
        coordFormat = (coordFormat + 1) % 5
        prefs.edit().putInt(KEY_COORD_FORMAT, coordFormat).apply()
        updateReadout()
    }

    /** Keeps the breadcrumb and course state in step with the recorder, once a second. */
    private fun updateLiveOverlays() {
        var needsRebuild = false

        val active = TrackRecorder.active
        if (active != null) {
            val line = trackLine
            if (line != null && line.actualPoints.size != active.points.size) {
                val wasEmpty = line.actualPoints.isEmpty()
                line.setPoints(active.points.map { GeoPoint(it.lat, it.lon) })
                if (wasEmpty) needsRebuild = true
            }
        }

        val here = locationOverlay?.myLocation
        if (here != null && CourseRunner.isRunning && !CourseRunner.isFinished) {
            CourseRunner.checkPunch(here)?.let { reached ->
                buzz()
                val done = CourseRunner.isFinished
                Toast.makeText(
                    this,
                    if (done) "Cilj! Čas ${CourseRunner.formatElapsed()}"
                    else "${reached.name} ob ${CourseRunner.formatElapsed(CourseRunner.splits.last())}",
                    Toast.LENGTH_LONG
                ).show()
                reloadCourseOverlays()
                needsRebuild = true
            }
        }
        if (needsRebuild) rebuildOverlays() else b.map.invalidate()
    }

    private fun buzz() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            v.vibrate(VibrationEffect.createOneShot(220, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun updateReadout() {
        val fix = locationOverlay?.lastFix
        val gp = locationOverlay?.myLocation
        val showing = gp ?: GeoPoint(b.map.mapCenter.latitude, b.map.mapCenter.longitude)

        b.txtCoords.text = when (coordFormat) {
            0 -> CoordinateSystems.formatDecimal(showing.latitude, showing.longitude)
            1 -> CoordinateSystems.toD96TM(showing.latitude, showing.longitude)
                .let { "E %.0f   N %.0f".format(it.easting, it.northing) }
            2 -> CoordinateSystems.toMgrs(showing.latitude, showing.longitude)
            3 -> CoordinateSystems.formatDegMin(showing.latitude, showing.longitude)
            else -> CoordinateSystems.toD48GK(showing.latitude, showing.longitude)
                .let { "Y %.0f   X %.0f".format(it.easting, it.northing) }
        }
        val systemName = when (coordFormat) {
            0 -> "WGS84"; 1 -> "D96/TM"; 2 -> "MGRS"; 3 -> "WGS84 °′"; else -> "D48/GK"
        }

        val bits = mutableListOf(systemName)
        if (gp == null) bits.add("sredina karte — ni signala")
        fix?.let {
            bits.add("±%.0f m".format(it.accuracy))
            if (it.hasAltitude()) bits.add("%.0f m n.v.".format(it.altitude))
        }
        heading?.let { bits.add("smer %.0f°".format(it)) }
        bits.add(activeLayerLabel())
        // Naming the scan rather than just "relief": the two look different on the ground,
        // and knowing which one is drawn is the difference between trusting a feature and
        // wondering whether it is simply out of date.
        if (hillshadeOn) bits.add("relief ${reliefSource.label}")
        if (nightMode) bits.add("noč")
        activeCustomMap?.let { bits.add("+ ${it.name}") }
        b.txtStatus.text = bits.joinToString("  ·  ")

        b.txtTarget.text = buildGuidance(gp)
        b.txtTarget.visibility = if (b.txtTarget.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Names the product actually on screen, which changes with zoom on the composite layer. */
    private fun activeLayerLabel(): String {
        if (baseBrightness == 0) return "brez podlage"
        val name = if (currentLayer.id == MapLayers.TOPO.id) {
            GursTopoTileSource.sourceLabelFor(b.map.zoomLevelDouble.toInt())
        } else currentLayer.label
        val dim = if (baseBrightness < 100) " ($baseBrightness %)" else ""
        val auto = if (sharpenedFrom != null) " ↑" else ""
        return name + dim + auto
    }

    /**
     * The guidance line, in priority order: a running course beats a manually chosen target,
     * because if a course is under way that is unambiguously the task in hand.
     */
    private fun buildGuidance(gp: GeoPoint?): String {
        if (TrackRecorder.isRecording) {
            val t = TrackRecorder.active
            if (t != null && CourseRunner.course == null) {
                return "● snemam  %.2f km · %d min".format(
                    t.distanceMetres() / 1000, t.durationMillis() / 60000
                )
            }
        }
        val course = CourseRunner.course
        if (course != null) {
            if (CourseRunner.isFinished) {
                return "Proga končana · čas ${CourseRunner.formatElapsed(CourseRunner.splits.lastOrNull() ?: 0)}"
            }
            val c = CourseRunner.current ?: return ""
            val n = "${CourseRunner.index + 1}/${course.controls.size}"
            if (gp == null) return "→ $n ${c.name} · čakam na signal"
            val dist = GeoMath.distance(gp, c.point())
            val brg = GeoMath.bearing(gp, c.point())
            val rel = heading?.let { (brg - it + 360) % 360 }
            return buildString {
                append("→ $n ${c.name}: ")
                append(if (dist < 1000) "%.0f m".format(dist) else "%.2f km".format(dist / 1000))
                append("  azimut %.0f°".format(brg))
                rel?.let { append("  (obrni %.0f°)".format(it)) }
                append("  ·  ${CourseRunner.formatElapsed()}")
            }
        }
        val t = target ?: return ""
        if (gp == null) return ""
        val tp = GeoPoint(t.lat, t.lon)
        val dist = GeoMath.distance(gp, tp)
        val brg = GeoMath.bearing(gp, tp)
        val rel = heading?.let { (brg - it + 360) % 360 }
        return buildString {
            append("→ ${t.name}: ")
            append(if (dist < 1000) "%.0f m".format(dist) else "%.2f km".format(dist / 1000))
            append("  azimut %.0f°".format(brg))
            rel?.let { append("  (obrni %.0f°)".format(it)) }
        }
    }

    // ------------------------------------------------------------------ location & sensors

    private fun requestLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED) enableLocation()
        else permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    private fun enableLocation() {
        locationOverlay?.apply {
            enableMyLocation()
            runOnFirstFix { runOnUiThread { updateReadout() } }
        }
    }

    private fun centreOnLocation() {
        val overlay = locationOverlay ?: return
        val p = overlay.myLocation
        if (p == null) {
            Toast.makeText(this, "Čakam na signal GPS…", Toast.LENGTH_SHORT).show()
            return
        }
        if (overlay.isFollowLocationEnabled) {
            overlay.disableFollowLocation()
            Toast.makeText(this, "Sledenje izklopljeno", Toast.LENGTH_SHORT).show()
        } else {
            overlay.enableFollowLocation()
            b.map.controller.animateTo(p)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val h = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
        heading = h
        if (!rotateWithHeading) return
        // Re-orienting the map forces a full redraw, so only act on a real change in heading
        // rather than on every sensor sample.
        var delta = abs(h - appliedRotation)
        if (delta > 180f) delta = 360f - delta
        if (delta >= ROTATION_STEP_DEGREES) {
            appliedRotation = h
            b.map.setMapOrientation(-h)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ------------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        Configuration.getInstance().load(this, prefs)
        b.map.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        // Any of these can have changed while the user was in another screen.
        loadCustomOverlay()
        reloadWaypointMarkers()
        reloadShownTracks()
        reloadCourseOverlays()
        TrackRecorder.active?.let { t ->
            trackLine?.setPoints(t.points.map { GeoPoint(it.lat, it.lon) })
        }
        applyHillshade()
        applyKeepScreenOn()
        rebuildOverlays()
        ui.post(refreshTick)
    }

    override fun onPause() {
        super.onPause()
        Configuration.getInstance().save(this, prefs)
        b.map.onPause()
        sensorManager.unregisterListener(this)
        ui.removeCallbacks(refreshTick)
        prefs.edit()
            .putFloat(KEY_LAT, b.map.mapCenter.latitude.toFloat())
            .putFloat(KEY_LON, b.map.mapCenter.longitude.toFloat())
            .putFloat(KEY_ZOOM, b.map.zoomLevelDouble.toFloat())
            .apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        b.map.onDetach()
    }

    companion object {
        const val KEY_LAYER = "layer_id"
        const val KEY_BASE_BRIGHTNESS = "base_brightness"
        const val KEY_AUTO_SHARPEN = "auto_sharpen"
        const val KEY_ACTIVE_MAP = "active_map_id"
        const val KEY_COORD_FORMAT = "coord_format"
        const val KEY_HILLSHADE = "hillshade_on"
        const val KEY_RELIEF_SOURCE = "relief_source"
        /** Ids of saved tracks drawn on the map, shared with the tracks screen. */
        const val KEY_SHOWN_TRACKS = "shown_track_ids"
        const val KEY_CLSS_EXPLAINED = "clss_explained"
        const val KEY_HILLSHADE_STRENGTH = "hillshade_strength"
        const val KEY_HILLSHADE_CONTRAST = "hillshade_contrast"
        const val KEY_HILLSHADE_BRIGHTNESS = "hillshade_brightness"
        const val KEY_MAP_SATURATION = "map_saturation"
        const val KEY_NIGHT_MODE = "night_mode"
        const val KEY_ROTATE = "rotate_with_heading"
        const val KEY_LAT = "last_lat"
        const val KEY_LON = "last_lon"
        const val KEY_ZOOM = "last_zoom"

        private const val ROTATION_STEP_DEGREES = 2f
        private const val NO_BASE_TAG = -1
    }
}
