package com.infinity.comicreader.reader

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import java.util.concurrent.CancellationException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Where bitmaps are resampled: [Resampler] in Kotlin, or native code on the CPU or the GPU ([NativeResampler]). */
enum class PixelEngine(val key: String) {
    KOTLIN("kotlin"),
    NATIVE("native"),
    GPU("gpu");

    companion object {
        fun of(key: String?): PixelEngine? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What [NativeResampler] needs of native code: a job resampling one ARGB_8888 bitmap into
 * another, both premultiplied, as [Resampler] does with premultiplied rows. A job holds
 * both bitmaps' pixels until [end]. On the device this is [LibPixels]; JVM tests stand in
 * their own, since they cannot load native code.
 */
internal interface PixelKernels {
    /** A job resampling [src] into [dst] on [workers] CPU workers, or on the GPU; 0 when not possible. */
    fun begin(src: Bitmap, dst: Bitmap, linear: Boolean, workers: Int, gpu: Boolean): Long

    /** Hands [job] the taps of its [vertical] axis or its horizontal one; false when they do not fit or the GPU is unusable. */
    fun axis(job: Long, vertical: Boolean, axis: Resampler.Axis): Boolean

    /**
     * Filters output rows [y0] until [y1] as [worker], below the job's workers, on the
     * cores in [cores] (bit n = core n; 0 = any) for its length; false on failure.
     */
    fun band(job: Long, worker: Int, y0: Int, y1: Int, cores: Long): Boolean

    /**
     * Filters output rows [y0] until [y1] of a GPU job on the calling thread, on [cores] as
     * [band] says, asking [stop] (null: never) before each chunk: [BAND_DONE],
     * [BAND_CANCELED] once it said so (rethrowing what it threw), else [BAND_FAILED]. By
     * default, one [band] after asking once.
     */
    fun gpuBand(job: Long, y0: Int, y1: Int, cores: Long, stop: Stop?): Int = when {
        stop?.stop() == true -> BAND_CANCELED
        band(job, 0, y0, y1, cores) -> BAND_DONE
        else -> BAND_FAILED
    }

    fun end(job: Long)

    /**
     * Frees what the GPU path keeps between jobs, for when memory runs low; by default
     * nothing. Never blocks: while a job runs, it frees once that job ends.
     */
    fun trim() {}

    /**
     * Asked before each chunk of a GPU band, on its thread; true stops the band. Runs while
     * the band holds the GPU: it must only read a flag (an AtomicBoolean, a job's isActive),
     * never block, take locks, do I/O or call [PixelKernels].
     */
    fun interface Stop {
        fun stop(): Boolean
    }

    companion object {
        const val BAND_FAILED = 0
        const val BAND_DONE = 1
        const val BAND_CANCELED = -2
    }
}

/** The resampler built from source (`src/main/cpp/resample*.c`), through JNI. */
internal object LibPixels : PixelKernels {
    /** True once the native library is loaded; false where it cannot be, as on the JVM. */
    val loaded: Boolean = runCatching { System.loadLibrary("avifjni") }.isSuccess

    override fun begin(src: Bitmap, dst: Bitmap, linear: Boolean, workers: Int, gpu: Boolean): Long =
        nBegin(src, dst, linear, workers, gpu)

    override fun axis(job: Long, vertical: Boolean, axis: Resampler.Axis): Boolean =
        nAxis(job, vertical, axis.start, axis.count, axis.weights, axis.stride, axis.ring, axis.ringLo, axis.ringHi)

    override fun band(job: Long, worker: Int, y0: Int, y1: Int, cores: Long): Boolean = nBand(job, worker, y0, y1, cores)

    override fun gpuBand(job: Long, y0: Int, y1: Int, cores: Long, stop: PixelKernels.Stop?): Int =
        nGpuBand(job, y0, y1, cores, stop)

    override fun end(job: Long) = nEnd(job)

    /** Never blocks; does nothing where the native library is not loaded. */
    override fun trim() {
        if (loaded) nTrim()
    }

    /**
     * Pins the calling thread to [cores] (bit n = core n), or ends its pin for every core's bit
     * (-1); the cores it could use before, 0 when refused or a pin is in effect (pins never nest).
     */
    fun pin(cores: Long): Long = nPin(cores)

    /**
     * Opens the core ledger (resetting it) or closes it: while open, native resampling bands
     * (a GPU band on its calling thread, not the driver's) and JPEG XL decodes count their
     * threads' CPU time core by core ([ledgerNs]) and thread by thread ([ledgerThreads]).
     * Calibration only.
     */
    fun ledger(open: Boolean) = nLedger(open)

    /** Nanoseconds of CPU time per core counted since the last call, zeroed. */
    fun ledgerNs(cores: Int): LongArray {
        val out = LongArray(cores.coerceIn(0, 64))
        nLedgerNs(out)
        return out
    }

    /** The nanoseconds of CPU time each thread counted since the last call, by thread id; forgotten. */
    fun ledgerThreads(): Map<Int, Long> {
        val tids = IntArray(128)
        val ns = LongArray(128)
        val n = nLedgerThreads(tids, ns)
        return HashMap<Int, Long>(n * 2).apply { for (i in 0 until n) put(tids[i], ns[i]) }
    }

    /**
     * Turns on or off a log line per GPU job under "ResampleGpu": milliseconds per step (lock,
     * context, shader compile, allocation, upload, submit, wait, copy, free), each pass's GPU
     * time where the driver has timer queries, the across pass' tap count, the cores the
     * copies ran on, and the buffers made and bytes kept. A trim of the kept buffers logs a
     * line whether tracing or not. Off by default; debug checks only. Does nothing where the
     * native library is not loaded.
     */
    fun trace(on: Boolean) {
        if (loaded) nTrace(on)
    }

    @JvmStatic
    private external fun nTrace(on: Boolean)

    @JvmStatic
    private external fun nPin(cores: Long): Long

    @JvmStatic
    private external fun nLedger(open: Boolean)

    @JvmStatic
    private external fun nLedgerNs(out: LongArray): Int

    @JvmStatic
    private external fun nLedgerThreads(tids: IntArray, ns: LongArray): Int

    @JvmStatic
    private external fun nBegin(src: Bitmap, dst: Bitmap, linear: Boolean, workers: Int, gpu: Boolean): Long

    @JvmStatic
    private external fun nAxis(
        job: Long, vertical: Boolean, start: IntArray, count: IntArray, weights: FloatArray, stride: Int, ring: Float,
        ringLo: IntArray, ringHi: IntArray
    ): Boolean

    @JvmStatic
    private external fun nBand(job: Long, worker: Int, y0: Int, y1: Int, cores: Long): Boolean

    @JvmStatic
    private external fun nGpuBand(job: Long, y0: Int, y1: Int, cores: Long, stop: PixelKernels.Stop?): Int

    @JvmStatic
    private external fun nEnd(job: Long)

    @JvmStatic
    private external fun nTrim()
}

/**
 * Resamples a bitmap with native code, reading and writing pixels where the bitmaps hold
 * them: no row copies through Java arrays, no lock on the output. The taps are
 * [Resampler.axis]'s, the bands [Resampler.inBands]'s; the GPU takes the bands one at a
 * time on the calling thread.
 *
 * CPU workers are pinned to the large cores ([CpuCores]): work someone waits on
 * ([Urgency]) runs on [threads] of them, other work (pages read ahead, previews) on
 * [laterThreads], growing to [threads] once it is waited on. A GPU band's calling thread,
 * which copies its rows to and from the GPU, is placed as a CPU worker would be.
 */
object NativeResampler {
    /**
     * The engine bitmaps are resampled with; [PixelEngine.GPU] falls back to the native CPU
     * code and that, where it cannot load, to [Resampler]. Moving off the GPU frees the
     * buffers its path keeps.
     */
    @Volatile
    var engine: PixelEngine = PixelEngine.NATIVE
        set(value) {
            val was = field
            field = value
            if (was == PixelEngine.GPU && value != PixelEngine.GPU) trim()
        }

    /** CPU workers for work someone waits on: one per large core. */
    @Volatile
    var threads: Int = defaultThreads(CpuCores.device, Runtime.getRuntime().availableProcessors())

    /** CPU workers for work nobody waits on yet. */
    @Volatile
    var laterThreads: Int = threads

    /** Whether workers are pinned to the large cores ([CpuCores]); off leaves placement to the system. */
    @Volatile
    var pinned: Boolean = true

    /** Pins work nobody waits on to the little cores instead, for A/B runs (slower and, by the power model, costlier). */
    @Volatile
    internal var laterOnLittle: Boolean = false

    /** Pins every worker to the fastest cores alone ([CpuCores.prime]) instead, for A/B runs. */
    @Volatile
    internal var onPrime: Boolean = false

    /** The cores the workers are pinned to. */
    internal var cores: CpuCores? = null
        get() = field ?: CpuCores.device

    internal var kernels: PixelKernels? = null
        get() = field ?: LibPixels.takeIf { it.loaded }

    /**
     * Frees the buffers the GPU path keeps between jobs once memory runs low or the UI
     * hides, on the calling thread; true when [level] asks for it. From API 34 on, apps get
     * only [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN] and
     * [ComponentCallbacks2.TRIM_MEMORY_BACKGROUND]; in front, [idleTrimMs] frees them.
     */
    fun onTrimMemory(level: Int): Boolean {
        @Suppress("DEPRECATION")
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return false
        trim()
        return true
    }

    /** Frees the buffers the GPU path keeps between jobs; never blocks ([PixelKernels.trim]). */
    fun trim() {
        kernels?.trim()
    }

    /** Milliseconds after the last GPU job at which the GPU path's buffers are freed. */
    @Volatile
    internal var idleTrimMs: Long = IDLE_TRIM_MS

    private val idleTimer by lazy {
        ScheduledThreadPoolExecutor(1) { r -> Thread(r, "resample-idle").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
    }
    private val idleLock = Any()
    private var idleTrim: ScheduledFuture<*>? = null

    /** Has [k] free the GPU path's buffers [idleTrimMs] from now, unless called again before. */
    private fun trimWhenIdle(k: PixelKernels) {
        synchronized(idleLock) {
            idleTrim?.cancel(false)
            idleTrim = idleTimer.schedule({ k.trim() }, idleTrimMs, TimeUnit.MILLISECONDS)
        }
    }

    /** [trim]s unless pages are resampled on the GPU, whose buffers serve them alone. */
    fun trimUnlessGpu() {
        if (engine != PixelEngine.GPU) trim()
    }

    /** A minute: page turns at reading pace keep the buffers, a pause frees them. */
    private const val IDLE_TRIM_MS = 60_000L

    /** One worker per large core; where speeds are unknown, at most 4. */
    internal fun defaultThreads(cores: CpuCores, processors: Int): Int =
        if (cores.known) cores.bigCount.coerceAtLeast(1) else minOf(4, processors).coerceAtLeast(1)

    /**
     * Resamples [window] of [src] into all of [dst] as [scaling] says, with [engine]; false
     * when it is [PixelEngine.KOTLIN] or native code cannot do it, so the caller uses
     * [Resampler]; with [fallback] false, also when the GPU was asked for and could not do it.
     * Throws [CancellationException] once [canceled] returns true. [urgent] says, before
     * each band, whether someone waits on the result. [workers] above 0 sets the CPU
     * workers whatever the urgency, [pin] whether they (or the GPU's calling thread) are
     * pinned, for timing a setup.
     */
    fun resample(
        src: Bitmap,
        dst: Bitmap,
        window: Resampler.Window,
        linear: Boolean,
        scaling: Scaling,
        canceled: () -> Boolean = { false },
        engine: PixelEngine = this.engine,
        fallback: Boolean = true,
        urgent: () -> Boolean = Urgency.current(),
        workers: Int = 0,
        pin: Boolean = pinned
    ): Boolean {
        if (engine == PixelEngine.KOTLIN || !fits(src) || !fits(dst)) return false
        val k = kernels ?: return false
        val ax = Resampler.axis(src.width, window.x, window.width, dst.width, scaling)
        val ay = Resampler.axis(src.height, window.y, window.height, dst.height, scaling)
        if (engine == PixelEngine.GPU) {
            val done = try {
                run(k, src, dst, ax, ay, linear, gpu = true, canceled, urgent, workers, pin)
            } finally {
                trimWhenIdle(k)
            }
            if (done) return true
            if (!fallback) return false
        }
        return run(k, src, dst, ax, ay, linear, gpu = false, canceled, urgent, workers, pin)
    }

    /** True for ARGB_8888 pixels held premultiplied, as an opaque bitmap's are too (it reports them as not). */
    private fun fits(b: Bitmap) = b.config == Bitmap.Config.ARGB_8888 && (b.isPremultiplied || !b.hasAlpha())

    /** One job on [k]; false when it could not start or a band failed. */
    private fun run(
        k: PixelKernels, src: Bitmap, dst: Bitmap, ax: Resampler.Axis, ay: Resampler.Axis, linear: Boolean, gpu: Boolean,
        canceled: () -> Boolean, urgent: () -> Boolean, workers: Int, pinning: Boolean
    ): Boolean {
        val now = (if (workers > 0) workers else threads).coerceAtLeast(1)
        val later = (if (workers > 0) workers else laterThreads).coerceAtLeast(1)
        val most = if (gpu) 1 else maxOf(now, later)
        val c = cores!!
        val job = k.begin(src, dst, linear, most, gpu)
        if (job == 0L) return false
        try {
            if (!k.axis(job, false, ax) || !k.axis(job, true, ay)) return false
            val failed = AtomicBoolean()
            try {
                Resampler.inBands(dst.height, most, canceled, { if (urgent()) now else later }) { worker, y0, y1 ->
                    val on = if (!pinning) 0L else if (onPrime) c.prime else if (laterOnLittle && !urgent()) c.little else c.big
                    k.band(job, worker, y0, y1, on).also { if (!it) failed.set(true) }
                }
            } catch (e: CancellationException) {
                if (!failed.get()) throw e
            }
            return !failed.get()
        } finally {
            k.end(job)
        }
    }
}
