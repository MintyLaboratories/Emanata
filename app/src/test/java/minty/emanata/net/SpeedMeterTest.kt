package minty.emanata.net

import minty.emanata.net.SpeedMeter.Estimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Share speed from real reads: medians of throughput and first-byte samples, reads in flight together counted once. */
class SpeedMeterTest {
    private var now = 0L
    private val meter = SpeedMeter({ now })

    private fun bulk(bytes: Long, ms: Long, on: SpeedMeter = meter) {
        on.bulkStarted()
        now += ms
        on.bulkEnded(bytes)
    }

    @Test
    fun nothingMeasuredIsUnknownAndNotSlow() {
        assertNull(meter.estimate())
        assertFalse(meter.slow())
    }

    @Test
    fun aFastLinkIsFastAndASlowOneSlow() {
        repeat(3) { bulk(8L shl 20, 1_000) }
        assertEquals(8L * (1 shl 20), meter.estimate()!!.bytesPerSecond)
        assertFalse(meter.slow())
        val slow = SpeedMeter({ now })
        repeat(3) { bulk(1_500_000, 1_500, slow) }
        assertEquals(1_000_000L, slow.estimate()!!.bytesPerSecond)
        assertTrue(slow.slow())
    }

    @Test
    fun aLongWaitForTheFirstByteIsSlow() {
        repeat(3) { meter.firstByte(5) }
        assertEquals(5L, meter.estimate()!!.firstByteMs)
        assertFalse(meter.slow())
        repeat(5) { meter.firstByte(1_500) }
        assertTrue(meter.slow())
    }

    @Test
    fun oneDiskSpinningUpDoesNotCount() {
        meter.firstByte(20_000)
        meter.firstByte(6)
        meter.firstByte(4)
        assertEquals(6L, meter.estimate()!!.firstByteMs)
        bulk(1L shl 20, 20_000)
        bulk(8L shl 20, 1_000)
        bulk(8L shl 20, 1_000)
        assertFalse(meter.slow())
    }

    @Test
    fun shortReadsWaitingTogetherOnOneSpinUpCountOnce() {
        // A folder's cover reads after an idle spell: all wait for the same disk to spin up.
        repeat(2) {
            repeat(6) { meter.shortStarted() }
            repeat(6) { i -> meter.shortEnded(18_000L + i) }
            meter.firstByte(6)
            meter.firstByte(5)
        }
        assertEquals(6L, meter.estimate()!!.firstByteMs)
        assertFalse(meter.slow())
    }

    @Test
    fun aRunOfShortReadsCountsItsQuickestAnswerAndNotFailures() {
        meter.shortStarted()
        meter.shortStarted()
        meter.shortStarted()
        meter.shortEnded(null)
        meter.shortEnded(40)
        meter.shortEnded(30)
        meter.firstByte(20)
        meter.firstByte(50)
        assertEquals(30L, meter.estimate()!!.firstByteMs)
        // A run every read of which failed gives no sample.
        meter.shortStarted()
        meter.shortEnded(null)
        assertEquals(30L, meter.estimate()!!.firstByteMs)
    }

    @Test
    fun aLongReadStartingDuringARunOfShortReadsSpoilsIt() {
        meter.shortStarted()
        meter.bulkStarted()
        meter.bulkEnded(0)
        meter.shortEnded(3_000)
        repeat(3) { meter.firstByte(7) }
        assertEquals(7L, meter.estimate()!!.firstByteMs)
    }

    @Test
    fun readsInFlightTogetherCountTheirUnion() {
        repeat(3) {
            // Two reads of 2 MB, both under way for the same second: 4 MB/s, not 2.
            meter.bulkStarted()
            meter.bulkStarted()
            now += 1_000
            meter.bulkEnded(2L shl 20)
            meter.bulkEnded(2L shl 20)
        }
        assertEquals(4L shl 20, meter.estimate()!!.bytesPerSecond)
    }

    @Test
    fun idleTimeBetweenReadsIsNotCounted() {
        repeat(3) {
            bulk(1L shl 19, 250)
            now += 10_000
            bulk(1L shl 19, 250)
        }
        assertEquals(2L shl 20, meter.estimate()!!.bytesPerSecond)
    }

    @Test
    fun shortReadsBesideALongOneAreNotTimed() {
        meter.bulkStarted()
        repeat(5) { meter.firstByte(3_000) }
        now += 1_000
        meter.bulkEnded(8L shl 20)
        assertNull(meter.estimate()?.firstByteMs)
    }

    @Test
    fun theKeptEstimateStandsInUntilEnoughSamples() {
        val seeded = SpeedMeter({ now }, Estimate(500_000, null))
        assertTrue(seeded.slow())
        bulk(8L shl 20, 1_000, seeded)
        bulk(8L shl 20, 1_000, seeded)
        assertTrue(seeded.slow())
        bulk(8L shl 20, 1_000, seeded)
        assertFalse(seeded.slow())
    }

    @Test
    fun onlyTheLatestSamplesCount() {
        repeat(SpeedMeter.KEPT) { bulk(1_500_000, 1_500) }
        assertTrue(meter.slow())
        repeat(SpeedMeter.KEPT / 2 + 1) { bulk(8L shl 20, 1_000) }
        assertFalse(meter.slow())
    }

    @Test
    fun theThresholds() {
        assertFalse(Estimate(SpeedMeter.SLOW_BYTES_PER_S, SpeedMeter.SLOW_FIRST_BYTE_MS).slow)
        assertTrue(Estimate(SpeedMeter.SLOW_BYTES_PER_S - 1, null).slow)
        assertTrue(Estimate(null, SpeedMeter.SLOW_FIRST_BYTE_MS + 1).slow)
    }
}
