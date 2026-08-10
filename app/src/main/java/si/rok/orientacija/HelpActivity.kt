package si.rok.orientacija

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import si.rok.orientacija.databinding.ActivityHelpBinding
import si.rok.orientacija.util.EdgeToEdge

/**
 * The manual. Kept as one scrollable page rather than a tour or a series of tips, because
 * it is most likely to be read once at the kitchen table before going out, and then
 * consulted for a specific answer afterwards.
 */
class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(b.root)
        EdgeToEdge.apply(b.root, b.toolbar, b.scroll)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.txtHelp.text = HtmlCompat.fromHtml(
            getString(R.string.help_body),
            HtmlCompat.FROM_HTML_MODE_COMPACT
        )
    }
}
