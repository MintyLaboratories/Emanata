package minty.emanata.ui

import android.Manifest
import android.app.Application
import android.os.Environment
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.floatingactionbutton.FloatingActionButton
import minty.emanata.R
import minty.emanata.data.DetailDisplay
import minty.emanata.data.ListDensity
import minty.emanata.data.Prefs
import minty.emanata.data.RingOption
import minty.emanata.data.RingOptions
import minty.emanata.data.SortKey
import minty.emanata.data.SortLevel
import minty.emanata.data.TitleDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowEnvironment
import java.io.File
import java.time.Duration

/**
 * The view ring's longest labels, measured with real text: whole (no "…"), the value on at
 * most two lines, clear of every button, the hub, each other and (stood-in) system bars, at font
 * scales 1.0, 1.3 and 2.0 on a phone and a tablet, for both hands; the phone at 2.0 also at
 * common narrower widths and with Android 14's nonlinear font scaling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewRingLabelsTest {

    private lateinit var context: Application
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        for (name in listOf("ui", "library", "favorite_kinds")) context.getSharedPreferences(name, 0).edit().clear().commit()
        prefs = Prefs(context)
        val dir = File(Environment.getExternalStorageDirectory(), "shelf").apply { mkdirs() }
        File(dir, "Book 01.cbz").createNewFile()
        prefs.lastPath = dir.absolutePath
        // The longest values: sort by reading direction, an extra large list, comfortable details, a middle-cut title.
        prefs.setSort(Prefs.TAB_BROWSE, prefs.sort(Prefs.TAB_BROWSE).copy(by = SortLevel(SortKey.DIRECTION)))
        prefs.setGrid(Prefs.TAB_BROWSE, false)
        prefs.setDensity(Prefs.TAB_BROWSE, ListDensity.EXTRA_LARGE)
        prefs.setDetail(Prefs.TAB_BROWSE, DetailDisplay.COMFORTABLE)
        prefs.titleDisplay = TitleDisplay.SINGLE
        prefs.ringOptions = RingOptions.DEFAULT.with(RingOption.SECTIONS, false).with(RingOption.TITLE_LINES, true).moved(3, 0)
    }

    private companion object {
        const val PHONE = "w400dp-h800dp-xxhdpi"
        const val NARROW = "w360dp-h800dp-xxhdpi"
    }

    private fun settle() = repeat(3) {
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
    }

    private fun box(v: View): RingGeometry.Box {
        val at = IntArray(2).also { v.getLocationInWindow(it) }
        return RingGeometry.Box(at[0].toFloat(), at[1].toFloat(), (at[0] + v.width).toFloat(), (at[1] + v.height).toFloat())
    }

    /** A status bar and side cutouts the ring must keep clear of (the floating button sits above the bottom). */
    private fun bars(list: ListActivity): Insets = (list.resources.displayMetrics.density).let { d -> Insets.of((24 * d).toInt(), (32 * d).toInt(), (24 * d).toInt(), 0) }

    private fun center(v: View): Triple<Float, Float, Float> = box(v).let { Triple((it.left + it.right) / 2, (it.top + it.bottom) / 2, v.width / 2f) }

    /** Opens the ring on a screen of [qualifiers] at [scale] and checks every label, and that the radius is at most [most] times the one asked for. */
    private fun check(qualifiers: String, scale: Float, left: Boolean, count: Int = 6, most: Float = 1f) {
        val what = "$qualifiers at $scale${if (left) ", left-handed" else ""}"
        RuntimeEnvironment.setQualifiers(qualifiers)
        RuntimeEnvironment.setFontScale(scale)
        prefs.leftHanded = left
        val list = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        settle()
        val bars = bars(list)
        list.viewRing.barInsets = { bars }
        val fab = list.findViewById<FloatingActionButton>(R.id.fab)
        assertTrue(what, fab.performLongClick())
        settle()
        val ring = list.viewRing
        val options = ViewRing.options(Prefs.TAB_BROWSE, prefs.ringOptions)
        assertEquals(what, count, options.size)
        val screen = box(list.window.decorView).let { RingGeometry.Box(it.left + bars.left, it.top + bars.top, it.right - bars.right, it.bottom - bars.bottom) }
        val want = RingGeometry.radius(options.size, 75f, 91f) * list.resources.displayMetrics.density
        assertTrue("$what: radius ${ring.ringRadius / want} of the one asked for", ring.ringRadius <= want * most + 0.5f)
        fun labelsClear(what: String) {
            val round = options.map { center(ring.button(it)!!) } + center(ring.hub()!!)
            val labels = options.map { it to ring.label(it)!! }
            for ((o, label) in labels) {
                val layout = label.layout
                val text = label.text.toString()
                for (line in 0 until layout.lineCount) assertEquals("$what: $o \"$text\" whole", 0, layout.getEllipsisCount(line))
                assertTrue("$what: $o \"$text\" on ${layout.lineCount} lines", layout.lineCount <= 3)
                val l = box(label)
                assertTrue("$what: $o $l on $screen", l.within(screen))
                for ((x, y, r) in round) assertEquals("$what: $o \"$text\" on a button at $x, $y", 0f, l.overlap(x, y, r), 0f)
                for ((p, other) in labels) if (p != o) assertEquals("$what: $o on $p", 0f, l.overlap(box(other)), 0f)
            }
        }
        labelsClear(what)
        // A tap changes values in place; the labels still fit, clear of the buttons, the hub, each other and the bars.
        ring.button(RingOption.SORT)!!.performClick()
        settle()
        labelsClear("$what, after a tap")
        ring.close(animate = false)
    }

    @Test
    fun aPhonesLongestLabelsFitAtEveryFontScale() {
        for (scale in listOf(1.0f, 1.3f)) for (left in listOf(false, true)) check(PHONE, scale, left)
        // At twice the font size, the default ring's five: short forms before the ring grows, and then no larger than the first ring.
        prefs.ringOptions = RingOptions.DEFAULT
        for (left in listOf(false, true)) check(PHONE, 2.0f, left, count = 5, most = 1.5f)
    }

    @Test
    fun aPhonesSixLongestLabelsFitAtTwiceTheFontSize() {
        for (left in listOf(false, true)) check(PHONE, 2.0f, left)
    }

    /** Common phone widths and split-screen or pop-up windows on a tablet. */
    @Test
    fun narrowerWindowsSixLongestLabelsFitAtTwiceTheFontSize() {
        for (width in listOf(360, 384, 393, 411)) for (left in listOf(false, true)) check("w${width}dp-h800dp-xxhdpi", 2.0f, left)
    }

    @Test
    @Ignore("F243 gap: below about 348 dp at font scale 2.0, a label at the 130% text floor reaches over a button")
    fun theNarrowestWindowsSixLongestLabelsFitAtTwiceTheFontSize() {
        for (width in listOf(320, 340)) for (left in listOf(false, true)) check("w${width}dp-h800dp-xxhdpi", 2.0f, left)
    }

    /** Opens the ring on a screen of [qualifiers] at [scale] and returns a plain label's text size, the ring's text floor and each ring label's size. */
    private fun textSizes(scale: Float, qualifiers: String = PHONE): Triple<Float, Float, List<Float>> {
        RuntimeEnvironment.setQualifiers(qualifiers)
        RuntimeEnvironment.setFontScale(scale)
        val list = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        settle()
        list.viewRing.barInsets = { bars(list) }
        assertTrue(list.findViewById<FloatingActionButton>(R.id.fab).performLongClick())
        settle()
        val sizes = ViewRing.options(Prefs.TAB_BROWSE, prefs.ringOptions).map { list.viewRing.label(it)!!.textSize }
        val floor = list.viewRing.textFloor()
        list.viewRing.close(animate = false)
        return Triple(TextView(list).textSize, floor, sizes)
    }

    @Test
    fun labelTextShrinksOnlyWhereGrowingCannotClearTheLabels() {
        // At 1.3, the text keeps its size, which is the floor.
        val (plain13, floor13, at13) = textSizes(1.3f)
        assertEquals(plain13, floor13, 0f)
        for (size in at13) assertEquals(plain13, size, 0f)
        // Six of the longest at twice the font size: all the same, smaller, and only as much as they need: above the floor.
        val (plain, floor, six) = textSizes(2.0f)
        assertEquals(plain13, floor, 0f)
        for (size in six) assertTrue("$size of $plain, floor $plain13", size < plain && size > plain13 + 0.5f)
        assertEquals(1, six.distinct().size)
        // On a narrower phone the floor clears them only with every label's sides searched together.
        val (plainNarrow, _, narrow) = textSizes(2.0f, NARROW)
        assertTrue("floor $plain13 of $plainNarrow", plain13 < plainNarrow)
        for (size in narrow) assertEquals("$size of $plainNarrow", plain13, size, 0f)
        for (left in listOf(false, true)) check(NARROW, 2.0f, left)
        // The default ring's five at twice the font size: the ring grows instead.
        prefs.ringOptions = RingOptions.DEFAULT
        val (plain5, _, five) = textSizes(2.0f)
        for (size in five) assertEquals(plain5, size, 0f)
    }

    /** Android 14 scales text nonlinearly, so the floor is the real size at 1.3, not a share of the size at 2.0. */
    @Test
    @Config(sdk = [34])
    fun aPhonesSixLongestLabelsFitAtTwiceTheFontSizeOnAndroid14() {
        // From Android 11 the list asks for all-files access, which reads the first storage volume.
        ShadowEnvironment.addExternalDir("emulated")
        shadowOf(context).grantPermissions(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
        val (plain13, _, _) = textSizes(1.3f)
        val (plain, floor, six) = textSizes(2.0f)
        assertTrue("nonlinear: $plain at 2.0, $plain13 at 1.3", plain < plain13 * 2.0f / 1.3f - 1f)
        assertEquals(plain13, floor, 0f)
        for (size in six) assertTrue("$size of $plain, floor $plain13", size < plain && size >= plain13)
        for (left in listOf(false, true)) check(PHONE, 2.0f, left)
        for (left in listOf(false, true)) check(NARROW, 2.0f, left)
    }

    @Test
    fun aTabletsLongestLabelsFitAtEveryFontScale() {
        for (scale in listOf(1.0f, 1.3f)) for (left in listOf(false, true)) check("w924dp-h1480dp-xhdpi", scale, left)
        // At twice the font size, short forms before the ring grows, and then no larger than the first ring.
        for (left in listOf(false, true)) check("w924dp-h1480dp-xhdpi", 2.0f, left, most = 1.5f)
    }

    @Test
    fun aRoomyRingShowsTheFullValueAndItsMutedPart() {
        val list = run {
            RuntimeEnvironment.setQualifiers("w924dp-h1480dp-xhdpi")
            Robolectric.buildActivity(ListActivity::class.java).setup().get()
        }
        settle()
        list.findViewById<FloatingActionButton>(R.id.fab).performLongClick()
        settle()
        val label = list.viewRing.label(RingOption.SORT)!!
        assertEquals("Sort\nReading direction left-to-right first→Reading direction right-to-left first", label.text.toString().replace("\u200B", ""))
        val spanned = label.text as android.text.Spanned
        val muted = com.google.android.material.color.MaterialColors.getColor(label, com.google.android.material.R.attr.colorOutline)
        val strong = com.google.android.material.color.MaterialColors.getColor(label, com.google.android.material.R.attr.colorOnSurface)
        fun colorAt(i: Int) = spanned.getSpans(i, i + 1, android.text.style.ForegroundColorSpan::class.java).last().foregroundColor
        val text = spanned.toString()
        val arrow = text.indexOf('→')
        assertEquals(muted, colorAt(text.indexOf('\n') + 1))
        assertEquals(muted, colorAt(arrow))
        assertEquals(strong, colorAt(arrow + 2))
        assertEquals(strong, colorAt(text.length - 1))
        // TalkBack hears the full words.
        assertEquals(
            "Sort: Reading direction – left-to-right first, tap for Reading direction – right-to-left first",
            list.viewRing.button(RingOption.SORT)!!.contentDescription.toString()
        )
    }
}
