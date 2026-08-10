package si.rok.orientacija.custom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Pan/zoom image view that reports taps in *image* pixel coordinates.
 *
 * Control points have to be recorded against the image itself, not the screen, so the
 * calibration survives the user zooming around while placing them. Every tap is therefore
 * pushed back through the inverse of the view matrix.
 */
class ImagePointView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Called with image-pixel coordinates whenever the user taps to place the cursor. */
    var onPointPicked: ((Double, Double) -> Unit)? = null

    var bitmap: Bitmap? = null
        set(value) { field = value; pendingFit = true; invalidate() }

    /** Points already committed, drawn numbered. */
    var committedPoints: List<Pair<Double, Double>> = emptyList()
        set(value) { field = value; invalidate() }

    /** The not-yet-committed cursor, in image pixels. */
    var cursor: Pair<Double, Double>? = null
        set(value) { field = value; invalidate() }

    private val viewMatrix = Matrix()
    private val inverse = Matrix()
    private var pendingFit = true
    private val tmp = FloatArray(2)

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6D00"); strokeWidth = 4f; style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2E6B3E"); style = Paint.Style.FILL
    }
    private val dotEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 26f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            viewMatrix.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            // Ignore drags while pinching, or the image lurches as fingers land.
            if (scaleDetector.isInProgress) return false
            viewMatrix.postTranslate(-dx, -dy)
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val bmp = bitmap ?: return false
            if (!viewMatrix.invert(inverse)) return false
            tmp[0] = e.x; tmp[1] = e.y
            inverse.mapPoints(tmp)
            // Reject taps outside the image; they are almost always a missed pan.
            if (tmp[0] < 0 || tmp[1] < 0 || tmp[0] > bmp.width || tmp[1] > bmp.height) return false
            cursor = Pair(tmp[0].toDouble(), tmp[1].toDouble())
            onPointPicked?.invoke(tmp[0].toDouble(), tmp[1].toDouble())
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            viewMatrix.postScale(2f, 2f, e.x, e.y)
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        if (pendingFit && width > 0 && height > 0) {
            fitToView(bmp)
            pendingFit = false
        }
        canvas.drawBitmap(bmp, viewMatrix, bmpPaint)

        committedPoints.forEachIndexed { i, p ->
            tmp[0] = p.first.toFloat(); tmp[1] = p.second.toFloat()
            viewMatrix.mapPoints(tmp)
            canvas.drawCircle(tmp[0], tmp[1], 20f, dotPaint)
            canvas.drawCircle(tmp[0], tmp[1], 20f, dotEdge)
            canvas.drawText("${i + 1}", tmp[0], tmp[1] + 9f, labelPaint)
        }

        cursor?.let { c ->
            tmp[0] = c.first.toFloat(); tmp[1] = c.second.toFloat()
            viewMatrix.mapPoints(tmp)
            val r = 26f
            canvas.drawCircle(tmp[0], tmp[1], r, crossPaint)
            canvas.drawLine(tmp[0] - r * 1.7f, tmp[1], tmp[0] - r * 0.4f, tmp[1], crossPaint)
            canvas.drawLine(tmp[0] + r * 0.4f, tmp[1], tmp[0] + r * 1.7f, tmp[1], crossPaint)
            canvas.drawLine(tmp[0], tmp[1] - r * 1.7f, tmp[0], tmp[1] - r * 0.4f, crossPaint)
            canvas.drawLine(tmp[0], tmp[1] + r * 0.4f, tmp[0], tmp[1] + r * 1.7f, crossPaint)
        }
    }

    private fun fitToView(bmp: Bitmap) {
        val s = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        viewMatrix.reset()
        viewMatrix.postScale(s, s)
        viewMatrix.postTranslate((width - bmp.width * s) / 2f, (height - bmp.height * s) / 2f)
    }

    fun resetView() { pendingFit = true; invalidate() }

    /** Nudges the cursor by whole image pixels, for fine placement past what a fingertip allows. */
    fun nudgeCursor(dx: Double, dy: Double) {
        val c = cursor ?: return
        val bmp = bitmap ?: return
        val nx = max(0.0, min(bmp.width.toDouble(), c.first + dx))
        val ny = max(0.0, min(bmp.height.toDouble(), c.second + dy))
        cursor = Pair(nx, ny)
        onPointPicked?.invoke(nx, ny)
    }
}
