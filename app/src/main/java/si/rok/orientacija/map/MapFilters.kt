package si.rok.orientacija.map

import android.graphics.ColorMatrix

/**
 * Colour transforms shared by every map layer.
 *
 * Each is expressed as a [ColorMatrix] so they can be composed — fade, then desaturate, then
 * night tint — and handed to a layer as a single filter rather than stacking passes.
 */
object MapFilters {

    /**
     * Red-shifted monochrome for use with a headlamp.
     *
     * Human night vision is least disturbed by deep red, so the map is first collapsed to
     * luminance and then re-tinted, rather than merely dimmed. Simply darkening leaves the
     * blues and greens that cost you dark adaptation; converting to luminance first means a
     * green forest and a blue stream both come through as legible red-grey rather than as
     * two different kinds of near-black.
     */
    fun night(intensity: Float): ColorMatrix {
        val i = intensity.coerceIn(0f, 1f)
        // Rec. 601 luma weights: perceptual brightness, not a flat channel average.
        val lr = 0.299f; val lg = 0.587f; val lb = 0.114f
        // Overall level drops as intensity rises, so a full-strength night map is dim as
        // well as red.
        val level = 1f - 0.35f * i
        val r = level
        val g = level * (1f - 0.72f * i)
        val b = level * (1f - 0.90f * i)
        return ColorMatrix(
            floatArrayOf(
                lr * r, lg * r, lb * r, 0f, 0f,
                lr * g, lg * g, lb * g, 0f, 0f,
                lr * b, lg * b, lb * b, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    /** Fades toward white. Used for map visibility: pales the base rather than darkening it. */
    fun fadeToWhite(visibility: Float): ColorMatrix {
        val f = visibility.coerceIn(0f, 1f)
        val lift = (1f - f) * 255f
        return ColorMatrix(
            floatArrayOf(
                f, 0f, 0f, 0f, lift,
                0f, f, 0f, 0f, lift,
                0f, 0f, f, 0f, lift,
                0f, 0f, 0f, 1f, 0f
            )
        )
    }

    fun saturation(amount: Float): ColorMatrix =
        ColorMatrix().apply { setSaturation(amount.coerceIn(0f, 2f)) }

    /** Applies [first], then [second]. */
    fun compose(first: ColorMatrix, second: ColorMatrix): ColorMatrix =
        ColorMatrix(first).apply { postConcat(second) }
}
