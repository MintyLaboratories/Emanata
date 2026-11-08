package com.infinity.comicreader.net

/**
 * Estimates one share's speed from its real reads: throughput from long reads, timed while at
 * least one is under way (reads in flight together share the link, so their union counts, not
 * each one), and first-byte latency from short reads made while no long read is under way. Each
 * is the median of its last [KEPT] samples, so one read waiting for a sleeping disk to spin up
 * does not count; until [MIN_SAMPLES] are in, [seed] (the estimate kept from before) stands in.
 */
class SpeedMeter(private val clock: () -> Long, private val seed: Estimate? = null) {

    /** What was measured: bytes a second and milliseconds to a short read's answer; null where unknown. */
    data class Estimate(val bytesPerSecond: Long?, val firstByteMs: Long?) {
        /** True below [SLOW_BYTES_PER_S], or over [SLOW_FIRST_BYTE_MS] to the first byte. */
        val slow: Boolean
            get() = (bytesPerSecond != null && bytesPerSecond < SLOW_BYTES_PER_S) || (firstByteMs != null && firstByteMs > SLOW_FIRST_BYTE_MS)
    }

    private val rates = ArrayDeque<Long>()
    private val firsts = ArrayDeque<Long>()
    private var active = 0
    private var mark = 0L
    private var busyMs = 0L
    private var bytes = 0L

    /** A long read starts. */
    @Synchronized
    fun bulkStarted() {
        tick()
        active++
    }

    /** A long read ended with [got] bytes (0 when it failed). */
    @Synchronized
    fun bulkEnded(got: Long) {
        tick()
        if (active > 0) active--
        bytes += got.coerceAtLeast(0)
        if (busyMs >= MIN_SAMPLE_MS && bytes >= MIN_SAMPLE_BYTES) {
            keep(rates, bytes * 1000 / busyMs)
            bytes = 0
            busyMs = 0
        }
    }

    /** Counts the time since the last event as busy while a long read was under way. */
    private fun tick() {
        val now = clock()
        if (active > 0) busyMs += (now - mark).coerceAtLeast(0)
        mark = now
    }

    /** A short read took [ms] to answer; not counted while a long read shares the link. */
    @Synchronized
    fun firstByte(ms: Long) {
        if (active == 0 && ms >= 0) keep(firsts, ms)
    }

    private fun keep(samples: ArrayDeque<Long>, value: Long) {
        samples.addLast(value)
        while (samples.size > KEPT) samples.removeFirst()
    }

    /** The estimate now; null when nothing is known. */
    @Synchronized
    fun estimate(): Estimate? {
        val rate = median(rates) ?: seed?.bytesPerSecond
        val first = median(firsts) ?: seed?.firstByteMs
        return if (rate == null && first == null) null else Estimate(rate, first)
    }

    /** True once this meter's own samples give a median, [seed] aside. */
    @Synchronized
    fun measured(): Boolean = median(rates) != null || median(firsts) != null

    /** True when the estimate says slow; false while nothing is known. */
    fun slow(): Boolean = estimate()?.slow == true

    private fun median(samples: ArrayDeque<Long>): Long? =
        if (samples.size < MIN_SAMPLES) null else samples.sorted().let { it[it.size / 2] }

    companion object {
        /** Below this throughput a share counts as slow: about a 4K page every few seconds. */
        const val SLOW_BYTES_PER_S = 2_000_000L

        /** Over this wait for a short read's answer a share counts as slow. */
        const val SLOW_FIRST_BYTE_MS = 1_000L

        /** Reads asked for at least this many bytes count toward throughput. */
        const val BULK_BYTES = 256 * 1024

        /** Reads asked for at most this many bytes count toward first-byte latency. */
        const val SHORT_BYTES = 64 * 1024

        /** Samples kept per measure. */
        const val KEPT = 8

        /** Samples needed before a median replaces [seed]. */
        const val MIN_SAMPLES = 3

        /** A throughput sample covers at least this much busy time... */
        const val MIN_SAMPLE_MS = 200L

        /** ...and at least this many bytes. */
        const val MIN_SAMPLE_BYTES = 1L shl 20
    }
}
