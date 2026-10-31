package com.infinity.comicreader.reader

import android.graphics.Bitmap
import java.util.concurrent.CancellationException
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

    /** Filters output rows [y0] until [y1] as [worker], below the job's workers; false on failure. */
    fun band(job: Long, worker: Int, y0: Int, y1: Int): Boolean

    fun end(job: Long)
}

/** The resampler built from source (`src/main/cpp/resample*.c`), through JNI. */
internal object LibPixels : PixelKernels {
    /** True once the native library is loaded; false where it cannot be, as on the JVM. */
    val loaded: Boolean = runCatching { System.loadLibrary("avifjni") }.isSuccess

    override fun begin(src: Bitmap, dst: Bitmap, linear: Boolean, workers: Int, gpu: Boolean): Long =
        nBegin(src, dst, linear, workers, gpu)

    override fun axis(job: Long, vertical: Boolean, axis: Resampler.Axis): Boolean =
        nAxis(job, vertical, axis.start, axis.count, axis.weights, axis.stride, axis.ring, axis.ringLo, axis.ringHi)

    override fun band(job: Long, worker: Int, y0: Int, y1: Int): Boolean = nBand(job, worker, y0, y1)

    override fun end(job: Long) = nEnd(job)

    @JvmStatic
    private external fun nBegin(src: Bitmap, dst: Bitmap, linear: Boolean, workers: Int, gpu: Boolean): Long

    @JvmStatic
    private external fun nAxis(
        job: Long, vertical: Boolean, start: IntArray, count: IntArray, weights: FloatArray, stride: Int, ring: Float,
        ringLo: IntArray, ringHi: IntArray
    ): Boolean

    @JvmStatic
    private external fun nBand(job: Long, worker: Int, y0: Int, y1: Int): Boolean

    @JvmStatic
    private external fun nEnd(job: Long)
}

/**
 * Resamples a bitmap with native code, reading and writing pixels where the bitmaps hold
 * them: no row copies through Java arrays, no lock on the output. The taps are
 * [Resampler.axis]'s, the bands [Resampler.inBands]'s; the GPU takes the bands one at a
 * time on the calling thread.
 */
object NativeResampler {
    /**
     * The engine bitmaps are resampled with; [PixelEngine.GPU] falls back to the native CPU
     * code and that, where it cannot load, to [Resampler].
     */
    @Volatile
    var engine: PixelEngine = PixelEngine.NATIVE

    /** CPU workers at most: the large cores' count on the reference tablet. */
    @Volatile
    var threads: Int = minOf(4, Runtime.getRuntime().availableProcessors())

    internal var kernels: PixelKernels? = null
        get() = field ?: LibPixels.takeIf { it.loaded }

    /**
     * Resamples [window] of [src] into all of [dst] as [scaling] says, with [engine]; false
     * when it is [PixelEngine.KOTLIN] or native code cannot do it, so the caller uses
     * [Resampler]; with [fallback] false, also when the GPU was asked for and could not do it.
     * Throws [CancellationException] once [canceled] returns true.
     */
    fun resample(
        src: Bitmap,
        dst: Bitmap,
        window: Resampler.Window,
        linear: Boolean,
        scaling: Scaling,
        canceled: () -> Boolean = { false },
        engine: PixelEngine = this.engine,
        fallback: Boolean = true
    ): Boolean {
        if (engine == PixelEngine.KOTLIN || !fits(src) || !fits(dst)) return false
        val k = kernels ?: return false
        val ax = Resampler.axis(src.width, window.x, window.width, dst.width, scaling)
        val ay = Resampler.axis(src.height, window.y, window.height, dst.height, scaling)
        if (engine == PixelEngine.GPU) {
            if (run(k, src, dst, ax, ay, linear, 1, gpu = true, canceled)) return true
            if (!fallback) return false
        }
        return run(k, src, dst, ax, ay, linear, threads, gpu = false, canceled)
    }

    /** True for ARGB_8888 pixels held premultiplied, as an opaque bitmap's are too (it reports them as not). */
    private fun fits(b: Bitmap) = b.config == Bitmap.Config.ARGB_8888 && (b.isPremultiplied || !b.hasAlpha())

    /** One job on [k]; false when it could not start or a band failed. */
    private fun run(
        k: PixelKernels, src: Bitmap, dst: Bitmap, ax: Resampler.Axis, ay: Resampler.Axis, linear: Boolean, threads: Int,
        gpu: Boolean, canceled: () -> Boolean
    ): Boolean {
        val job = k.begin(src, dst, linear, threads, gpu)
        if (job == 0L) return false
        try {
            if (!k.axis(job, false, ax) || !k.axis(job, true, ay)) return false
            val failed = AtomicBoolean()
            try {
                Resampler.inBands(dst.height, threads, canceled) { worker, y0, y1 ->
                    k.band(job, worker, y0, y1).also { if (!it) failed.set(true) }
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
