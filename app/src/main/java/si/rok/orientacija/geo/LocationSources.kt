package si.rok.orientacija.geo

import android.annotation.SuppressLint
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build

/**
 * Which system location providers to listen to.
 *
 * Kept in one place because the map and the track recorder subscribe separately — the
 * recorder keeps running with the activity gone — and a track recorded from a different
 * set of providers than the map is drawing would disagree with the map for no reason the
 * user could ever work out.
 */
object LocationSources {

    /**
     * In preference order: the satellite provider, which is what actually works on a hill;
     * the platform's fused provider from Android 12, which fills gaps under canopy without
     * needing Play Services; and the network provider, which is only ever worth having
     * before the first real fix and is filtered out again once there is anything better.
     */
    fun available(lm: LocationManager): List<String> {
        val wanted = mutableListOf(LocationManager.GPS_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted.add(LocationManager.FUSED_PROVIDER)
        }
        wanted.add(LocationManager.NETWORK_PROVIDER)
        val present = runCatching { lm.allProviders }.getOrDefault(emptyList())
        return wanted.filter { it in present }
    }

    /**
     * Subscribes to one provider, asking for the best fix it can manage.
     *
     * From Android 12 the request can say so outright. Without it the platform is free to
     * trade accuracy for battery — reasonably, for an app checking which city you are in,
     * but not for one you are navigating off. Below 12 there is no such control and the
     * plain call is the whole of what can be asked for.
     *
     * Zero interval and zero distance either way: every fix the receiver produces is
     * another sample for the filter to average the noise out of, and throwing some away to
     * save power would only make the position worse.
     */
    @SuppressLint("MissingPermission")
    fun requestHighAccuracy(
        lm: LocationManager,
        provider: String,
        listener: LocationListener
    ): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val request = LocationRequest.Builder(0L)
                .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                .setMinUpdateIntervalMillis(0L)
                .build()
            lm.requestLocationUpdates(provider, request, { it.run() }, listener)
        } else {
            lm.requestLocationUpdates(provider, 0L, 0f, listener)
        }
    }.isSuccess
}
