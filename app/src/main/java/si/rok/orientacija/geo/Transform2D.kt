package si.rok.orientacija.geo

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Planar transform fitting between two sets of corresponding points.
 *
 * This is the heart of custom-map calibration. A photo of a paper map is essentially never
 * taken perpendicular to the sheet, so the mapping from photo pixels to world coordinates is
 * projective (8 DOF), not just scale/rotate/translate (4 DOF). Dragging a photo over the map
 * can only ever produce the 4-DOF version, which is why a corner-to-corner "looks aligned"
 * photo can still be badly wrong in the middle.
 *
 * Given enough control points we fit a full homography. With fewer we degrade deliberately
 * to affine or similarity rather than overfitting an under-determined system.
 *
 * All fitting happens in Hartley-normalised space: photo pixels are O(1e3) while Web Mercator
 * metres are O(1e6), and mixing those magnitudes directly makes the normal equations badly
 * conditioned enough to lose most of the available precision.
 */
object Transform2D {

    enum class Model(val minPoints: Int, val label: String) {
        TRANSLATION(1, "premik"),
        SIMILARITY(2, "podobnostna"),
        AFFINE(3, "afina"),
        HOMOGRAPHY(4, "projektivna")
    }

    /**
     * A fitted 3x3 transform in row-major order, plus the quality of the fit.
     *
     * [rmsError] and [maxError] are in the units of the destination points — metres, when
     * fitting photo pixels to Web Mercator. They are the honest answer to "can I trust this
     * calibration", and the UI surfaces them for exactly that reason.
     */
    class Fit(
        val matrix: DoubleArray,
        val model: Model,
        val rmsError: Double,
        val maxError: Double,
        val pointCount: Int,
        /**
         * Leave-one-out cross-validated RMS, or -1 when there are too few points to compute
         * it. This is the honest error figure: [rmsError] measures how well the transform
         * reproduces the very points it was fitted to, which it can always do well, and on
         * measured trials it understated the true error across the sheet by a factor of
         * three to six. Prefer this when telling the user how far off they might be.
         */
        val cvError: Double = -1.0
    ) {
        fun apply(x: Double, y: Double): DoubleArray = Transform2D.apply(matrix, x, y)
        fun inverse(): DoubleArray? = invert(matrix)

        /** True when the model has as much freedom as there are points, so residuals are 0 by construction. */
        val exactlyDetermined: Boolean get() = pointCount <= model.minPoints
    }

    // ---------------------------------------------------------------- public API

    /**
     * Fits the richest model the point count supports, unless [forceModel] pins it.
     *
     * Returns null only when the points are degenerate (all identical, or collinear in a way
     * that leaves the system singular) — callers should treat that as "ask the user for more
     * or better-spread points" rather than as a crash.
     */
    fun fit(
        src: List<DoubleArray>,
        dst: List<DoubleArray>,
        forceModel: Model? = null,
        /**
         * Image extent as [minX, minY, maxX, maxY]. When supplied, a homography is rejected
         * if its projection degenerates anywhere inside it — see [projectionIsSaneOver].
         */
        domain: DoubleArray? = null,
        /** Leave-one-out cross-validation. Off by default: it costs N refits. */
        crossValidate: Boolean = false
    ): Fit? {
        require(src.size == dst.size) { "point lists must be the same length" }
        val n = src.size
        if (n == 0) return null

        val model = forceModel ?: when {
            n >= 4 -> Model.HOMOGRAPHY
            n == 3 -> Model.AFFINE
            n == 2 -> Model.SIMILARITY
            else -> Model.TRANSLATION
        }
        if (n < model.minPoints) return null

        val full = solveFor(src, dst, model) ?: return fallback(src, dst, model, domain, crossValidate)

        // A homography fitted to noisy points can place its vanishing line across the sheet,
        // which sends part of the image to infinity. Measured over 400 random trials this
        // filter never rejected a good fit and caught errors up to 3600 km, all while the
        // in-sample residual read zero. Falling back to affine loses perspective correction
        // but stays bounded, which is strictly better than a map with a singularity in it.
        if (model == Model.HOMOGRAPHY && domain != null && !projectionIsSaneOver(full, domain)) {
            return fallback(src, dst, Model.HOMOGRAPHY, domain, crossValidate)
        }

        var sumSq = 0.0
        var worst = 0.0
        for (i in 0 until n) {
            val p = apply(full, src[i][0], src[i][1])
            val d = hypot(p[0] - dst[i][0], p[1] - dst[i][1])
            sumSq += d * d
            if (d > worst) worst = d
        }

        val cv = if (crossValidate) leaveOneOutRms(src, dst, model, domain) else -1.0
        return Fit(full, model, sqrt(sumSq / n), worst, n, cv)
    }

    /** Fits [model] in normalised space and returns the transform in original units. */
    private fun solveFor(src: List<DoubleArray>, dst: List<DoubleArray>, model: Model): DoubleArray? {
        val tSrc = normaliser(src) ?: return null
        val tDst = normaliser(dst) ?: return null
        val nSrc = src.map { applyTo(tSrc, it) }
        val nDst = dst.map { applyTo(tDst, it) }

        val m = when (model) {
            Model.TRANSLATION -> fitTranslation(nSrc, nDst)
            Model.SIMILARITY -> fitSimilarity(nSrc, nDst)
            Model.AFFINE -> fitAffine(nSrc, nDst)
            Model.HOMOGRAPHY -> fitHomography(nSrc, nDst)
        } ?: return null

        // Undo the normalisation: M = Tdst^-1 * M' * Tsrc
        val tDstInv = invert(tDst) ?: return null
        return multiply(tDstInv, multiply(m, tSrc))
    }

    /**
     * Checks that the perspective denominator keeps one sign and stays away from zero across
     * the image. A sign change means the vanishing line crosses the sheet; a large ratio
     * means an implausibly extreme camera angle. Either way the transform is not usable.
     */
    private fun projectionIsSaneOver(m: DoubleArray, domain: DoubleArray): Boolean {
        // w is linear in x and y, so its extremes over a rectangle occur at the corners.
        val w = doubleArrayOf(
            m[6] * domain[0] + m[7] * domain[1] + m[8],
            m[6] * domain[2] + m[7] * domain[1] + m[8],
            m[6] * domain[2] + m[7] * domain[3] + m[8],
            m[6] * domain[0] + m[7] * domain[3] + m[8]
        )
        var lo = Double.MAX_VALUE
        var hi = 0.0
        for (v in w) {
            if (v * w[0] <= 0.0) return false   // sign change: vanishing line crosses the sheet
            val a = abs(v)
            if (a < lo) lo = a
            if (a > hi) hi = a
        }
        // Beyond about a 3x scale change corner-to-corner the fit is describing an
        // implausible camera angle rather than the map.
        return hi > 0.0 && lo / hi >= 0.3
    }

    /**
     * Leave-one-out RMS: refit without each point and measure the error at the held-out one.
     * Needs one more point than the model consumes, else every refit is exactly determined.
     */
    private fun leaveOneOutRms(
        src: List<DoubleArray>,
        dst: List<DoubleArray>,
        model: Model,
        domain: DoubleArray?
    ): Double {
        val n = src.size
        // One spare point is enough to hold one out; each refit is then exactly determined,
        // which makes the estimate pessimistic but still far more informative than the
        // in-sample residual, which is identically zero in that case.
        if (n < model.minPoints + 1) return -1.0
        var sumSq = 0.0
        for (i in 0 until n) {
            val s = ArrayList<DoubleArray>(n - 1)
            val d = ArrayList<DoubleArray>(n - 1)
            for (j in 0 until n) if (j != i) { s.add(src[j]); d.add(dst[j]) }
            val f = fit(s, d, model, domain, crossValidate = false) ?: return -1.0
            val p = apply(f.matrix, src[i][0], src[i][1])
            val e = hypot(p[0] - dst[i][0], p[1] - dst[i][1])
            sumSq += e * e
        }
        return sqrt(sumSq / n)
    }

    /** Retries with progressively simpler models when the richer one is singular or unusable. */
    private fun fallback(
        src: List<DoubleArray>,
        dst: List<DoubleArray>,
        failed: Model,
        domain: DoubleArray?,
        crossValidate: Boolean
    ): Fit? {
        val simpler = when (failed) {
            Model.HOMOGRAPHY -> Model.AFFINE
            Model.AFFINE -> Model.SIMILARITY
            Model.SIMILARITY -> Model.TRANSLATION
            Model.TRANSLATION -> return null
        }
        return fit(src, dst, simpler, domain, crossValidate)
    }

    fun apply(m: DoubleArray, x: Double, y: Double): DoubleArray {
        val w = m[6] * x + m[7] * y + m[8]
        if (abs(w) < 1e-12) return doubleArrayOf(Double.NaN, Double.NaN)
        return doubleArrayOf(
            (m[0] * x + m[1] * y + m[2]) / w,
            (m[3] * x + m[4] * y + m[5]) / w
        )
    }

    fun identity() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0.0
            for (k in 0..2) s += a[i * 3 + k] * b[k * 3 + j]
            r[i * 3 + j] = s
        }
        return r
    }

    fun invert(m: DoubleArray): DoubleArray? {
        val c0 = m[4] * m[8] - m[5] * m[7]
        val c1 = m[5] * m[6] - m[3] * m[8]
        val c2 = m[3] * m[7] - m[4] * m[6]
        val det = m[0] * c0 + m[1] * c1 + m[2] * c2
        if (abs(det) < 1e-14) return null
        val i = 1.0 / det
        return doubleArrayOf(
            c0 * i, (m[2] * m[7] - m[1] * m[8]) * i, (m[1] * m[5] - m[2] * m[4]) * i,
            c1 * i, (m[0] * m[8] - m[2] * m[6]) * i, (m[2] * m[3] - m[0] * m[5]) * i,
            c2 * i, (m[1] * m[6] - m[0] * m[7]) * i, (m[0] * m[4] - m[1] * m[3]) * i
        )
    }

    /** Builds a similarity matrix directly from a drag/pinch/rotate gesture. */
    fun fromSimilarity(scale: Double, rotationRad: Double, tx: Double, ty: Double): DoubleArray {
        val c = kotlin.math.cos(rotationRad) * scale
        val s = kotlin.math.sin(rotationRad) * scale
        return doubleArrayOf(c, -s, tx, s, c, ty, 0.0, 0.0, 1.0)
    }

    // ---------------------------------------------------------------- model fits

    private fun fitTranslation(src: List<DoubleArray>, dst: List<DoubleArray>): DoubleArray {
        var dx = 0.0; var dy = 0.0
        for (i in src.indices) { dx += dst[i][0] - src[i][0]; dy += dst[i][1] - src[i][1] }
        val n = src.size
        return doubleArrayOf(1.0, 0.0, dx / n, 0.0, 1.0, dy / n, 0.0, 0.0, 1.0)
    }

    /**
     * Scale + rotation + translation, allowing a reflection.
     *
     * The reflection is not optional here. Image pixel y increases downward while world y
     * increases upward, so the true image-to-world transform always has a negative
     * determinant — and no rotation can change that sign. A rotation-only similarity fitted
     * to two control points still passes through both of them exactly, reporting zero error
     * while mirroring the rest of the sheet about the line joining them. Measured on a 5 km
     * sheet that is a 5.3 km error at zero apparent residual: the worst possible failure,
     * because it looks perfect.
     *
     * Both handedness options are fitted and the better one wins. With exactly two points
     * both interpolate exactly and the residuals tie, so the tie is broken toward the
     * reflected fit, which is the physically correct one for a map image.
     */
    private fun fitSimilarity(src: List<DoubleArray>, dst: List<DoubleArray>): DoubleArray? {
        val proper = fitSimilarityOfHandedness(src, dst, reflected = false) ?: return null
        val reflected = fitSimilarityOfHandedness(src, dst, reflected = true) ?: return proper
        // Two points: both models pass through both points, so both residuals are zero to
        // within rounding. Comparing them would amount to a coin flip on floating-point
        // noise, so decide on physics — a map image is always mirrored relative to world y.
        if (src.size <= 2) return reflected
        return if (sumSqResidual(reflected, src, dst) <= sumSqResidual(proper, src, dst)) reflected else proper
    }

    /**
     * Closed-form least-squares similarity. Writing the linear part as (a, b) keeps the
     * system linear: [[a,-b],[b,a]] for a rotation, [[a,b],[b,-a]] for a reflection.
     */
    private fun fitSimilarityOfHandedness(
        src: List<DoubleArray>,
        dst: List<DoubleArray>,
        reflected: Boolean
    ): DoubleArray? {
        val n = src.size
        var sx = 0.0; var sy = 0.0; var dx = 0.0; var dy = 0.0
        for (i in 0 until n) { sx += src[i][0]; sy += src[i][1]; dx += dst[i][0]; dy += dst[i][1] }
        sx /= n; sy /= n; dx /= n; dy /= n

        var num1 = 0.0; var num2 = 0.0; var den = 0.0
        for (i in 0 until n) {
            val px = src[i][0] - sx; val py = src[i][1] - sy
            val qx = dst[i][0] - dx; val qy = dst[i][1] - dy
            if (reflected) {
                num1 += px * qx - py * qy
                num2 += px * qy + py * qx
            } else {
                num1 += px * qx + py * qy
                num2 += px * qy - py * qx
            }
            den += px * px + py * py
        }
        if (den < 1e-18) return null
        val a = num1 / den
        val b = num2 / den
        return if (reflected) {
            doubleArrayOf(a, b, dx - (a * sx + b * sy), b, -a, dy - (b * sx - a * sy), 0.0, 0.0, 1.0)
        } else {
            doubleArrayOf(a, -b, dx - (a * sx - b * sy), b, a, dy - (b * sx + a * sy), 0.0, 0.0, 1.0)
        }
    }

    private fun sumSqResidual(m: DoubleArray, src: List<DoubleArray>, dst: List<DoubleArray>): Double {
        var s = 0.0
        for (i in src.indices) {
            val p = apply(m, src[i][0], src[i][1])
            val ex = p[0] - dst[i][0]
            val ey = p[1] - dst[i][1]
            s += ex * ex + ey * ey
        }
        return s
    }

    /** Two independent 3-parameter least-squares problems, one per output axis. */
    private fun fitAffine(src: List<DoubleArray>, dst: List<DoubleArray>): DoubleArray? {
        val ata = Array(3) { DoubleArray(3) }
        val atx = DoubleArray(3)
        val aty = DoubleArray(3)
        for (i in src.indices) {
            val r = doubleArrayOf(src[i][0], src[i][1], 1.0)
            for (j in 0..2) {
                for (k in 0..2) ata[j][k] += r[j] * r[k]
                atx[j] += r[j] * dst[i][0]
                aty[j] += r[j] * dst[i][1]
            }
        }
        val cx = solve(copyOf(ata), atx.copyOf()) ?: return null
        val cy = solve(copyOf(ata), aty.copyOf()) ?: return null
        return doubleArrayOf(cx[0], cx[1], cx[2], cy[0], cy[1], cy[2], 0.0, 0.0, 1.0)
    }

    /**
     * Full projective fit via the DLT, with h33 pinned to 1 so the system stays inhomogeneous
     * and can be solved by normal equations instead of needing an SVD. h33 is only zero when
     * the map plane passes through the camera centre, which cannot happen for a photo of a
     * map lying in front of the lens.
     */
    private fun fitHomography(src: List<DoubleArray>, dst: List<DoubleArray>): DoubleArray? {
        val ata = Array(8) { DoubleArray(8) }
        val atb = DoubleArray(8)
        for (i in src.indices) {
            val x = src[i][0]; val y = src[i][1]
            val bigX = dst[i][0]; val bigY = dst[i][1]
            val r1 = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -x * bigX, -y * bigX)
            val r2 = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -x * bigY, -y * bigY)
            accumulate(ata, atb, r1, bigX)
            accumulate(ata, atb, r2, bigY)
        }
        val h = solve(ata, atb) ?: return null
        return doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
    }

    private fun accumulate(ata: Array<DoubleArray>, atb: DoubleArray, row: DoubleArray, b: Double) {
        for (j in row.indices) {
            for (k in row.indices) ata[j][k] += row[j] * row[k]
            atb[j] += row[j] * b
        }
    }

    // ---------------------------------------------------------------- numerics

    private fun copyOf(m: Array<DoubleArray>) = Array(m.size) { m[it].copyOf() }

    /** Gaussian elimination with partial pivoting. Returns null if the matrix is singular. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (abs(a[pivot][col]) < 1e-14) return null
            if (pivot != col) {
                val t = a[pivot]; a[pivot] = a[col]; a[col] = t
                val tb = b[pivot]; b[pivot] = b[col]; b[col] = tb
            }
            for (r in col + 1 until n) {
                val f = a[r][col] / a[col][col]
                if (f == 0.0) continue
                for (c in col until n) a[r][c] -= f * a[col][c]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (c in r + 1 until n) s -= a[r][c] * x[c]
            x[r] = s / a[r][r]
        }
        return x
    }

    /**
     * Hartley normalisation: centre the points on the origin and scale so the mean distance
     * from it is sqrt(2). Without this, fitting pixels against Mercator metres is numerically
     * hopeless.
     */
    private fun normaliser(pts: List<DoubleArray>): DoubleArray? {
        val n = pts.size
        var cx = 0.0; var cy = 0.0
        for (p in pts) { cx += p[0]; cy += p[1] }
        cx /= n; cy /= n
        var mean = 0.0
        for (p in pts) mean += hypot(p[0] - cx, p[1] - cy)
        mean /= n
        // A single point, or several stacked on top of each other, has no meaningful scale;
        // fall back to pure centring so the caller still gets a usable transform.
        val s = if (mean < 1e-12) 1.0 else sqrt(2.0) / mean
        return doubleArrayOf(s, 0.0, -s * cx, 0.0, s, -s * cy, 0.0, 0.0, 1.0)
    }

    private fun applyTo(m: DoubleArray, p: DoubleArray) = apply(m, p[0], p[1])
}
