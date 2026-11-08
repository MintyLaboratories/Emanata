package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.os.Environment
import android.os.Looper
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.infinity.comicreader.R
import com.infinity.comicreader.data.DetailDisplay
import com.infinity.comicreader.data.ListDensity
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.RingOption
import com.infinity.comicreader.data.RingOptions
import com.infinity.comicreader.data.SortKey
import com.infinity.comicreader.data.SortLevel
import com.infinity.comicreader.data.TitleDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The view ring's longest labels, measured with real text: whole (no "…"), the value on at
 * most two lines, clear of every button, the hub, each other and the system bars, at font
 * scales 1.0, 1.3 and 2.0 on a phone and a tablet, for both hands.
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

    private fun settle() = repeat(3) {
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
    }

    private fun box(v: View): RingGeometry.Box {
        val at = IntArray(2).also { v.getLocationInWindow(it) }
        return RingGeometry.Box(at[0].toFloat(), at[1].toFloat(), (at[0] + v.width).toFloat(), (at[1] + v.height).toFloat())
    }

    private fun center(v: View): Triple<Float, Float, Float> = box(v).let { Triple((it.left + it.right) / 2, (it.top + it.bottom) / 2, v.width / 2f) }

    /** Opens the ring on a screen of [qualifiers] at [scale] and checks every label. */
    private fun check(qualifiers: String, scale: Float, left: Boolean, count: Int = 6) {
        val what = "$qualifiers at $scale${if (left) ", left-handed" else ""}"
        RuntimeEnvironment.setQualifiers(qualifiers)
        RuntimeEnvironment.setFontScale(scale)
        prefs.leftHanded = left
        val list = Robolectric.buildActivity(ListActivity::class.java).setup().get()
        settle()
        val fab = list.findViewById<FloatingActionButton>(R.id.fab)
        assertTrue(what, fab.performLongClick())
        settle()
        val ring = list.viewRing
        val options = ViewRing.options(Prefs.TAB_BROWSE, prefs.ringOptions)
        assertEquals(what, count, options.size)
        val screen = box(list.window.decorView)
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
        // A tap changes values in place; the labels still fit.
        ring.button(RingOption.SORT)!!.performClick()
        settle()
        for ((o, label) in labels) for (line in 0 until label.layout.lineCount) assertEquals("$what, after a tap: $o whole", 0, label.layout.getEllipsisCount(line))
        ring.close(animate = false)
    }

    @Test
    fun aPhonesLongestLabelsFitAtEveryFontScale() {
        for (scale in listOf(1.0f, 1.3f)) for (left in listOf(false, true)) check("w400dp-h800dp-xxhdpi", scale, left)
        // At twice the font size, the default ring's five.
        prefs.ringOptions = RingOptions.DEFAULT
        for (left in listOf(false, true)) check("w400dp-h800dp-xxhdpi", 2.0f, left, count = 5)
    }

    @Test
    fun aTabletsLongestLabelsFitAtEveryFontScale() {
        for (scale in listOf(1.0f, 1.3f, 2.0f)) for (left in listOf(false, true)) check("w924dp-h1480dp-xhdpi", scale, left)
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
