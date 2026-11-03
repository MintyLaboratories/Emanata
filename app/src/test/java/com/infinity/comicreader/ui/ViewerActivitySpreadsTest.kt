package com.infinity.comicreader.ui

import com.infinity.comicreader.testing.bookId
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ResumeMode
import com.infinity.comicreader.data.SpreadDisplay
import com.infinity.comicreader.data.TwoPage
import com.infinity.comicreader.reader.Fixtures
import com.infinity.comicreader.reader.TestImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import java.io.File
import java.time.Duration

/** Paged reading laid out by the spread engine: pairs by page number, and spreads split or whole. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerActivitySpreadsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Application
    private lateinit var prefs: Prefs
    private lateinit var dir: File

    private val portrait = TestImages.png(26, 40)
    private val wide = TestImages.png(52, 40)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        prefs.resumeMode = ResumeMode.NO
        dir = tmp.newFolder("shelf")
    }

    /** A book of [names] (`.png` added), wide where [wideNames] says. */
    private fun book(names: List<String>, wideNames: Set<String> = emptySet()): File =
        Fixtures.zip(File(dir, "Tale 001.cbz"), *names.map { Fixtures.entry("$it.png", if (it in wideNames) wide else portrait) }.toTypedArray())

    /** Like [book], as a 7z archive: solid, so its page sizes are not read ahead. */
    private fun solidBook(names: List<String>, wideNames: Set<String> = emptySet()): File =
        Fixtures.sevenZip(File(dir, "Tale 001.cb7"), *names.map { Fixtures.entry("$it.png", if (it in wideNames) wide else portrait) }.toTypedArray())

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(book: File): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val progress = viewer.findViewById<View>(R.id.viewer_progress)
        waitUntil { progress.visibility != View.VISIBLE }
        return viewer
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        do {
            Thread.sleep(10)
            idle()
        } while (!condition() && System.currentTimeMillis() < deadline)
    }

    /** Waits for the reader to have read and stored the open book's page sizes. */
    private fun sizesRead() {
        waitUntil { File(context.cacheDir, "page-sizes-by-id").listFiles()?.any { it.name.endsWith(".bin") } == true }
        repeat(5) { Thread.sleep(10); idle() }
    }

    private fun ViewerActivity.items() = findViewById<ViewPager2>(R.id.viewer_pager).adapter!!.itemCount

    private fun ViewerActivity.indicator() = findViewById<TextView>(R.id.viewer_page_text).text.toString()

    private fun ViewerActivity.next() {
        findViewById<View>(R.id.btn_next_page).performClick()
        idle()
    }

    private fun numbered(from: Int, to: Int) = (from..to).map { "Tale 001-%03d".format(it) }

    @Test
    fun twoPagesPairByPageNumberAroundAFilenameSpread() {
        prefs.twoPage = TwoPage.ON
        val names = numbered(0, 5) + "Tale 001-006-007" + numbered(8, 11)
        val viewer = launch(book(names))
        // [000] [001] [002|003] [004|005] [006-007] [008|009] [010|011]: the spread votes even.
        assertEquals(7, viewer.items())
        repeat(4) { viewer.next() }
        assertEquals("7 / 11", viewer.indicator())
        viewer.next()
        assertEquals("8 / 11", viewer.indicator())
    }

    @Test
    fun theFirstPageStandsAloneEvenWhenWide() {
        prefs.twoPage = TwoPage.ON
        val names = (1..8).map { "Tale %02d".format(it) }
        val viewer = launch(book(names, setOf("Tale 01")))
        assertEquals(5, viewer.items())
        viewer.next()
        assertEquals("2 / 8", viewer.indicator())
    }

    @Test
    fun openingOnAWidePageSplitsItFromTheFirstLayout() {
        prefs.spreads = SpreadDisplay.SPLIT
        // Battery saver holds the size scans back: only the sizes read at opening are known.
        prefs.saverScans = true
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
        val book = book(numbered(1, 10), setOf("Tale 001-005"))
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath).putExtra(ViewerActivity.EXTRA_PAGE, 4)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val pager = viewer.findViewById<ViewPager2>(R.id.viewer_pager)
        waitUntil { pager.adapter != null }
        repeat(20) { Thread.sleep(10); idle() }
        assertEquals(11, viewer.items())
        assertEquals("5 / 11", viewer.indicator())
    }

    @Test
    fun openingOnTwoWidePagesSplitsBoth() {
        prefs.spreads = SpreadDisplay.SPLIT
        prefs.saverScans = true
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
        val viewer = openAt(book(numbered(1, 10), setOf("Tale 001-005", "Tale 001-006")), 4)
        assertEquals(12, viewer.items())
        assertEquals("5 / 12", viewer.indicator())
    }

    @Test
    fun openingALandscapeBookShowsWholePages() {
        prefs.spreads = SpreadDisplay.SPLIT
        prefs.saverScans = true
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
        val names = numbered(1, 10)
        val viewer = openAt(book(names, names.toSet()), 4)
        assertEquals(10, viewer.items())
        assertEquals("5 / 10", viewer.indicator())
    }

    /** The reader opened on [book] at page [page] once its first layout settles. */
    private fun openAt(book: File, page: Int): ViewerActivity {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book.absolutePath).putExtra(ViewerActivity.EXTRA_PAGE, page)
        val viewer = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup().get()
        val pager = viewer.findViewById<ViewPager2>(R.id.viewer_pager)
        waitUntil { pager.adapter != null }
        repeat(20) { Thread.sleep(10); idle() }
        return viewer
    }

    @Test
    fun splitShowsAWidePageAsTwoNumberedPages() {
        prefs.spreads = SpreadDisplay.SPLIT
        val viewer = launch(book(numbered(1, 10), setOf("Tale 001-005")))
        // The sizes are read after the book shows, then the wide page takes two screens.
        waitUntil { viewer.items() == 11 }
        assertEquals(11, viewer.items())
        repeat(4) { viewer.next() }
        assertEquals("5 / 11", viewer.indicator())
        viewer.next()
        assertEquals("6 / 11", viewer.indicator())
        viewer.next()
        assertEquals("7 / 11", viewer.indicator())
    }

    @Test
    fun mergeShowsAWidePageWhole() {
        prefs.spreads = SpreadDisplay.MERGE
        val viewer = launch(book(numbered(1, 10), setOf("Tale 001-005")))
        sizesRead()
        assertEquals(10, viewer.items())
    }

    @Test
    fun theSheetSwitchesSpreadsBetweenSplitAndMerge() {
        val viewer = launch(book(numbered(1, 10), setOf("Tale 001-005")))
        ReaderSheet(viewer).tap(R.id.btn_spreads_split)
        waitUntil { viewer.items() == 11 }
        assertEquals(SpreadDisplay.SPLIT, prefs.spreads)
        assertEquals(11, viewer.items())
        ReaderSheet(viewer).tap(R.id.btn_spreads_merge)
        assertEquals(10, viewer.items())
    }

    @Test
    fun shiftingMovesThePairsOfThisBookOnly() {
        prefs.twoPage = TwoPage.ON
        val book = book((1..7).map { "Tale %02d".format(it) })
        val viewer = launch(book)
        // [01] [02|03] [04|05] [06|07], then shifted [01] [02] [03|04] [05|06] [07].
        assertEquals(4, viewer.items())
        val sheet = ReaderSheet(viewer)
        assertTrue(sheet.view<MaterialSwitch>(R.id.sheet_shift).isEnabled)
        sheet.tap(R.id.sheet_shift)
        assertEquals(5, viewer.items())
        assertTrue(Library(context).isShifted(bookId(book)))
        sheet.tap(R.id.sheet_shift)
        assertEquals(4, viewer.items())
        assertFalse(Library(context).isShifted(bookId(book)))
    }

    @Test
    fun aWidePageTurnsTheScreenToLandscapeUntilTheNextPage() {
        val viewer = launch(book(numbered(1, 6), setOf("Tale 001-003")))
        // The wide page is known once the sizes are read.
        sizesRead()
        viewer.next()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
        viewer.next()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, viewer.requestedOrientation)
        viewer.next()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
    }

    @Test
    fun rotateForSpreadsOffLeavesTheScreenAlone() {
        prefs.rotateSpreads = false
        val viewer = launch(book(numbered(1, 6), setOf("Tale 001-003")))
        sizesRead()
        repeat(2) { viewer.next() }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
    }

    @Test
    fun splitHalvesNeedNoTurn() {
        prefs.spreads = SpreadDisplay.SPLIT
        val viewer = launch(book(numbered(1, 6), setOf("Tale 001-003")))
        waitUntil { viewer.items() == 7 }
        repeat(3) {
            viewer.next()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
        }
    }

    @Test
    fun aWideFirstPageTurnsTheScreenOnceItsSizeIsRead() {
        val viewer = launch(book(numbered(1, 6), setOf("Tale 001-001")))
        sizesRead()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, viewer.requestedOrientation)
        viewer.next()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
    }

    /** Turns the screen to [qualifier] (`land` or `port`), as the system does after a request. */
    private fun ViewerActivity.turn(qualifier: String) {
        RuntimeEnvironment.setQualifiers(qualifier)
        onConfigurationChanged(resources.configuration)
        idle()
    }

    /** Gives the device an accelerometer, as the reader's sensor watch finds it. */
    private fun accelerometer(): ShadowSensorManager =
        shadowOf(context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).apply {
            addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        }

    /** Gravity along the device's [x], [y] and [z] axes: (0, 9.8, 0) upright, (0, 0, 9.8) lying flat. */
    private fun ShadowSensorManager.gravity(x: Float, y: Float, z: Float) {
        val event = ShadowSensorManager.createSensorEvent(3, Sensor.TYPE_ACCELEROMETER)
        event.values[0] = x
        event.values[1] = y
        event.values[2] = z
        sendSensorEventToListeners(event)
        idle()
    }

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun lyingFlatThePortraitTurnedFromComesBackAndStaysUntilPickedUp() {
        val sensors = accelerometer()
        val viewer = launch(book(numbered(1, 6), setOf("Tale 001-003")))
        sizesRead()
        repeat(2) { viewer.next() }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, viewer.requestedOrientation)
        assertEquals(1, sensors.listeners.size)
        sensors.gravity(0f, 0f, 9.8f)
        viewer.turn("land")
        viewer.next()
        // Flat, the sensor would leave the screen in landscape: portrait comes back explicitly.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, viewer.requestedOrientation)
        viewer.turn("port")
        sensors.gravity(0.2f, 0.1f, 9.8f)
        idleFor(2_000)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, viewer.requestedOrientation)
        // Picked up: once the sensor settles, it decides again, and is let go.
        sensors.gravity(0f, 9.8f, 0f)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, viewer.requestedOrientation)
        idleFor(500)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
        assertTrue(sensors.listeners.isEmpty())
    }

    @Test
    fun heldUpTheSensorTakesOverAtOnce() {
        val sensors = accelerometer()
        val viewer = launch(book(numbered(1, 6), setOf("Tale 001-003")))
        sizesRead()
        repeat(2) { viewer.next() }
        // Held in landscape for the spread.
        sensors.gravity(9.8f, 0f, 0f)
        idleFor(500)
        viewer.turn("land")
        viewer.next()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
        assertTrue(sensors.listeners.isEmpty())
    }

    @Test
    fun theSensorIsReadOnlyWhileTurnedAndShown() {
        val sensors = accelerometer()
        val controller = Robolectric.buildActivity(
            ViewerActivity::class.java,
            Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book(numbered(1, 6), setOf("Tale 001-002")).absolutePath)
        ).setup()
        val viewer = controller.get()
        waitUntil { viewer.findViewById<View>(R.id.viewer_progress).visibility != View.VISIBLE }
        sizesRead()
        assertTrue(sensors.listeners.isEmpty())
        viewer.next()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, viewer.requestedOrientation)
        assertEquals(1, sensors.listeners.size)
        controller.pause().stop()
        assertTrue(sensors.listeners.isEmpty())
        controller.start().resume()
        assertEquals(1, sensors.listeners.size)
    }

    @Test
    fun aStillDeviceShownAgainHandsBackOnceItsReadingRepeats() {
        val sensors = accelerometer()
        val controller = Robolectric.buildActivity(
            ViewerActivity::class.java,
            Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book(numbered(1, 6), setOf("Tale 001-002")).absolutePath)
        ).setup()
        val viewer = controller.get()
        waitUntil { viewer.findViewById<View>(R.id.viewer_progress).visibility != View.VISIBLE }
        sizesRead()
        viewer.next()
        // Standing still in landscape for the spread.
        sensors.gravity(9.8f, 0f, 0f)
        idleFor(500)
        viewer.turn("land")
        controller.pause().stop()
        controller.start().resume()
        // The same reading as before it stopped: still enough to tell the way up.
        sensors.gravity(9.8f, 0f, 0f)
        idleFor(500)
        viewer.next()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
        assertTrue(sensors.listeners.isEmpty())
    }

    @Test
    fun keptOverAStopTheSensorStillLetsGo() {
        val sensors = accelerometer()
        val controller = Robolectric.buildActivity(
            ViewerActivity::class.java,
            Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book(numbered(1, 6), setOf("Tale 001-002")).absolutePath)
        ).setup()
        val viewer = controller.get()
        waitUntil { viewer.findViewById<View>(R.id.viewer_progress).visibility != View.VISIBLE }
        sizesRead()
        viewer.next()
        sensors.gravity(0f, 0f, 9.8f)
        viewer.turn("land")
        viewer.next()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, viewer.requestedOrientation)
        controller.pause().stop()
        assertTrue(sensors.listeners.isEmpty())
        controller.start().resume()
        assertEquals(1, sensors.listeners.size)
        sensors.gravity(0f, 9.8f, 0f)
        idleFor(500)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
        assertTrue(sensors.listeners.isEmpty())
    }

    @Test
    fun reclinedAsFarAsTheSystemStillTurnsTheSensorTakesOver() {
        val sensors = accelerometer()
        val viewer = launch(book(numbered(1, 6), setOf("Tale 001-003")))
        sizesRead()
        repeat(2) { viewer.next() }
        // Lying back 64 degrees from upright, in landscape: past where orientation events give up.
        sensors.gravity(9.8f * 0.438f, 0f, 9.8f * 0.899f)
        idleFor(500)
        viewer.turn("land")
        viewer.next()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
    }

    @Test
    fun withAutoRotateOffTheLockComesBackWithoutTheSensor() {
        android.provider.Settings.System.putInt(context.contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0)
        val sensors = accelerometer()
        val viewer = launch(book(numbered(1, 6), setOf("Tale 001-003")))
        sizesRead()
        repeat(2) { viewer.next() }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, viewer.requestedOrientation)
        assertTrue(sensors.listeners.isEmpty())
        viewer.turn("land")
        viewer.next()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
        assertTrue(sensors.listeners.isEmpty())
    }

    @Test
    fun lyingFlatUpsideDownTheReversePortraitComesBack() {
        @Suppress("DEPRECATION")
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
        shadowOf(display).setRotation(android.view.Surface.ROTATION_180)
        val sensors = accelerometer()
        val viewer = launch(book(numbered(1, 6), setOf("Tale 001-003")))
        sizesRead()
        repeat(2) { viewer.next() }
        sensors.gravity(0f, 0f, 9.8f)
        shadowOf(display).setRotation(android.view.Surface.ROTATION_90)
        viewer.turn("land")
        viewer.next()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, viewer.requestedOrientation)
    }

    @Test
    fun landscapeOnlyKeepsOnePageAScreenWhileTurnedForAWidePage() {
        prefs.twoPage = TwoPage.LANDSCAPE
        val viewer = launch(book(numbered(1, 8), setOf("Tale 001-003")))
        sizesRead()
        assertEquals(8, viewer.items())
        repeat(2) { viewer.next() }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, viewer.requestedOrientation)
        viewer.turn("land")
        // Turned for the wide page alone: still one page a screen, not pairs.
        assertEquals(8, viewer.items())
        assertEquals("3 / 8", viewer.indicator())
        viewer.next()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, viewer.requestedOrientation)
        viewer.turn("port")
        assertEquals(8, viewer.items())
        assertEquals("4 / 8", viewer.indicator())
        viewer.next()
        assertEquals("5 / 8", viewer.indicator())
    }

    @Test
    fun landscapeOnlyPairsAfterATurnTheReaderMakes() {
        prefs.twoPage = TwoPage.LANDSCAPE
        val viewer = launch(book(numbered(1, 8)))
        sizesRead()
        viewer.turn("land")
        assertTrue(viewer.items() < 8)
        viewer.turn("port")
    }

    @Test
    fun aRecreatedReaderKeepsTheOrientationTheReaderPicked() {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_PATH, book(numbered(1, 6)).absolutePath)
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
        waitUntil { controller.get().findViewById<View>(R.id.viewer_progress).visibility != View.VISIBLE }
        ReaderSheet(controller.get()).tap(R.id.btn_orient_portrait).dialog.dismiss()
        controller.recreate()
        val viewer = controller.get()
        waitUntil { viewer.findViewById<View>(R.id.viewer_progress).visibility != View.VISIBLE }
        val group = ReaderSheet(viewer).view<MaterialButtonToggleGroup>(R.id.sheet_orientation)
        assertEquals(R.id.btn_orient_portrait, group.checkedButtonId)
    }

    @Test
    fun spreadsSplitOrMergeIsOffInTwoPageReading() {
        prefs.twoPage = TwoPage.ON
        val sheet = ReaderSheet(launch(book(numbered(1, 6))))
        assertFalse(sheet.view<View>(R.id.btn_spreads_split).isEnabled)
        assertFalse(sheet.view<View>(R.id.btn_spreads_merge).isEnabled)
        assertTrue(sheet.view<View>(R.id.btn_two_off).isEnabled)
        sheet.tap(R.id.btn_two_off)
        assertTrue(sheet.view<View>(R.id.btn_spreads_split).isEnabled)
    }

    @Test
    fun aSolidBookLearnsAWidePageAsItLoads() {
        prefs.spreads = SpreadDisplay.SPLIT
        val viewer = launch(solidBook(numbered(1, 10), setOf("Tale 001-003")))
        // Nothing read ahead: the wide page is known once it loads, with the pages after the first.
        waitUntil {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            viewer.items() == 11
        }
        assertEquals(11, viewer.items())
    }
}
