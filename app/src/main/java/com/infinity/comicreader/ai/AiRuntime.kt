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

/** The Vulkan device models run on: its [name], the memory it lets us use in MB, and whether it computes in fp16. */
data class GpuInfo(val name: String, val budgetMb: Int, val fp16: Boolean) {
    companion object {
        /** From native code's "name\tbudget\tfp16"; null for null or anything else. */
        fun parse(s: String?): GpuInfo? {
            val parts = s?.split('\t')?.takeIf { it.size == 3 } ?: return null
            val budget = parts[1].toIntOrNull() ?: return null
            return GpuInfo(parts[0], budget, parts[2] == "1")
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
     * output's size, or null on failure.
     */
    fun run(src: Bitmap, input: Box, dst: Bitmap? = null, crop: Box? = null, dx: Int = 0, dy: Int = 0): Dims?

    /** Frees the buffers kept between runs, on the GPU and the CPU; the weights stay. */
    fun trim()
}

/** What runs models: [LibNcnn] on the device; JVM tests stand in their own. */
internal interface InferenceRuntime {
    /** False where the runtime cannot load. */
    val available: Boolean

    /** The GPU models would run on, or null when none is usable (CPU only). */
    fun gpu(): GpuInfo?

    /**
     * [files] opened on the GPU when [gpu] and one is usable, else on [threads] CPU threads; on
     * the GPU, fp16 storage and arithmetic only with [fp16] (the CPU always computes in fp32).
     * Null when the files do not load.
     */
    fun open(files: ModelFiles, gpu: Boolean, threads: Int, fp16: Boolean = true): TileModel?
}

/**
 * Runs blocks on one thread of its own, which lives as long as the process; a block run from
 * that thread runs at once. Exceptions reach the caller.
 */
internal class OneThread(name: String) {
    @Volatile
    private var thread: Thread? = null

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true; thread = this } }

    fun <T> run(block: () -> T): T {
        if (Thread.currentThread() === thread) return block()
        try {
            return executor.submit(Callable(block)).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}

/**
 * ncnn with Vulkan, built from source (`src/main/cpp/ai_ncnn.cpp`), in a library loaded on
 * first use. Every call runs on one thread ([OneThread]): the OpenMP runtime inside fails to
 * start again once the thread that started it ends, and aborts the process.
 */
internal object LibNcnn : InferenceRuntime {
    private const val TAG = "LibNcnn"

    private val worker = OneThread("ai-ncnn")

    override val available: Boolean by lazy { worker.run { runCatching { System.loadLibrary("aincnn") }.isSuccess } }

    /** The GPU, found once: finding it starts and ends a Vulkan instance. */
    private val gpuInfo: GpuInfo? by lazy { if (available) GpuInfo.parse(worker.run { nGpu() }) else null }

    override fun gpu(): GpuInfo? = gpuInfo

    override fun open(files: ModelFiles, gpu: Boolean, threads: Int, fp16: Boolean): TileModel? {
        if (!available) return null
        val handle = worker.run { nOpen(files.param.path, files.bin.path, gpu && gpuInfo != null, threads, fp16) }
        return if (handle == 0L) null else Opened(handle, files)
    }

    private class Opened(private var handle: Long, private val files: ModelFiles) : TileModel {
        override val onGpu: Boolean = worker.run { nOnGpu(handle) }
        private val size = IntArray(2)

        override fun run(src: Bitmap, input: Box, dst: Bitmap?, crop: Box?, dx: Int, dy: Int): Dims? = worker.run {
            check(handle != 0L) { "Model closed" }
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

        override fun trim() = worker.run {
            if (handle != 0L) nTrim(handle)
        }

        override fun close() = worker.run {
            if (handle != 0L) nClose(handle)
            handle = 0L
        }
    }

    @JvmStatic
    private external fun nGpu(): String?

    @JvmStatic
    private external fun nOpen(param: String, bin: String, gpu: Boolean, threads: Int, fp16: Boolean): Long

    @JvmStatic
    private external fun nOnGpu(handle: Long): Boolean

    @JvmStatic
    private external fun nRun(
        handle: Long, src: Bitmap, ix: Int, iy: Int, iw: Int, ih: Int, inScale: Float,
        dst: Bitmap?, cx: Int, cy: Int, cw: Int, ch: Int, dx: Int, dy: Int, outScale: Float, size: IntArray
    ): Int

    @JvmStatic
    private external fun nTrim(handle: Long)

    @JvmStatic
    private external fun nClose(handle: Long)
}
