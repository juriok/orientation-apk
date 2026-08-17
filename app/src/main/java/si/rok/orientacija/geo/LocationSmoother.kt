package si.rok.orientacija.geo

import android.location.Location
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Steadies a stream of GPS fixes.
 *
 * A raw fix moves every second even when the phone is lying on a rock: the receiver solves
 * a fresh position each time and the answer wanders by several metres. Drawn straight to
 * the map that reads as the marker twitching around, and it is worst exactly when you are
 * standing still trying to work out where you are.
 *
 * Two separate problems, handled separately.
 *
 * **Wrong fixes** are thrown away rather than smoothed. A fix derived from cell towers or
 * wifi can be a kilometre out, and averaging it in would drag the position off the path
 * instead of leaving it alone. Anything much coarser than what we already have is rejected
 * while our own position is still fresh, and so is anything implying a speed a person
 * cannot manage — unless it keeps happening, which means the jump was real and the filter
 * is the thing that is wrong.
 *
 * **Noisy fixes** are merged with a Kalman filter on position. The receiver's own accuracy
 * estimate is the measurement error, and the expected movement since the last fix is the
 * process noise, so the filter tightens up when you stand still and loosens when you move:
 *
 *  * standing, a 5 m fix updates the position by about a tenth of each wobble, so the
 *    marker sits still instead of dancing;
 *  * running at 3 m/s, the same fix updates by nearly half, and the position trails the
 *    truth by a few metres — comfortably inside the accuracy of the fix itself.
 *
 * Nothing here invents precision. The filter reports the variance it actually believes in,
 * which is why the accuracy readout gets better as fixes accumulate and worse the moment
 * they stop.
 */
class LocationSmoother {

    private var lat = 0.0
    private var lon = 0.0

    /** Estimate variance in square metres; negative until the first fix. */
    private var variance = -1.0

    /**
     * Monotonic clock of the last accepted fix. Wall-clock time is unusable for this: it
     * jumps when the network corrects it, and fixes from two providers do not agree on it.
     */
    private var lastElapsedNanos = 0L

    private var consecutiveOutliers = 0

    val hasFix: Boolean get() = variance >= 0

    /** Best estimate of the current accuracy, in metres. */
    val accuracyMetres: Float get() = if (variance < 0) 0f else sqrt(variance).toFloat()

    fun reset() {
        variance = -1.0
        lastElapsedNanos = 0L
        consecutiveOutliers = 0
    }

    /**
     * Takes a raw fix and returns the steadied position, or null when the fix was rejected
     * and the previous position still stands.
     */
    fun accept(fix: Location): Location? {
        val accuracy = if (fix.hasAccuracy() && fix.accuracy > 0f) fix.accuracy.toDouble()
                       else ASSUMED_ACCURACY
        if (accuracy > UNUSABLE_ACCURACY && hasFix) return null

        val now = fix.elapsedRealtimeNanos
        val secondsSinceLast =
            if (lastElapsedNanos == 0L || now <= lastElapsedNanos) 0.0
            else (now - lastElapsedNanos) / 1_000_000_000.0

        if (!hasFix) return startAt(fix, accuracy, now)

        // Out of order. Two providers are running and their fixes interleave; an older one
        // has nothing to add to a newer one already folded in.
        if (now < lastElapsedNanos) return null

        // Much coarser than what we hold, and what we hold is still fresh. This is the rule
        // that keeps a cell-tower fix from throwing the marker across the valley.
        val held = sqrt(variance)
        if (accuracy > held * COARSE_RATIO && accuracy > COARSE_FLOOR &&
            secondsSinceLast < STALE_AFTER_SECONDS
        ) {
            return null
        }

        val moved = distanceTo(fix.latitude, fix.longitude)
        val plausible = secondsSinceLast * MAX_SPEED + JUMP_ALLOWANCE * (accuracy + held)
        if (moved > plausible) {
            // Once is noise. Repeatedly means the position really did move — a fix regained
            // after a tunnel, or the phone carried somewhere while it had nothing — and the
            // filter has to give up its old belief rather than fight the evidence forever.
            if (++consecutiveOutliers < OUTLIERS_BEFORE_RESET) return null
            return startAt(fix, accuracy, now)
        }
        consecutiveOutliers = 0

        // Process noise: how far the position could have drifted since the last fix. Taken
        // from the receiver's own speed where it has one, so the filter is loose while
        // moving and tight while still, with a floor so it never stops adapting entirely.
        //
        // A reported speed of exactly zero is believed, and is the whole point: standing
        // still is when the filter should tighten hardest. Only a fix that carries no speed
        // at all falls back to assuming a walk. Treating a reported zero as "unknown"
        // instead loosened the filter precisely when it should have clamped down, and cost
        // a third of the achievable accuracy at a standstill.
        val speed = if (fix.hasSpeed()) fix.speed.toDouble() else WALKING_SPEED
        val drift = max(speed, MIN_DRIFT_SPEED)
        variance += drift * drift * secondsSinceLast

        val gain = variance / (variance + accuracy * accuracy)
        lat += gain * (fix.latitude - lat)
        lon += gain * (fix.longitude - lon)
        variance *= (1 - gain)
        lastElapsedNanos = now

        return publish(fix)
    }

    private fun startAt(fix: Location, accuracy: Double, elapsedNanos: Long): Location {
        lat = fix.latitude
        lon = fix.longitude
        variance = accuracy * accuracy
        lastElapsedNanos = elapsedNanos
        consecutiveOutliers = 0
        return publish(fix)
    }

    /**
     * The steadied fix. Speed and bearing are the receiver's own and are not ours to
     * reinterpret, but two fields are corrected here so that every part of the app reads
     * the same numbers:
     *
     *  * **accuracy**, because after filtering it is genuinely no longer the fix's own;
     *  * **altitude**, converted from height above the ellipsoid — which is what the
     *    platform reports — to height above sea level, which is what a map, a summit sign
     *    and a person all mean by altitude. Over Slovenia that is a correction of about
     *    47 m, so it is not a refinement but the difference between right and wrong.
     *
     * Doing it here rather than at each place that reads an altitude is deliberate: this is
     * the single gate every fix in the app passes through, so there is nowhere for an
     * uncorrected value to leak out and disagree with the rest.
     */
    private fun publish(fix: Location): Location = Location(fix).apply {
        latitude = lat
        longitude = lon
        accuracy = accuracyMetres
        if (fix.hasAltitude()) {
            altitude = Geoid.toSeaLevel(fix.altitude, lat, lon)
        }
    }

    private fun distanceTo(otherLat: Double, otherLon: Double): Double {
        val out = FloatArray(1)
        Location.distanceBetween(lat, lon, otherLat, otherLon, out)
        return out[0].toDouble()
    }

    companion object {
        /** Used when a fix carries no accuracy of its own, which is rare and never good. */
        private const val ASSUMED_ACCURACY = 30.0

        /** Beyond this a fix says little more than which town you are in. */
        private const val UNUSABLE_ACCURACY = 500.0

        /** How much worse than the held estimate a fix may be before it is ignored. */
        private const val COARSE_RATIO = 4.0

        /** Below this, "four times worse" is still only a few metres and worth having. */
        private const val COARSE_FLOOR = 40.0

        /** After this long without a fix, take whatever arrives — stale beats nothing. */
        private const val STALE_AFTER_SECONDS = 20.0

        /** 30 m/s is over 100 km/h: faster than this is not a person who has moved. */
        private const val MAX_SPEED = 30.0

        /** A jump is only suspicious once it clears the error bars by this much. */
        private const val JUMP_ALLOWANCE = 3.0

        private const val OUTLIERS_BEFORE_RESET = 4

        /** Assumed movement when the receiver reports no speed. */
        private const val WALKING_SPEED = 1.4

        /** Floor on assumed drift, so a stationary filter can still follow a slow change. */
        private const val MIN_DRIFT_SPEED = 0.6
    }
}
