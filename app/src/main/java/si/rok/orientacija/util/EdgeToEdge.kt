package si.rok.orientacija.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Inset handling for screens that use a toolbar.
 *
 * Android 15 draws SDK-35 apps edge to edge and stops reserving space for the status and
 * navigation bars, so without this the toolbar slides underneath the clock and the bottom
 * of the content disappears behind the gesture bar.
 */
object EdgeToEdge {

    /**
     * Pads [topView] down past the status bar and [bottomView] up past the gesture bar.
     *
     * Padding is added to whatever the view already has rather than assigned, and the
     * original values are captured once, so repeated inset callbacks (rotation, keyboard,
     * multi-window) do not accumulate.
     */
    fun apply(root: View, topView: View, bottomView: View? = null) {
        val topBase = topView.paddingTop
        val bottomBase = bottomView?.paddingBottom ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topView.setPadding(
                topView.paddingLeft, topBase + bars.top, topView.paddingRight, topView.paddingBottom
            )
            bottomView?.setPadding(
                bottomView.paddingLeft, bottomView.paddingTop,
                bottomView.paddingRight, bottomBase + bars.bottom
            )
            insets
        }
    }
}
