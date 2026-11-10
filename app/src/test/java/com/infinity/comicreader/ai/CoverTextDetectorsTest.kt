package com.infinity.comicreader.ai

import android.app.Application
import android.graphics.Bitmap
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.ai.CoverTextDetectors.Use
import com.infinity.comicreader.ai.CoverTextDetectors.Why
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.SmartCrop
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Which text detector covers use: ML Kit only when turned on, available and working, never for right-to-left books. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoverTextDetectorsTest {
    private val image: Bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    private val line = listOf(SmartCrop.TextBox(1.0, 2.0, 30.0, 8.0, 1f))

    /** Play services as a test sets it: there or not, the module installed or not, and requests answered by hand. */
    private class FakePlay(var there: Boolean = true, val installed: () -> Boolean? = { true }) : PlayServices {
        @Volatile
        var asked = 0
        val requests = mutableListOf<(Boolean) -> Unit>()
        var canceled = 0

        override fun available() = there

        override fun moduleInstalled(): Boolean? {
            asked++
            return installed()
        }

        override fun requestModule(done: (Boolean) -> Unit): () -> Unit {
            requests += done
            return { canceled++ }
        }
    }

    private fun play(installed: Boolean?) = FakePlay(installed = { installed })

    /** A detector known as [id] that finds [found], looks at images twice their size, and counts its runs. */
    private class Fake(override val id: String, var found: () -> List<SmartCrop.TextBox>?) : CoverTextDetector {
        var runs = 0

        override fun inputSize(w: Int, h: Int): IntArray = intArrayOf(w * 2, h * 2)

        override fun lines(src: Bitmap): List<SmartCrop.TextBox>? {
            runs++
            return found()
        }
    }

    private val ml = Fake("mlkit") { line }
    private val pp = Fake(TextDetector.ID) { line }
    private var on = true
    private var mayDownload = true
    private var clock = 1_000L
    private var made = 0

    private fun detectors(play: PlayServices, mlKit: (() -> CoverTextDetector)? = { ml }) = CoverTextDetectors(
        { on }, play,
        mlKit?.let { make ->
            CoverTextDetectors.MlKit("mlkit") {
                made++
                make()
            }
        },
        { pp }, { mayDownload }, { clock }
    )

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
        val d = detectors(FakePlay())
        assertTrue(d.offered())
        assertEquals(CoverTextDetectors.Status(Use.ML_KIT, null), d.status())
        assertEquals("mlkit", d.session().id)
        assertArrayEquals("ML Kit's input", intArrayOf(20, 40), d.session().inputSize(10, 20))
        assertEquals(line, d.session().lines(image))
        assertEquals(1, ml.runs)
    }

    @Test
    fun theIdIsKnownBeforeMlKitIsMadeOrItsModuleAskedAbout() {
        val play = FakePlay()
        val d = detectors(play)
        assertEquals("mlkit", d.session().id)
        assertEquals(0, made)
        assertEquals(0, play.asked)
        d.session().lines(image)
        d.session().lines(image)
        assertEquals("made once", 1, made)
        assertEquals("asked once", 1, play.asked)
    }

    @Test
    fun mlKitNotStartingLeavesPpOcrForTheSession() {
        val d = detectors(FakePlay(), mlKit = { throw IllegalStateException("no context") })
        val detector = d.session()
        assertEquals("mlkit", detector.id)
        assertArrayEquals("PP-OCR's input", SmartCrop.inputSize(10, 20), detector.inputSize(10, 20))
        assertNull(detector.lines(image))
        assertEquals(CoverTextDetectors.Status(Use.PP_OCR, Why.FAILED), d.status())
        assertEquals(TextDetector.ID, d.session().id)
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
    fun theDebugFlagSimulatesADeviceWithoutPlayServices() {
        var absent = true
        val d = detectors(WithoutPlayServices(FakePlay()) { absent })
        assertFalse(d.offered())
        assertEquals(TextDetector.ID, d.session().id)
        absent = false
        assertTrue(d.offered())
        assertEquals("mlkit", d.session().id)
    }

    @Test
    fun booksMarkedRightToLeftUsePpOcr() {
        val d = detectors(FakePlay())
        assertEquals(TextDetector.ID, d.forBook(rightToLeft = true).id)
        assertEquals("mlkit", d.forBook(rightToLeft = false).id)
        assertEquals(TextDetector.ID, d.rightToLeft().id)
        on = false
        assertEquals(TextDetector.ID, d.forBook(rightToLeft = false).id)
    }

    @Test
    fun aMissingModuleIsRequestedAndMlKitWaitsForIt() {
        val play = play(installed = false)
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
        assertEquals(0, d.arrivals)
        play.requests.single()(true)
        assertEquals(1, d.arrivals)
        assertEquals(line, detector.lines(image))
        assertEquals(CoverTextDetectors.Status(Use.ML_KIT, null), d.status())
    }

    @Test
    fun aDownloadingModuleIsAskedAboutAgainNowAndThen() {
        var installed = false
        val play = FakePlay(installed = { installed })
        val d = detectors(play)
        assertNull(d.session().lines(image))
        installed = true
        clock += CoverTextDetectors.RECHECK_MS - 1
        assertNull(d.session().lines(image))
        assertEquals(1, play.asked)
        clock += 1
        assertEquals(line, d.session().lines(image))
        assertEquals(2, play.asked)
        assertEquals(1, d.arrivals)
        assertEquals("the request is dropped", 1, play.canceled)
        // A late answer to it changes nothing.
        play.requests.single()(false)
        assertEquals(CoverTextDetectors.Status(Use.ML_KIT, null), d.status())
    }

    @Test
    fun aDownloadNotDoneInTimeLeavesPpOcrForTheSession() {
        val play = play(installed = false)
        val d = detectors(play)
        d.session().lines(image)
        clock += CoverTextDetectors.DOWNLOAD_WAIT_MS - 1
        assertTrue(d.status().downloading)
        clock += 1
        assertEquals(CoverTextDetectors.Status(Use.PP_OCR, Why.MODULE_FAILED), d.status())
        assertEquals(1, play.canceled)
        assertEquals(TextDetector.ID, d.session().id)
        play.requests.single()(true)
        assertEquals(Why.MODULE_FAILED, d.status().why)
    }

    @Test
    fun coversAskingMeanwhileWaitForTheFirstCheck() {
        val asking = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val play = FakePlay(installed = {
            asking.countDown()
            answer.await(5, TimeUnit.SECONDS)
            true
        })
        val detector = detectors(play).session()
        val found = arrayOfNulls<List<SmartCrop.TextBox>>(3)
        val first = thread { found[0] = detector.lines(image) }
        assertTrue(asking.await(5, TimeUnit.SECONDS))
        val others = (1..2).map { i -> thread { found[i] = detector.lines(image) } }
        Thread.sleep(100)
        answer.countDown()
        (others + first).forEach { it.join(5_000) }
        assertEquals("asked once", 1, play.asked)
        found.forEach { assertEquals(line, it) }
    }

    @Test
    fun aDownloadTheNetworkPolicyHoldsBackLeavesPpOcrForTheSession() {
        mayDownload = false
        val play = play(installed = false)
        val d = detectors(play)
        assertNull(d.session().lines(image))
        assertTrue(play.requests.isEmpty())
        assertEquals(CoverTextDetectors.Status(Use.PP_OCR, Why.HELD_BACK), d.status())
        mayDownload = true
        assertEquals(TextDetector.ID, d.session().id)
    }

    @Test
    fun aFailedDownloadLeavesPpOcrForTheSession() {
        val play = play(installed = false)
        val d = detectors(play)
        d.session().lines(image)
        play.requests.single()(false)
        assertEquals(CoverTextDetectors.Status(Use.PP_OCR, Why.MODULE_FAILED), d.status())
        assertEquals(TextDetector.ID, d.session().id)
    }

    @Test
    fun playServicesNotTellingAboutTheModuleTwiceLeavesPpOcr() {
        val play = play(installed = null)
        val d = detectors(play)
        assertNull(d.session().lines(image))
        assertEquals("one miss is not enough", Use.ML_KIT, d.status().use)
        assertNull(d.session().lines(image))
        assertEquals(CoverTextDetectors.CHECK_TRIES, play.asked)
        assertEquals(CoverTextDetectors.Status(Use.PP_OCR, Why.MODULE_FAILED), d.status())
    }

    @Test
    fun anInterruptedCheckIsNoMiss() {
        val play = FakePlay(installed = {
            Thread.currentThread().interrupt()
            null
        })
        val d = detectors(play)
        repeat(CoverTextDetectors.CHECK_TRIES + 1) {
            assertNull(d.session().lines(image))
            assertTrue("the interrupt is kept", Thread.interrupted())
        }
        assertEquals(CoverTextDetectors.Status(Use.ML_KIT, null), d.status())
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
    fun memoryPressureAndInterruptsAreNoFailuresOfMlKit() {
        val d = detectors(FakePlay())
        val detector = d.session()
        ml.found = { throw OutOfMemoryError("decode pressure") }
        repeat(CoverTextDetectors.FAILURES) { assertThrows(OutOfMemoryError::class.java) { detector.lines(image) } }
        ml.found = { throw InterruptedException() }
        repeat(CoverTextDetectors.FAILURES) {
            assertNull(detector.lines(image))
            assertTrue("the interrupt is kept", Thread.interrupted())
        }
        assertEquals(CoverTextDetectors.Status(Use.ML_KIT, null), d.status())
    }

    @Test
    fun turningTheSettingOnAgainGivesMlKitANewTry() {
        val d = detectors(FakePlay())
        ml.found = { null }
        repeat(CoverTextDetectors.FAILURES) { d.session().lines(image) }
        assertEquals(Why.FAILED, d.status().why)
        // Off and on again, with nothing asking in between.
        on = false
        d.settingChanged()
        on = true
        d.settingChanged()
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
