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
    private class FakePlay(
        var there: Boolean = true,
        val installed: () -> Boolean? = { true },
        val request: () -> Unit = {}
    ) : PlayServices {
        @Volatile
        var asked = 0
        val requests = mutableListOf<(Boolean) -> Unit>()
        var detached = 0
        var released = 0

        override fun available() = there

        override fun moduleInstalled(): Boolean? {
            asked++
            return installed()
        }

        override fun requestModule(done: (Boolean) -> Unit): () -> Unit {
            request()
            requests += done
            return { detached++ }
        }

        override fun releaseModule() {
            released++
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
    private var policyAsked = false

    /** Runs once, at the first read of the setting after the network policy was asked. */
    private var afterPolicy: (() -> Unit)? = null

    private fun detectors(play: PlayServices, mlKit: (() -> CoverTextDetector)? = { ml }) = CoverTextDetectors(
        {
            if (policyAsked) afterPolicy?.let {
                afterPolicy = null
                it()
            }
            on
        },
        play,
        mlKit?.let { make ->
            CoverTextDetectors.MlKit("mlkit") {
                made++
                make()
            }
        },
        { pp },
        {
            policyAsked = true
            mayDownload
        },
        { clock }
    )

    @Test
    fun theAppsDetectorsOfferMlKitOnceTurnedOn() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        val d = CoverTextDetectors.forApp(context, FakePlay())
        assertTrue("the setting is shown", d.offered())
        assertEquals(CoverTextDetectors.Status(Use.PP_OCR, Why.OFF), d.status())
        Prefs(context).coverMlKit = true
        assertEquals(CoverTextDetectors.Status(Use.ML_KIT, null), d.status())
        assertEquals(MlKitTextDetector.ID, d.session().id)
        assertTrue(MlKitTextDetector.ID in CoverTextDetector.IDS)
    }

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
    fun mlKitMissingItsClassesLeavesPpOcrForTheSession() {
        val d = detectors(FakePlay(), mlKit = { throw NoClassDefFoundError("text recognizer") })
        assertNull(d.session().lines(image))
        assertEquals(CoverTextDetectors.Status(Use.PP_OCR, Why.FAILED), d.status())
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
        assertFalse("no setting", d.offered())
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
        assertEquals("mlkit", d.session().id)
        assertEquals(TextDetector.ID, d.rightToLeft().id)
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
        assertEquals("the request is dropped", 1, play.detached)
        assertEquals("the module stays wanted", 0, play.released)
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
        assertEquals(1, play.detached)
        assertEquals("still turned on: the module stays wanted", 0, play.released)
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
    fun aDownloadTheNetworkPolicyHoldsBackLeavesPpOcrForAWhile() {
        mayDownload = false
        val play = play(installed = false)
        val d = detectors(play)
        assertNull(d.session().lines(image))
        assertTrue(play.requests.isEmpty())
        assertEquals(CoverTextDetectors.Status(Use.PP_OCR, Why.HELD_BACK), d.status())
        mayDownload = true
        clock += DetectorModel.HELD_MS - 1
        assertEquals(TextDetector.ID, d.session().id)
    }

    @Test
    fun aHeldBackDownloadIsRequestedOnceThePolicyAllowsIt() {
        mayDownload = false
        val play = play(installed = false)
        val d = detectors(play)
        d.session().lines(image)
        val changes = d.changes.value
        clock += DetectorModel.HELD_MS
        assertEquals("still held back", CoverTextDetectors.Status(Use.PP_OCR, Why.HELD_BACK), d.status())
        assertEquals("no change while it stays held back", changes, d.changes.value)
        mayDownload = true
        clock += DetectorModel.HELD_MS - 1
        assertEquals("asked again a while after the last hold", TextDetector.ID, d.session().id)
        assertEquals("no change before the hold is lifted", changes, d.changes.value)
        clock += 1
        assertEquals(CoverTextDetectors.Status(Use.ML_KIT, null), d.status())
        assertEquals("covers on screen are made again under ML Kit", changes + 1, d.changes.value)
        assertNull(d.session().lines(image))
        assertEquals(1, play.requests.size)
        play.requests.single()(true)
        assertEquals(line, d.session().lines(image))
        assertEquals(1, d.arrivals)
    }

    @Test
    fun aFailedDownloadLeavesPpOcrForTheSession() {
        val play = play(installed = false)
        val d = detectors(play)
        d.session().lines(image)
        play.requests.single()(false)
        assertEquals(CoverTextDetectors.Status(Use.PP_OCR, Why.MODULE_FAILED), d.status())
        assertEquals(TextDetector.ID, d.session().id)
        // Unlike a hold, no time lifts it.
        clock += DetectorModel.HELD_MS
        assertEquals(CoverTextDetectors.Status(Use.PP_OCR, Why.MODULE_FAILED), d.status())
        assertEquals(TextDetector.ID, d.session().id)
        assertEquals(1, play.requests.size)
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
    fun aModuleCheckMissingMlKitsClassesLeavesPpOcrForTheSession() {
        val play = FakePlay(installed = { throw NoClassDefFoundError("module install client") })
        val d = detectors(play)
        assertNull(d.session().lines(image))
        assertEquals(CoverTextDetectors.Status(Use.PP_OCR, Why.FAILED), d.status())
        assertEquals(TextDetector.ID, d.session().id)
    }

    @Test
    fun aCheckEndingInAnErrorIsAskedAgain() {
        var fail = true
        val play = FakePlay(installed = { if (fail) throw StackOverflowError("check") else true })
        val d = detectors(play)
        assertNull(d.session().lines(image))
        fail = false
        assertEquals(line, d.session().lines(image))
        assertEquals(2, play.asked)
    }

    @Test
    fun aRequestPlayServicesThrowsAtLeavesPpOcrForTheSession() {
        val play = FakePlay(installed = { false }, request = { throw IllegalStateException("no client") })
        val d = detectors(play)
        assertNull(d.session().lines(image))
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
        // A detector handed out before does not run ML Kit again, however much later.
        clock += DetectorModel.HELD_MS
        val runs = ml.runs
        assertNull(detector.lines(image))
        assertEquals(runs, ml.runs)
        assertEquals(CoverTextDetectors.Status(Use.PP_OCR, Why.FAILED), d.status())
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
    fun turningTheSettingOffDropsTheDownloadRequest() {
        val play = play(installed = false)
        val d = detectors(play)
        d.session().lines(image)
        on = false
        d.settingChanged()
        assertEquals(1, play.detached)
        assertEquals("the module is released", 1, play.released)
        // Its late answer changes nothing.
        play.requests.single()(true)
        assertEquals(0, d.arrivals)
        on = true
        d.settingChanged()
        assertEquals(CoverTextDetectors.Status(Use.ML_KIT, null), d.status())
        assertNull("asked about again", d.session().lines(image))
        assertEquals(2, play.asked)
        assertEquals(2, play.requests.size)
        assertEquals(1, play.released)
    }

    @Test
    fun turningTheSettingOffDuringTheCheckRequestsNothing() {
        val asking = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val play = FakePlay(installed = {
            asking.countDown()
            answer.await(5, TimeUnit.SECONDS)
            false
        })
        val d = detectors(play)
        val detector = d.session()
        val cover = thread { detector.lines(image) }
        assertTrue(asking.await(5, TimeUnit.SECONDS))
        on = false
        d.settingChanged()
        answer.countDown()
        cover.join(5_000)
        assertTrue("no download requested", play.requests.isEmpty())
        on = true
        d.settingChanged()
        assertEquals(CoverTextDetectors.Status(Use.ML_KIT, null), d.status())
    }

    @Test
    fun turningTheSettingOffAsTheModuleIsAboutToBeRequestedRequestsNothing() {
        val play = play(installed = false)
        lateinit var d: CoverTextDetectors
        // The listener runs once the request is decided on, before it is sent.
        afterPolicy = {
            on = false
            d.settingChanged()
        }
        d = detectors(play)
        assertNull(d.session().lines(image))
        assertNull("the hook ran", afterPolicy)
        assertTrue("no download requested", play.requests.isEmpty())
        assertEquals(0, play.released)
        on = true
        d.settingChanged()
        assertEquals(CoverTextDetectors.Status(Use.ML_KIT, null), d.status())
        assertNull("asked about again", d.session().lines(image))
        assertEquals(1, play.requests.size)
    }

    @Test
    fun theListenerRunningWhileTheModuleIsRequestedDetachesAndReleasesIt() {
        lateinit var d: CoverTextDetectors
        val play = FakePlay(installed = { false }, request = {
            on = false
            d.settingChanged()
        })
        d = detectors(play)
        assertNull(d.session().lines(image))
        // Play services may still finish the download: the app stops listening and releases the module.
        assertEquals(1, play.detached)
        assertEquals(1, play.released)
        play.requests.single()(true)
        assertEquals(0, d.arrivals)
    }

    @Test
    fun turningTheSettingOffWhileTheModuleIsRequestedDetachesAndReleasesIt() {
        // The setting reads off before its listener runs.
        val play = FakePlay(installed = { false }, request = { on = false })
        val d = detectors(play)
        assertNull(d.session().lines(image))
        assertEquals(1, play.detached)
        assertEquals(1, play.released)
        // Its late answer changes nothing.
        play.requests.single()(true)
        assertEquals(0, d.arrivals)
        on = true
        d.settingChanged()
        assertEquals(CoverTextDetectors.Status(Use.ML_KIT, null), d.status())
    }

    @Test
    fun aModuleFoundAfterACheckMissedCountsAsArrived() {
        var installed: Boolean? = null
        val d = detectors(FakePlay(installed = { installed }))
        assertNull("went without text", d.session().lines(image))
        installed = true
        assertEquals(line, d.session().lines(image))
        assertEquals(1, d.arrivals)
        assertEquals(1, d.changes.value)
    }

    @Test
    fun ppOcrsModelArrivingIsAnArrival() {
        val d = detectors(FakePlay())
        d.modelArrived()
        assertEquals(1, d.arrivals)
        assertEquals(1, d.changes.value)
    }

    @Test
    fun aModuleThereAtOnceIsNoArrival() {
        val d = detectors(FakePlay())
        assertEquals(line, d.session().lines(image))
        assertEquals(0, d.arrivals)
        assertEquals(0, d.changes.value)
    }

    @Test
    fun givingMlKitUpIsAChangeAndANewTryAnArrival() {
        val d = detectors(FakePlay())
        ml.found = { null }
        repeat(CoverTextDetectors.FAILURES) { d.session().lines(image) }
        assertEquals(1, d.changes.value)
        assertEquals(0, d.arrivals)
        d.settingChanged()
        assertEquals("covers made before are made again", 1, d.arrivals)
        assertEquals(2, d.changes.value)
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
