package com.infinity.comicreader.net

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A share's measured speed is kept on disk, at most every half minute, and forgotten with the share. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareSpeedsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var now = 0L

    @Before
    fun setUp() {
        Remotes.serve(listOf(FakeRemoteFs.share("s1"))) { throw AssertionError("no reads") }
        ShareSpeeds.init(app)
        ShareSpeeds.clock = { now }
    }

    @After
    fun tearDown() {
        ShareSpeeds.clock = { System.nanoTime() / 1_000_000 }
        ShareSpeeds.reset()
        Remotes.serve(emptyList()) { throw AssertionError("no shares") }
    }

    /** Three reads at [bytes] a second. */
    private fun reads(id: String, bytes: Long) = repeat(SpeedMeter.MIN_SAMPLES) {
        val meter = ShareSpeeds.meter(id)
        meter.bulkStarted()
        now += 1_000
        meter.bulkEnded(bytes)
        ShareSpeeds.measured(id)
    }

    @Test
    fun aSlowShareIsKnownSlowAfterARestart() {
        reads("s1", 1_500_000)
        assertTrue(ShareSpeeds.slow("s1"))
        ShareSpeeds.init(app)
        assertEquals(1_500_000L, ShareSpeeds.estimate("s1")!!.bytesPerSecond)
        assertTrue(ShareSpeeds.slow("s1"))
        // Faster reads within half a minute of the save are not kept yet.
        reads("s1", 8L shl 20)
        assertFalse(ShareSpeeds.slow("s1"))
        ShareSpeeds.init(app)
        assertTrue(ShareSpeeds.slow("s1"))
        now += ShareSpeeds.SAVE_EVERY_MS
        reads("s1", 8L shl 20)
        ShareSpeeds.init(app)
        assertFalse(ShareSpeeds.slow("s1"))
    }

    @Test
    fun forgettingAShareDropsWhatWasKept() {
        reads("s1", 1_500_000)
        ShareSpeeds.forget("s1")
        ShareSpeeds.init(app)
        assertNull(ShareSpeeds.estimate("s1"))
    }
}
