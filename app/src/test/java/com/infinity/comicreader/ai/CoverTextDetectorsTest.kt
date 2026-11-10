package com.infinity.comicreader.ai

import android.app.Application
import android.graphics.Bitmap
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.ai.CoverTextDetectors.Use
import com.infinity.comicreader.ai.CoverTextDetectors.Why
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.SmartCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which text detector covers use: ML Kit only when turned on, available and working, never for right-to-left books. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoverTextDetectorsTest {
    private val image: Bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    private val line = listOf(SmartCrop.TextBox(1.0, 2.0, 30.0, 8.0, 1f))

    /** Play services as a test sets it: there or not, the module installed or not, and requests answered by hand. */
    private class FakePlay(var there: Boolean = true, var installed: Boolean? = true) : PlayServices {
        var asked = 0
        val requests = mutableListOf<(Boolean) -> Unit>()

        override fun available() = there

        override fun moduleInstalled(): Boolean? {
            asked++
            return installed
        }

        override fun requestModule(done: (Boolean) -> Unit) {
            requests += done
        }
    }

    /** A detector known as [id] that finds [found] and counts its runs. */
    private class Fake(override val id: String, var found: () -> List<SmartCrop.TextBox>?) : CoverTextDetector {
        var runs = 0

        override fun lines(src: Bitmap): List<SmartCrop.TextBox>? {
            runs++
            return found()
        }
    }

    private val ml = Fake("mlkit") { line }
    private val pp = Fake(TextDetector.ID) { line }
    private var on = true
    private var mayDownload = true

    private fun detectors(play: PlayServices, mlKit: CoverTextDetector? = ml) =
        CoverTextDetectors({ on }, play, mlKit?.let { { it } }, { pp }, { mayDownload })

    @Test
    fun mlKitIsOffByDefault() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        assertFalse(Prefs(context).coverMlKit)
        on = false
        val d = detectors(FakePlay())
        assertEquals(CoverTextDetectors.Status(Use.PP_OCR, Why.OFF), d.status())
        assertEquals(TextDetector.ID, d.session().id)
    }

    @Test
    fun mlKitWhenTurnedOnAndPlayServicesIsThere() {
        val play = FakePlay()
        val d = detectors(play)
        assertTrue(d.offered())
        assertEquals(CoverTextDetectors.Status(Use.ML_KIT, null), d.status())
        assertEquals("mlkit", d.session().id)
        assertEquals(line, d.session().lines(image))
        assertEquals(1, ml.runs)
    }

    @Test
    fun withoutPlayServicesPpOcrAndNoSetting() {
        val d = detectors(FakePlay(there = false))
        assertFalse(d.offered())
        assertEquals(CoverTextDetectors.Status(Use.PP_OCR, Why.UNAVAILABLE), d.status())
        assertEquals(TextDetector.ID, d.session().id)
    }

    @Test
    fun withoutMlKitInTheAppPpOcr() {
        val d = detectors(FakePlay(), mlKit = null)
        assertEquals(CoverTextDetectors.Status(Use.PP_OCR, Why.UNAVAILABLE), d.status())
    }

    @Test
    fun theDebugFlagPlaysADeviceWithoutPlayServices() {
        var absent = true
        val play = FakePlay()
        val d = detectors(WithoutPlayServices(play) { absent })
        assertFalse(d.offered())
        assertEquals(TextDetector.ID, d.session().id)
        absent = false
        assertTrue(d.offered())
        assertEquals("mlkit", d.session().id)
    }

    @Test
    fun booksMarkedRightToLeftUsePpOcr() {
        val d = detectors(FakePlay())
        assertEquals(TextDetector.ID, d.forBook { true }.id)
        assertEquals("mlkit", d.forBook { false }.id)
        assertEquals(TextDetector.ID, d.rightToLeft().id)
        // With PP-OCR picked, the book's direction is not looked up.
        on = false
        assertEquals(TextDetector.ID, d.forBook { error("not asked") }.id)
    }

    @Test
    fun aMissingModuleIsRequestedAndMlKitWaitsForIt() {
        val play = FakePlay(installed = false)
        val d = detectors(play)
        val detector = d.session()
        assertNull("did not run", detector.lines(image))
        assertEquals(1, play.requests.size)
        assertEquals(CoverTextDetectors.Status(Use.ML_KIT, null, downloading = true), d.status())
        // Still the session's pick while it downloads: covers keep their detector.
        assertEquals("mlkit", d.session().id)
        assertNull(detector.lines(image))
        assertEquals("asked once", 1, play.asked)
        assertEquals(1, play.requests.size)
        assertEquals(0, ml.runs)
        play.requests.single()(true)
        assertEquals(line, detector.lines(image))
        assertEquals(CoverTextDetectors.Status(Use.ML_KIT, null), d.status())
    }

    @Test
    fun aDownloadTheNetworkPolicyHoldsBackLeavesPpOcrForTheSession() {
        mayDownload = false
        val play = FakePlay(installed = false)
        val d = detectors(play)
        assertNull(d.session().lines(image))
        assertTrue(play.requests.isEmpty())
        assertEquals(CoverTextDetectors.Status(Use.PP_OCR, Why.HELD_BACK), d.status())
        mayDownload = true
        assertEquals(TextDetector.ID, d.session().id)
    }

    @Test
    fun aFailedDownloadLeavesPpOcrForTheSession() {
        val play = FakePlay(installed = false)
        val d = detectors(play)
        d.session().lines(image)
        play.requests.single()(false)
        assertEquals(CoverTextDetectors.Status(Use.PP_OCR, Why.MODULE_FAILED), d.status())
        assertEquals(TextDetector.ID, d.session().id)
    }

    @Test
    fun playServicesNotTellingAboutTheModuleLeavesPpOcr() {
        val d = detectors(FakePlay(installed = null))
        assertNull(d.session().lines(image))
        assertEquals(CoverTextDetectors.Status(Use.PP_OCR, Why.MODULE_FAILED), d.status())
    }

    @Test
    fun mlKitFailingInARowFallsBackToPpOcrForTheSession() {
        val d = detectors(FakePlay())
        val detector = d.session()
        ml.found = { null }
        repeat(CoverTextDetectors.FAILURES - 1) { assertNull(detector.lines(image)) }
        // A success starts the count over.
        ml.found = { line }
        assertEquals(line, detector.lines(image))
        ml.found = { throw IllegalStateException("broken") }
        repeat(CoverTextDetectors.FAILURES - 1) { assertNull(detector.lines(image)) }
        assertEquals(Use.ML_KIT, d.status().use)
        assertNull(detector.lines(image))
        assertEquals(CoverTextDetectors.Status(Use.PP_OCR, Why.FAILED), d.status())
        assertEquals(TextDetector.ID, d.session().id)
        // A detector handed out before does not run ML Kit again.
        val runs = ml.runs
        assertNull(detector.lines(image))
        assertEquals(runs, ml.runs)
    }

    @Test
    fun turningTheSettingOnAgainGivesMlKitANewTry() {
        val d = detectors(FakePlay())
        ml.found = { null }
        repeat(CoverTextDetectors.FAILURES) { d.session().lines(image) }
        assertEquals(Why.FAILED, d.status().why)
        on = false
        assertEquals(Why.OFF, d.status().why)
        on = true
        assertEquals(CoverTextDetectors.Status(Use.ML_KIT, null), d.status())
    }

    @Test
    fun turnedOffMlKitDoesNotRun() {
        val d = detectors(FakePlay())
        val detector = d.session()
        on = false
        d.status()
        assertNull(detector.lines(image))
        assertEquals(0, ml.runs)
    }
}
