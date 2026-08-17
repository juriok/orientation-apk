package si.rok.orientacija.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import si.rok.orientacija.geo.Geoid
import si.rok.orientacija.geo.LocationSmoother
import si.rok.orientacija.geo.LocationSources

/**
 * Feeds the map a steadied position instead of raw fixes.
 *
 * osmdroid's own provider subscribes to the satellite and network providers at once, with
 * no minimum interval and no minimum distance, and hands every fix straight to the map. On
 * a hill that is mostly fine; in a village it means the marker is periodically thrown a few
 * hundred metres by a fix worked out from cell masts, and even with a clean sky it twitches
 * every second because a receiver simply does not solve the same position twice.
 *
 * This subscribes to the satellite provider, adds the platform's fused provider where the
 * system has one — it fills the gaps under canopy without needing Play Services — and puts
 * everything through [LocationSmoother] before the map ever sees it. The network provider
 * is used only to put something on screen before the first real fix arrives, and is dropped
 * the moment there is anything better.
 */
class SmoothedLocationProvider(context: Context) : IMyLocationProvider, LocationListener {

    private val appContext = context.applicationContext
    private val smoother = LocationSmoother()

    private var locationManager: LocationManager? = null
    private var consumer: IMyLocationConsumer? = null
    private var lastPublished: Location? = null

    override fun startLocationProvider(myLocationConsumer: IMyLocationConsumer?): Boolean {
        consumer = myLocationConsumer
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        locationManager = lm

        var subscribed = false
        for (provider in LocationSources.available(lm)) {
            // Ask for everything the receiver produces and filter here. Letting the platform
            // thin the stream by time or distance would only hand the filter fewer, equally
            // noisy samples to work with.
            subscribed = LocationSources.requestHighAccuracy(lm, provider, this) || subscribed
        }
        if (!subscribed) return false

        // Something on screen immediately, rather than an empty map until the first fix.
        seedFromLastKnown(lm)
        return true
    }

    /**
     * Puts the last position the system already knew on screen, so the map is not blank
     * for the half minute a cold receiver takes to find the sky.
     *
     * Only recent and reasonably accurate ones: yesterday's fix from the other end of the
     * country is not a starting point, it is a lie that takes a while to correct itself,
     * and the map jumping away from it later is precisely the behaviour being fixed here.
     */
    private fun seedFromLastKnown(lm: LocationManager) {
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        val candidates = LocationSources.available(lm)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { it.hasAccuracy() && it.accuracy <= SEED_MAX_ACCURACY }
            .filter { (now - it.elapsedRealtimeNanos) / 1_000_000_000.0 <= SEED_MAX_AGE_SECONDS }

        // The most accurate of them, not merely the most recent: a fresh cell fix is worth
        // less than a satellite fix from a few minutes ago.
        val best = candidates.minByOrNull { it.accuracy } ?: return
        // This one fix does not pass through the filter, so its altitude has to be put on
        // the same footing here — otherwise the readout would show a height 47 m out for
        // the few seconds before the first real fix corrects it.
        publish(
            Location(best).apply {
                if (best.hasAltitude()) {
                    altitude = Geoid.toSeaLevel(best.altitude, best.latitude, best.longitude)
                }
            }
        )
    }

    override fun onLocationChanged(location: Location) {
        smoother.accept(location)?.let { publish(it) }
    }

    private fun publish(location: Location) {
        lastPublished = location
        consumer?.onLocationChanged(location, this)
    }

    override fun stopLocationProvider() {
        runCatching { locationManager?.removeUpdates(this) }
        consumer = null
    }

    override fun getLastKnownLocation(): Location? = lastPublished

    override fun destroy() {
        stopLocationProvider()
        locationManager = null
        lastPublished = null
        smoother.reset()
    }

    // Required by LocationListener; nothing here changes what the filter should do.
    @Deprecated("Required by LocationListener on older API levels")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    /**
     * A provider going away does not invalidate the position we hold — the other provider
     * may still be running — so the filter keeps its state and simply ages out on its own.
     */
    override fun onProviderDisabled(provider: String) = Unit

    companion object {
        /** Older than this and a stored fix says where you were, not where you are. */
        private const val SEED_MAX_AGE_SECONDS = 300.0
        private const val SEED_MAX_ACCURACY = 200f
    }
}
