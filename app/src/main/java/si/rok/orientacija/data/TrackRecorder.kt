package si.rok.orientacija.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import si.rok.orientacija.MainActivity
import si.rok.orientacija.R

/**
 * Shared recording state.
 *
 * The service owns the location subscription while the activity owns the map, and both need
 * the same point list. Holding it in one process-wide object keeps them in step without
 * binding ceremony, and the map can simply read the list on its existing refresh tick.
 */
object TrackRecorder {

    @Volatile var active: Track? = null
        private set

    val isRecording: Boolean get() = active != null

    fun begin(name: String): Track {
        val t = Track(name = name)
        active = t
        return t
    }

    /**
     * Appends a fix, rejecting ones that would only add noise.
     *
     * A wildly inaccurate fix drags the drawn line across the map and corrupts the distance
     * total, and duplicate points at a standstill bloat the file for no information.
     */
    fun record(loc: Location): Boolean {
        val t = active ?: return false
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_METRES) return false
        val p = TrackPoint(
            lat = loc.latitude,
            lon = loc.longitude,
            elevation = if (loc.hasAltitude()) loc.altitude else null,
            time = if (loc.time > 0) loc.time else System.currentTimeMillis(),
            accuracy = if (loc.hasAccuracy()) loc.accuracy else 0f
        )
        val last = t.points.lastOrNull()
        if (last != null && Track.haversine(last, p) < MIN_MOVE_METRES) return false
        t.points.add(p)
        return true
    }

    fun finish(): Track? {
        val t = active ?: return null
        t.endedAt = System.currentTimeMillis()
        active = null
        return t
    }

    fun discard() { active = null }

    private const val MAX_ACCURACY_METRES = 50f
    private const val MIN_MOVE_METRES = 3.0
}

/**
 * Keeps location updates flowing while the screen is off or the app is backgrounded.
 *
 * Android stops delivering location to a backgrounded activity within seconds, so without a
 * foreground service a recorded track would simply stop the moment the phone went into a
 * pocket — which is most of any run.
 */
class TrackRecordingService : Service(), LocationListener {

    private var locationManager: LocationManager? = null
    private val store by lazy { TrackStore(this) }
    private var sinceLastSave = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        requestUpdates()
        return START_STICKY
    }

    private fun requestUpdates() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        } catch (e: Exception) {
            // GPS unavailable on this device or disabled; the network provider below may
            // still yield something usable.
        }
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, this)
        } catch (e: Exception) {
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!TrackRecorder.record(location)) return
        // Periodic autosave: if the process is killed mid-run the track survives to the
        // last checkpoint instead of vanishing entirely.
        if (++sinceLastSave >= SAVE_EVERY_N_POINTS) {
            sinceLastSave = 0
            TrackRecorder.active?.let { runCatching { store.upsert(it) } }
        }
    }

    @Deprecated("Required by LocationListener on older API levels")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun stopRecording() {
        TrackRecorder.active?.let { runCatching { store.upsert(it) } }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { locationManager?.removeUpdates(this) }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Snemanje sledi", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Prikaz med snemanjem sledi" }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Snemanje sledi")
            .setContentText("Orientacija beleži vašo pot.")
            .setSmallIcon(R.drawable.ic_my_location)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_STOP = "si.rok.orientacija.STOP_RECORDING"
        private const val CHANNEL_ID = "track_recording"
        private const val NOTIFICATION_ID = 4711
        private const val SAVE_EVERY_N_POINTS = 20
    }
}
