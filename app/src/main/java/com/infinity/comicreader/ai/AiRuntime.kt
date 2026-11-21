package com.infinity.comicreader.ai

import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** A pixel rectangle at ([x], [y]), [width] x [height]. */
data class Box(val x: Int, val y: Int, val width: Int, val height: Int)

/** A [width] x [height] size. */
data class Dims(val width: Int, val height: Int)

/** A one-channel model output: [width] x [height] [values], row by row. */
class ScoreMap(val width: Int, val height: Int, val values: FloatArray) {
    init {
        require(values.size == width * height) { "${values.size} values for $width x $height" }
    }

    operator fun get(x: Int, y: Int): Float = values[y * width + x]
}

/**
 * The fp16 modes a GPU supports, as Vulkan reports them: [storage] (fp16 buffers), [packed]
 * (fp16 pairs in 32-bit words, computed in fp32) and [arithmetic] (computing in fp16).
 */
data class Fp16Modes(val storage: Boolean, val packed: Boolean, val arithmetic: Boolean) {
    /** Models may run in fp16: storage or packed halves the bytes moved; arithmetic needs one of them. */
    val usable: Boolean get() = storage || packed

    /** The modes a model in fp16 turns on, as [STORAGE], [PACKED] and [ARITHMETIC] bits: each one supported; none unless [usable]. */
    val bits: Int
        get() = if (!usable) 0 else (if (storage) STORAGE else 0) or (if (packed) PACKED else 0) or (if (arithmetic) ARITHMETIC else 0)

    companion object {
        const val STORAGE = 1
        const val PACKED = 2
        const val ARITHMETIC = 4

        val NONE = Fp16Modes(storage = false, packed = false, arithmetic = false)
        val ALL = Fp16Modes(storage = true, packed = true, arithmetic = true)

        /** From native code's bits ([STORAGE], [PACKED], [ARITHMETIC]); null for anything else. */
        fun of(bits: Int): Fp16Modes? =
            if (bits !in 0..7) null else Fp16Modes(bits and STORAGE != 0, bits and PACKED != 0, bits and ARITHMETIC != 0)
    }
}

/**
 * The Vulkan device models run on: its [name], the memory it lets us use in MB, the fp16 modes
 * it supports ([fp16Modes]), and the largest buffer one shader may address ([maxBufferBytes]):
 * a feature map past it comes out wrong without an error.
 */
data class GpuInfo(val name: String, val budgetMb: Int, val fp16Modes: Fp16Modes, val maxBufferBytes: Long) {
    /** Models may run in fp16 on it ([Fp16Modes.usable]). */
    val fp16: Boolean get() = fp16Modes.usable

    /** The fp16 bits a model opened on it turns on: [Fp16Modes.bits] when [fp16] is asked for, else none (fp32). */
    fun fp16Bits(fp16: Boolean): Int = if (fp16) fp16Modes.bits else 0

    /**
     * Bytes per value of a feature map [channels] wide in fp16 ([half]) or fp32: 2 with fp16
     * storage; with packed fp16 alone, 2 only where ncnn packs channels by 4 (a width that is
     * not a multiple of 4 is kept one value per 32-bit word); else 4.
     */
    fun mapElementBytes(half: Boolean, channels: Int): Int = if (half && (fp16Modes.storage || channels % 4 == 0)) 2 else 4

    companion object {
        /** From native code's "name\tbudget\tfp16 bits\tbuffer" ([Fp16Modes.of]); null for null or anything else. */
        fun parse(s: String?): GpuInfo? {
            val parts = s?.split('\t')?.takeIf { it.size == 4 } ?: return null
            val budget = parts[1].toIntOrNull() ?: return null
            val modes = parts[2].toIntOrNull()?.let { Fp16Modes.of(it) } ?: return null
            val buffer = parts[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return GpuInfo(parts[0], budget, modes, buffer)
        }
    }
}

/**
 * A model's ncnn files, and how it reads and writes pixels: input channels are 8-bit values
 * times [inScale], output channels times [outScale] are 8-bit values (0..1 in and out by default).
 */
data class ModelFiles(val param: File, val bin: File, val inScale: Float = 1f / 255, val outScale: Float = 255f)

/**
 * A model opened on the GPU or the CPU. Runs one tile at a time: [TileEngine] holds its lock
 * around every use.
 */
internal interface TileModel : AutoCloseable {
    val onGpu: Boolean

    /**
     * Runs the model on [input] of [src] (an RGB tile; pixels outside [src] repeat its nearest
     * edge); with [dst], writes the output's [crop] to [dst] at ([dx], [dy]), opaque. The
     * output's size, or null on failure or once closed.
     */
    fun run(src: Bitmap, input: Box, dst: Bitmap? = null, crop: Box? = null, dx: Int = 0, dy: Int = 0): Dims?

    /** Frees the buffers kept between runs, on the GPU and the CPU; the weights stay. */
    fun trim()

    /**
     * The most bytes of working memory (feature maps and scratch, on the GPU and the CPU; not
     * the weights) held at once since the last call, which starts counting again; 0 where not
     * counted.
     */
    fun takePeak(): Long = 0

    /**
     * Bytes its weights took as it opened: the native heap's growth (weights as transformed
     * for the kernels, often larger than the file) and, on the GPU, its memory's growth; other
     * threads' allocations meanwhile included. 0 where not counted.
     */
    fun weightBytes(): Long = 0

    /**
     * Runs the model on all of [src], each channel as (8-bit value - [mean]) * [norm], fed as
     * B, G, R when [bgr], else R, G, B: the output's first channel; null on failure, once
     * closed, or where not supported.
     */
    fun map(src: Bitmap, mean: FloatArray, norm: FloatArray, bgr: Boolean): ScoreMap? = null
}

/** What runs models: [LibNcnn] on the device; JVM tests stand in their own. */
internal interface InferenceRuntime {
    /** False where the runtime cannot load. */
    val available: Boolean

    /** The GPU models would run on, or null when none is usable (CPU only). */
    fun gpu(): GpuInfo?

    /**
     * [files] opened on the GPU when [gpu] and one is usable, else on [threads] CPU threads; on
     * the GPU, the fp16 modes it supports only with [fp16] (the CPU always computes in fp32).
     * Null when the files do not load.
     */
    fun open(files: ModelFiles, gpu: Boolean, threads: Int, fp16: Boolean = true): TileModel?
}

/**
 * Runs blocks on one thread of its own, which lives as long as the process; a block run from
 * that thread runs at once. Exceptions reach the caller. A caller interrupted while its block
 * runs still waits for the block to end (it may be using the caller's bitmaps), then keeps
 * its interrupt.
 */
internal class OneThread(name: String) {
    @Volatile
    private var thread: Thread? = null

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true; thread = this } }

    fun <T> run(block: () -> T): T {
        if (Thread.currentThread() === thread) return block()
        val future = executor.submit(Callable(block))
        var interrupted = false
        try {
            while (true) {
                try {
                    return future.get()
                } catch (e: InterruptedException) {
                    interrupted = true
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

/**
 * ncnn with Vulkan, built from source (`src/main/cpp/ai_ncnn.cpp`), in a library loaded on
 * first use. Every call on a model runs on one thread ([OneThread]), the one it opened on: the
 * OpenMP runtime inside fails to start again once the thread that started it ends, and aborts
 * the process.
 */
internal object LibNcnn : InferenceRuntime {
    private const val TAG = "LibNcnn"

    private val worker = OneThread("ai-ncnn")

    override val available: Boolean by lazy { worker.run { runCatching { System.loadLibrary("aincnn") }.isSuccess } }

    /** The GPU, found once: finding it starts and ends a Vulkan instance. */
    private val gpuInfo: GpuInfo? by lazy { if (available) GpuInfo.parse(worker.run { nGpu() }) else null }

    override fun gpu(): GpuInfo? = gpuInfo

    override fun open(files: ModelFiles, gpu: Boolean, threads: Int, fp16: Boolean): TileModel? = openOn(worker, files, gpu, threads, fp16)

    /**
     * The same runtime, CPU only, on a thread of its own: a small model there (the text
     * detector of covers) neither waits behind AI page tiles nor holds them up.
     */
    val cpuLane: InferenceRuntime = object : InferenceRuntime {
        private val lane = OneThread("ai-ncnn-cpu")

        override val available: Boolean get() = LibNcnn.available

        override fun gpu(): GpuInfo? = null

        override fun open(files: ModelFiles, gpu: Boolean, threads: Int, fp16: Boolean): TileModel? = openOn(lane, files, false, threads, fp16)
    }

    private fun openOn(on: OneThread, files: ModelFiles, gpu: Boolean, threads: Int, fp16: Boolean): TileModel? {
        if (!available) return null
        val info = gpuInfo?.takeIf { gpu }
        val handle = on.run { nOpen(files.param.path, files.bin.path, info != null, threads, info?.fp16Bits(fp16) ?: 0) }
        return if (handle == 0L) null else Opened(handle, files, on)
    }

    /** A model open on thread [worker]. */
    private class Opened(private var handle: Long, private val files: ModelFiles, private val worker: OneThread) : TileModel {
        override val onGpu: Boolean = worker.run { nOnGpu(handle) }
        private val size = IntArray(2)

        override fun run(src: Bitmap, input: Box, dst: Bitmap?, crop: Box?, dx: Int, dy: Int): Dims? = worker.run {
            if (handle == 0L) {
                Log.w(TAG, "tile $input: model closed")
                return@run null
            }
            val c = crop ?: Box(0, 0, 0, 0)
            val code = nRun(
                handle, src, input.x, input.y, input.width, input.height, files.inScale,
                dst, c.x, c.y, c.width, c.height, dx, dy, files.outScale, size
            )
            if (code != 0) {
                Log.w(TAG, "tile $input failed: $code")
                null
            } else {
                Dims(size[0], size[1])
            }
        }

        override fun map(src: Bitmap, mean: FloatArray, norm: FloatArray, bgr: Boolean): ScoreMap? = worker.run {
            if (handle == 0L) return@run null
            val values = nMap(handle, src, mean, norm, bgr, size)
            if (values == null) {
                Log.w(TAG, "map ${src.width}x${src.height} failed")
                null
            } else {
                ScoreMap(size[0], size[1], values)
            }
        }

        override fun trim() = worker.run {
            if (handle != 0L) nTrim(handle)
        }

        override fun takePeak(): Long = worker.run { if (handle != 0L) nPeak(handle) else 0L }

        override fun weightBytes(): Long = worker.run { if (handle != 0L) nWeights(handle) else 0L }

        override fun close() = worker.run {
            if (handle != 0L) nClose(handle)
            handle = 0L
        }
    }

    @JvmStatic
    private external fun nGpu(): String?

    @JvmStatic
    private external fun nOpen(param: String, bin: String, gpu: Boolean, threads: Int, fp16: Int): Long

    @JvmStatic
    private external fun nOnGpu(handle: Long): Boolean

    @JvmStatic
    private external fun nRun(
        handle: Long, src: Bitmap, ix: Int, iy: Int, iw: Int, ih: Int, inScale: Float,
        dst: Bitmap?, cx: Int, cy: Int, cw: Int, ch: Int, dx: Int, dy: Int, outScale: Float, size: IntArray
    ): Int

    @JvmStatic
    private external fun nMap(handle: Long, src: Bitmap, mean: FloatArray, norm: FloatArray, bgr: Boolean, size: IntArray): FloatArray?

    @JvmStatic
    private external fun nTrim(handle: Long)

    @JvmStatic
    private external fun nPeak(handle: Long): Long

    @JvmStatic
    private external fun nWeights(handle: Long): Long

    @JvmStatic
    private external fun nClose(handle: Long)
}
