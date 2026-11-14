package com.infinity.comicreader.ai

import android.graphics.Bitmap
import android.util.Log
import com.infinity.comicreader.reader.LibPixels
import java.io.File
import java.security.MessageDigest

/** What runs shader chains: [GlesShaders] on the device; JVM tests stand in their own. */
internal interface ShaderRuntime {
    /** False where chains cannot run: no OpenGL ES 3, no float textures to draw into, or turned off after failures. */
    val available: Boolean

    /** Bytes per pixel of the float textures between passes: 8 for RGBA16F, 16 for RGBA32F. */
    val floatBytes: Int

    /** The longest texture side the GPU allows. */
    val maxSide: Int

    /**
     * [chain]'s passes compiled, as a model running tiles ([TileModel]); null when one does not
     * compile. Given a [cache] folder, compiled programs are kept there for the next open.
     */
    fun open(chain: ShaderChain, cache: File? = null): TileModel?
}

/**
 * Shader chains on OpenGL ES 3 (`src/main/cpp/shader_gles.c`), in the app's native library, on
 * a context of the runner's own: each pass draws into a float texture, the chain's [ShaderPlan]
 * for the tile's size comes with every run, the result is read back as 8-bit RGB. Calls on a
 * chain take turns; the native lock keeps chains apart.
 */
internal object GlesShaders : ShaderRuntime {
    private const val TAG = "GlesShaders"

    /** "renderer, float bytes, longest side, texture units, GL version", found once: finding it starts the context. */
    private val info: List<String>? by lazy { if (LibPixels.loaded) runCatching { nInit() }.getOrNull()?.split('\t') else null }

    override val available: Boolean get() = info != null && nUsable()

    override val floatBytes: Int get() = info?.getOrNull(1)?.toIntOrNull() ?: 8

    override val maxSide: Int get() = info?.getOrNull(2)?.toIntOrNull() ?: 0

    /** The GPU's name, once [available] was asked; null where unusable. */
    val renderer: String? get() = info?.getOrNull(0)

    /** Turns the runner off for the process, as repeated failures do (debug checks of the fallback). */
    fun turnOff() {
        if (LibPixels.loaded) nTurnOff()
    }

    /** Program caches kept in a cache folder, the most recently used first: about 1 to 4 MB each. */
    const val CACHED = 8

    override fun open(chain: ShaderChain, cache: File?): TileModel? {
        if (!available) return null
        val sources = chain.sources
        val file = cache?.let { dir -> runCatching { dir.mkdirs(); File(dir, cacheName(info.orEmpty(), sources)) }.getOrNull() }
        val handle = nOpen(sources.toTypedArray(), chain.programs.map { it.bindings.size }.toIntArray(), file?.path)
        if (file != null) prune(file)
        return if (handle == 0L) null else Opened(handle, chain)
    }

    /** The cache file name of [sources] compiled by the GPU [info] describes: a hash of both and the runner's version ([ShaderSource.RUNNER]). */
    internal fun cacheName(info: List<String>, sources: List<String>, runner: Int = ShaderSource.RUNNER): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (s in listOf("runner $runner") + info + sources) {
            digest.update(s.toByteArray())
            digest.update(0)
        }
        return digest.digest().take(16).joinToString("") { "%02x".format(it) } + CACHE_SUFFIX
    }

    /**
     * Marks [used] as just used and deletes the caches beside it past the [CACHED] most recent,
     * and the partial ones an end mid-write left ([PART_AGE_MS] old: none is being written).
     */
    internal fun prune(used: File, now: Long = System.currentTimeMillis()) {
        if (used.isFile) used.setLastModified(now)
        val files = used.parentFile?.listFiles().orEmpty()
        val all = files.filter { it.name.endsWith(CACHE_SUFFIX) }
        for (f in all.sortedByDescending { it.lastModified() }.drop(CACHED)) f.delete()
        for (f in files) if (f.name.endsWith(CACHE_SUFFIX + PART_SUFFIX) && now - f.lastModified() > PART_AGE_MS) f.delete()
    }

    private const val CACHE_SUFFIX = ".programs"

    /** What `shader_gles.c` writes a cache to before renaming it into place. */
    private const val PART_SUFFIX = ".part"

    /** Age past which a partial cache is left over: writing one takes well under a second. */
    const val PART_AGE_MS = 60_000L

    private class Opened(private var handle: Long, private val chain: ShaderChain) : TileModel {
        override val onGpu: Boolean = true

        @Synchronized
        override fun run(src: Bitmap, input: Box, dst: Bitmap?, crop: Box?, dx: Int, dy: Int): Dims? {
            if (handle == 0L) return null
            val plan = try {
                chain.plan(input.width, input.height)
            } catch (e: ShaderException) {
                Log.w(TAG, "tile $input: ${e.message}")
                return null
            }
            val c = crop ?: Box(0, 0, 0, 0)
            val code = nRun(handle, plan.encoded, src, input.x, input.y, input.width, input.height, if (crop == null) null else dst, c.x, c.y, c.width, c.height, dx, dy)
            return if (code == 0) plan.output else null
        }

        @Synchronized
        override fun trim() {
            if (handle != 0L) nTrim(handle)
        }

        @Synchronized
        override fun takePeak(): Long = if (handle != 0L) nPeak(handle) else 0L

        @Synchronized
        override fun close() {
            if (handle != 0L) nClose(handle)
            handle = 0L
        }
    }

    @JvmStatic
    private external fun nInit(): String?

    @JvmStatic
    private external fun nUsable(): Boolean

    @JvmStatic
    private external fun nTurnOff()

    @JvmStatic
    private external fun nOpen(sources: Array<String>, binds: IntArray, cache: String?): Long

    @JvmStatic
    private external fun nRun(
        handle: Long, plan: IntArray, src: Bitmap, ix: Int, iy: Int, iw: Int, ih: Int,
        dst: Bitmap?, cx: Int, cy: Int, cw: Int, ch: Int, dx: Int, dy: Int
    ): Int

    @JvmStatic
    private external fun nTrim(handle: Long)

    @JvmStatic
    private external fun nPeak(handle: Long): Long

    @JvmStatic
    private external fun nClose(handle: Long)
}
