package com.infinity.comicreader.ui

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.infinity.comicreader.R
import com.infinity.comicreader.reader.Calibration
import com.infinity.comicreader.reader.CalibrationEnd
import com.infinity.comicreader.reader.CalibrationProgress
import com.infinity.comicreader.reader.Calibrator
import com.infinity.comicreader.reader.Candidate
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CalibrationScreenActivityTest {
    private lateinit var fake: FakeCalibration
    private var now = DeviceReadings(saverOn = false, thermalStatus = 0, batteryPercent = 80)

    @Before
    fun setUp() {
        fake = FakeCalibration()
        CalibrationScreenActivity.control = fake
        CalibrationScreenActivity.readings = { now }
    }

    @After
    fun tearDown() {
        CalibrationScreenActivity.control = Calibrator
        CalibrationScreenActivity.readings = Calibrator::readings
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(): ActivityController<CalibrationScreenActivity> =
        Robolectric.buildActivity(CalibrationScreenActivity::class.java).setup().also { idle() }

    private fun CalibrationScreenActivity.shows(id: Int) = findViewById<View>(id).visibility == View.VISIBLE
    private fun CalibrationScreenActivity.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun CalibrationScreenActivity.tap(id: Int) {
        findViewById<View>(id).performClick()
        idle()
    }

    @Test
    fun theFirstRunStartsAtOnceWithNothingOldToKeep() {
        val a = launch().get()
        assertEquals(listOf(false), fake.starts)
        assertEquals(CalibrationScreenActivity.State.RUNNING, a.state)
        assertEquals("Optimizing for this device", a.text(R.id.calibration_title))
        assertTrue(a.text(R.id.calibration_text).endsWith("About 25 seconds."))
        assertTrue(a.shows(R.id.calibration_cancel))
        assertTrue(a.shows(R.id.calibration_background))
        assertFalse(a.shows(R.id.calibration_keep))
        assertFalse(a.shows(R.id.calibration_close))
        assertEquals("Getting ready", a.text(R.id.calibration_step))
        // The screen stays on while it runs.
        assertTrue(a.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
    }

    @Test
    fun laterRunsOfferToKeepTheOldCalibration() {
        fake.calibrated = true
        val a = launch().get()
        assertTrue(a.shows(R.id.calibration_keep))
        a.tap(R.id.calibration_keep)
        assertEquals(1, fake.canceled)
        assertEquals(1, fake.skipped)
        assertEquals(0, fake.declined)
        assertTrue(a.isFinishing)
    }

    @Test
    fun cancelStopsThisRunUntilTheNextLaunch() {
        val a = launch().get()
        a.tap(R.id.calibration_cancel)
        assertEquals(1, fake.canceled)
        assertEquals(1, fake.declined)
        assertEquals(0, fake.skipped)
        assertTrue(a.isFinishing)
    }

    @Test
    fun continueInBackgroundLeavesTheRunGoing() {
        val a = launch().get()
        a.tap(R.id.calibration_background)
        assertEquals(1, fake.backgrounded)
        assertEquals(0, fake.canceled)
        assertTrue(a.isFinishing)
    }

    @Test
    fun progressNamesTheSetupAndRound() {
        val a = launch().get()
        fake.move(CalibrationProgress(timing = true, done = 10, total = 50, candidate = Candidate(Candidate.Kind.PIXELS, 4, pinned = true), round = 2))
        idle()
        assertEquals("Resizing on 4 big cores", a.text(R.id.calibration_step))
        assertEquals("Round 2 of 5", a.text(R.id.calibration_round))
        assertEquals(200, a.findViewById<LinearProgressIndicator>(R.id.calibration_bar).progress)
    }

    @Test
    fun backDoesNotLeaveWhileItRuns() {
        val a = launch().get()
        a.onBackPressedDispatcher.onBackPressed()
        assertFalse(a.isFinishing)
        assertEquals(0, fake.canceled)
    }

    @Test
    fun theScreenClosesOnceDone() {
        val a = launch().get()
        fake.end(CalibrationEnd(ok = true, why = null))
        idle()
        assertTrue(a.isFinishing)
    }

    @Test
    fun aFailedRunSaysWhyAndWhatStays() {
        val a = launch().get()
        fake.end(CalibrationEnd(ok = false, why = "${Calibration.TOO_WARM} (thermal status 2) while timing"))
        idle()
        assertEquals(CalibrationScreenActivity.State.FAILED, a.state)
        assertEquals("The device got too warm while measuring. Standard settings stay in use; you will be asked again next time.", a.text(R.id.calibration_text))
        assertFalse(a.shows(R.id.calibration_progress))
        assertFalse(a.shows(R.id.calibration_background))
        assertTrue(a.shows(R.id.calibration_close))
        // Not started again on its own.
        assertEquals(1, fake.starts.size)
        a.tap(R.id.calibration_close)
        assertEquals(1, fake.declined)
        assertTrue(a.isFinishing)
    }

    @Test
    fun aRunStoppedByLeavingStartsAgainWhenBack() {
        val c = launch()
        c.pause()
        fake.end(CalibrationEnd(ok = false, why = Calibration.AWAY))
        idle()
        assertEquals(1, fake.starts.size)
        c.resume()
        idle()
        assertEquals(2, fake.starts.size)
        assertEquals(CalibrationScreenActivity.State.RUNNING, c.get().state)
        // Stopped by something else while in front: started again at once too.
        fake.end(CalibrationEnd(ok = false, why = Calibration.READING))
        idle()
        assertEquals(3, fake.starts.size)
    }

    @Test
    fun aRunAlreadyGoingIsShownNotStartedAgain() {
        fake.running = true
        val a = launch().get()
        assertTrue(fake.starts.isEmpty())
        assertEquals(CalibrationScreenActivity.State.RUNNING, a.state)
    }

    @Test
    fun batterySaverAsksFirst() {
        now = now.copy(saverOn = true)
        val a = launch().get()
        assertTrue(fake.starts.isEmpty())
        assertEquals(CalibrationScreenActivity.State.SAVER, a.state)
        assertEquals("Battery saver is on", a.text(R.id.calibration_title))
        assertTrue(a.shows(R.id.calibration_saver_settings))
        assertTrue(a.shows(R.id.calibration_anyway))
        assertTrue(a.shows(R.id.calibration_cancel))
        assertFalse(a.shows(R.id.calibration_background))
        assertFalse(a.shows(R.id.calibration_progress))
        a.tap(R.id.calibration_anyway)
        assertEquals(listOf(true), fake.starts)
        assertEquals(CalibrationScreenActivity.State.RUNNING, a.state)
    }

    @Test
    fun batterySaverSettingsOpenAndTheRunStartsOnceItIsOff() {
        now = now.copy(saverOn = true)
        val c = launch()
        val a = c.get()
        a.tap(R.id.calibration_saver_settings)
        assertEquals(Settings.ACTION_BATTERY_SAVER_SETTINGS, shadowOf(a).nextStartedActivity.action)
        // Switched off from the quick settings, the screen still in front.
        now = now.copy(saverOn = false)
        a.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED).setPackage(a.packageName))
        idle()
        assertEquals(listOf(false), fake.starts)
    }

    @Test
    fun batterySaverOffOnReturnFromSettingsStartsTheRun() {
        now = now.copy(saverOn = true)
        val c = launch()
        c.pause()
        now = now.copy(saverOn = false)
        c.resume()
        idle()
        assertEquals(listOf(false), fake.starts)
    }

    @Test
    fun cancelAtTheSaverQuestionDeclines() {
        now = now.copy(saverOn = true)
        val a = launch().get()
        a.tap(R.id.calibration_cancel)
        assertEquals(1, fake.declined)
        assertTrue(a.isFinishing)
        assertTrue(fake.starts.isEmpty())
    }

    @Test
    fun heatBlocksWithAPlainWord() {
        now = now.copy(thermalStatus = Calibration.HOT, saverOn = true)
        val a = launch().get()
        assertTrue(fake.starts.isEmpty())
        assertEquals(CalibrationScreenActivity.State.HOT, a.state)
        assertEquals("Too warm to measure", a.text(R.id.calibration_title))
        assertTrue(a.shows(R.id.calibration_close))
        assertFalse(a.shows(R.id.calibration_cancel))
        assertFalse(a.shows(R.id.calibration_anyway))
        a.onBackPressedDispatcher.onBackPressed()
        assertEquals(1, fake.declined)
        assertTrue(a.isFinishing)
    }

    @Test
    fun lowBatteryBlocksWithAPlainWord() {
        now = now.copy(batteryPercent = 15)
        val a = launch().get()
        assertTrue(fake.starts.isEmpty())
        assertEquals(CalibrationScreenActivity.State.LOW_BATTERY, a.state)
        assertEquals("Measuring needs at least 20 % battery. Try again once it is charged.", a.text(R.id.calibration_text))
        a.tap(R.id.calibration_close)
        assertEquals(1, fake.declined)
        assertTrue(a.isFinishing)
    }

    @Test
    fun itListensOnlyWhileStarted() {
        val c = launch()
        assertEquals(1, fake.listening)
        c.pause().stop()
        assertEquals(0, fake.listening)
    }

    @Test
    fun setupsHavePlainNames() {
        val res = ApplicationProvider.getApplicationContext<Application>().resources
        fun name(kind: Candidate.Kind, n: Int, pinned: Boolean, gpu: Boolean = false) = SetupNames.of(res, Candidate(kind, n, pinned, gpu))
        assertEquals("Resizing on 1 big core", name(Candidate.Kind.PIXELS, 1, true))
        assertEquals("Resizing on 8 cores", name(Candidate.Kind.PIXELS, 8, false))
        assertEquals("Resizing on the GPU", name(Candidate.Kind.PIXELS, 1, false, gpu = true))
        assertEquals("JPEG XL on 2 big cores", name(Candidate.Kind.JXL, 2, true))
        assertEquals("JPEG XL on 8 cores", name(Candidate.Kind.JXL, 8, false))
        assertEquals("JPEG on big cores", name(Candidate.Kind.JPEG, 1, true))
        assertEquals("JPEG on any core", name(Candidate.Kind.JPEG, 1, false))
    }
}
