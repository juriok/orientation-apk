package si.rok.orientacija.data

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import si.rok.orientacija.R
import si.rok.orientacija.databinding.ActivitySimpleListBinding
import si.rok.orientacija.util.EdgeToEdge

/**
 * Courses are ordered sequences of waypoints. Building one from existing waypoints rather
 * than a separate point editor means anything already dropped on the map — or imported from
 * a GPX — can become a training course without re-entering it.
 */
class CoursesActivity : AppCompatActivity() {

    private lateinit var b: ActivitySimpleListBinding
    private lateinit var store: CourseStore
    private var items = mutableListOf<Course>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySimpleListBinding.inflate(layoutInflater)
        setContentView(b.root)
        EdgeToEdge.apply(b.root, b.toolbar, b.list)
        b.toolbar.title = getString(R.string.courses)
        b.toolbar.setNavigationOnClickListener { finish() }

        store = CourseStore(this)
        b.txtEmpty.setText(R.string.no_courses_yet)

        b.btnPrimary.setText(R.string.new_course)
        b.btnPrimary.setOnClickListener { createCourse() }
        b.btnSecondary.setText(R.string.punch_radius)
        b.btnSecondary.setOnClickListener { editPunchRadius() }

        b.list.setOnItemClickListener { _, _, pos, _ -> showActions(items[pos]) }
        refresh()
    }

    private fun refresh() {
        items = store.load()
        items.sortByDescending { it.createdAt }
        b.txtEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        b.list.adapter = object : ArrayAdapter<Course>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val c = items[position]
                v.findViewById<TextView>(android.R.id.text1).text = c.name
                v.findViewById<TextView>(android.R.id.text2).text =
                    "%d kontrolnih točk · %.2f km v zračni črti".format(c.controls.size, c.lengthMetres() / 1000)
                return v
            }
        }
    }

    /**
     * Builds a course by picking waypoints. Order follows the order they are tapped, not the
     * order they appear in the list — a course is a sequence, and re-ordering afterwards
     * would be far more fiddly than simply tapping them in the order you will run them.
     */
    private fun createCourse() {
        val waypoints = WaypointStore(this).load()
        if (waypoints.size < 2) {
            Toast.makeText(this, "Potrebujete vsaj 2 shranjeni točki.", Toast.LENGTH_LONG).show()
            return
        }
        val labels = waypoints.map { it.name }.toTypedArray()
        val chosen = mutableListOf<Int>()
        val checked = BooleanArray(waypoints.size)

        AlertDialog.Builder(this)
            .setTitle("Tapnite točke v vrstnem redu proge")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) chosen.add(which) else chosen.remove(which)
            }
            .setPositiveButton(R.string.ok) { _, _ ->
                if (chosen.size < 2) {
                    Toast.makeText(this, "Izberite vsaj 2 točki.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                promptName(chosen.map { waypoints[it] })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptName(points: List<Waypoint>) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText("Proga ${store.load().size + 1}")
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.new_course)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val course = Course(name = input.text.toString().ifBlank { "Proga" })
                points.forEachIndexed { i, w ->
                    course.controls.add(Control("${i + 1}. ${w.name}", w.lat, w.lon))
                }
                store.upsert(course)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showActions(course: Course) {
        AlertDialog.Builder(this)
            .setTitle(course.name)
            .setItems(
                arrayOf(getString(R.string.start_course), getString(R.string.rename), getString(R.string.delete))
            ) { _, which ->
                when (which) {
                    0 -> {
                        CourseRunner.start(course)
                        setResult(Activity.RESULT_OK, Intent())
                        Toast.makeText(this, "Proga se je začela.", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    1 -> renameCourse(course)
                    2 -> {
                        if (CourseRunner.course?.id == course.id) CourseRunner.stop()
                        store.delete(course.id)
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun renameCourse(course: Course) {
        val input = EditText(this).apply { setText(course.name); setSelection(text.length) }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                course.name = input.text.toString().ifBlank { course.name }
                store.upsert(course)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editPunchRadius() {
        val options = intArrayOf(10, 15, 25, 40)
        val labels = options.map { "$it m" }.toTypedArray()
        val current = options.indexOfFirst { it.toDouble() == CourseRunner.punchRadiusMetres }
        AlertDialog.Builder(this)
            .setTitle(R.string.punch_radius)
            .setSingleChoiceItems(labels, current) { d, which ->
                CourseRunner.punchRadiusMetres = options[which].toDouble()
                getSharedPreferences("orientacija", MODE_PRIVATE).edit()
                    .putInt("punch_radius", options[which]).apply()
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
