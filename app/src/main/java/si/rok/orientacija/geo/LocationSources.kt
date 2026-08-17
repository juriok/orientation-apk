package si.rok.orientacija.geo

import android.location.LocationManager
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
}
