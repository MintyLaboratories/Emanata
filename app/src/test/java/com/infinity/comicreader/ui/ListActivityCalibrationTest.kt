package com.infinity.comicreader.ui

import android.Manifest
import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.testing.idleUntil
import com.infinity.comicreader.reader.CalibrationOffer
import com.infinity.comicreader.reader.Calibrator
import com.infinity.comicreader.reader.DeviceReadings
import com.infinity.comicreader.testing.FakeCalibration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The library offers calibration: the screen on its own the first time, a banner later. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ListActivityCalibrationTest {
    private lateinit var fake: FakeCalibration
    private var saverOn = false

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        for (name in listOf("ui", "library", "favorite_kinds")) context.getSharedPreferences(name, 0).edit().clear().commit()
        fake = FakeCalibration()
        CalibrationScreenActivity.control = fake
        CalibrationScreenActivity.readings = { DeviceReadings(saverOn = saverOn, thermalStatus = 0, batteryPercent = 80) }
    }

    @After
    fun tearDown() {
        awaitLeftoverWork()
        CalibrationScreenActivity.control = Calibrator
        CalibrationScreenActivity.readings = Calibrator::readings
    }

    /** Runs what is due on the main thread: calibration's news and the banner it shows come there. */
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Starts the library and waits until its folder is listed. */
    private fun launch() = Robolectric.buildActivity(ListActivity::class.java).setup().also { c ->
        idleUntil("the folder listed") { c.get().listJob?.isCompleted == true }
    }

    private fun ListActivity.banner() = findViewById<View>(R.id.calibration_banner)

    private fun ListActivity.screenStarted(): Boolean {
        val shadow = shadowOf(this)
        while (true) {
            val next = shadow.nextStartedActivity ?: return false
            if (next.component?.className == CalibrationScreenActivity::class.java.name) return true
        }
    }

    @Test
    fun theFirstCalibrationOpensTheScreenOnceTheLibraryHasFocus() {
        fake.offered = CalibrationOffer.SCREEN
        val c = launch()
        val a = c.get()
        // Not before, though resumed and hearing calibration change: a dialog over the library, or the library not drawn yet.
        fake.move(com.infinity.comicreader.reader.CalibrationProgress())
        idle()
        assertFalse(a.screenStarted())
        assertEquals(0, fake.shownTimes)
        c.windowFocusChanged(true)
        idle()
        assertTrue(a.screenStarted())
        assertEquals(1, fake.shownTimes)
        assertEquals(View.GONE, a.banner().visibility)
        // Once a launch.
        c.pause().resume()
        c.windowFocusChanged(true)
        idle()
        assertFalse(a.screenStarted())
    }

    @Test
    fun aDueCalibrationGetsTheBanner() {
        fake.offered = CalibrationOffer.BANNER
        val a = launch().get()
        assertEquals(View.VISIBLE, a.banner().visibility)
        assertEquals("Optimize for this device (~25 s)", a.findViewById<TextView>(R.id.calibration_banner_text).text.toString())
        assertFalse(a.screenStarted())
        assertEquals(false, fake.saverAsked)
    }

    @Test
    fun startOpensTheScreen() {
        fake.offered = CalibrationOffer.BANNER
        val a = launch().get()
        a.findViewById<View>(R.id.calibration_banner_start).performClick()
        assertTrue(a.screenStarted())
        assertEquals(View.GONE, a.banner().visibility)
    }

    @Test
    fun laterHidesItUntilTheNextLaunch() {
        fake.offered = CalibrationOffer.BANNER
        val a = launch().get()
        a.findViewById<View>(R.id.calibration_banner_later).performClick()
        assertEquals(1, fake.declined)
        assertEquals(View.GONE, a.banner().visibility)
        assertEquals(0, fake.skipped)
    }

    @Test
    fun skipKeepsTheOldCalibration() {
        fake.offered = CalibrationOffer.BANNER
        val a = launch().get()
        a.findViewById<View>(R.id.calibration_banner_skip).performClick()
        assertEquals(1, fake.skipped)
        assertEquals(0, fake.declined)
        assertEquals(View.GONE, a.banner().visibility)
    }

    @Test
    fun theBannerFollowsChangesAndHidesWhileARunGoes() {
        val a = launch().get()
        assertEquals(View.GONE, a.banner().visibility)
        fake.offered = CalibrationOffer.BANNER
        fake.start(underSaver = false)
        idle()
        assertEquals(View.GONE, a.banner().visibility)
        fake.end(com.infinity.comicreader.reader.CalibrationEnd(ok = false, why = "disturbed 31 times"))
        idle()
        assertEquals(View.VISIBLE, a.banner().visibility)
    }

    @Test
    fun withNothingOnOfferNothingShows() {
        val c = launch()
        val a = c.get()
        c.windowFocusChanged(true)
        idle()
        assertEquals(View.GONE, a.banner().visibility)
        assertFalse(a.screenStarted())
    }
}
