package minty.emanata.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.util.Log
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Semaphore
import kotlin.math.ceil
import kotlin.math.floor

/**
 * What [AvifCodec.info] tells of a file's current image, in its order: size, bit depth,
 * YUV format and range, alpha, image count and progressive state, CICP, transforms with
 * the clean aperture's crop, ICC profile size and image index. After opening, before any
 * image is decoded, it describes the file's primary item (its full size).
 */
internal class AvifInfo(v: IntArray) {
    val width = v[0]
    val height = v[1]
    val depth = v[2]

    /** libavif's pixel format: 1 = 4:4:4, 2 = 4:2:2, 3 = 4:2:0, 4 = 4:0:0 (monochrome). */
    val format = v[3]
    val fullRange = v[4] != 0
    val alpha = v[5] != 0
    val count = v[6]
    val progressive = v[7]
    val primaries = v[8]
    val transfer = v[9]
    val matrix = v[10]
    val transforms = v[11]

    /** Quarter turns anticlockwise (`irot`). */
    val rotation = if (transforms and IROT != 0) v[12] and 3 else 0

    /** `imir`: [MIRROR_TOP_BOTTOM] or [MIRROR_LEFT_RIGHT]; -1 for none. */
    val mirror = if (transforms and IMIR != 0) v[13] else -1

    /** `[x, y, width, height]` of the clean aperture (`clap`) in this image's pixels, or null. */
    val crop: IntArray? = if (transforms and CLAP != 0 && v[16] > 0 && v[17] > 0) intArrayOf(v[14], v[15], v[16], v[17]) else null
    val iccSize = v[18]
    val index = v[19]

    /** Images to step through: the layers of a layered image opened with layers, else one. */
    val layers: Int get() = if (progressive == ACTIVE) count else 1

    /** `[width, height]` as shown: cropped, then turned. */
    val shownSize: IntArray
        get() {
            val w = crop?.get(2) ?: width
            val h = crop?.get(3) ?: height
            return if (rotation % 2 == 1) intArrayOf(h, w) else intArrayOf(w, h)
        }

    /**
     * `[left, top, right, bottom]` of the clean aperture in a [w] x [h] rendering of this
     * image (a smaller layer, or a sampled decode): the whole pixels it touches. Null
     * without one.
     */
    fun cropIn(w: Int, h: Int): IntArray? {
        val c = crop ?: return null
        val sx = w.toDouble() / width
        val sy = h.toDouble() / height
        val l = floor(c[0] * sx + EPSILON).toInt().coerceIn(0, w - 1)
        val t = floor(c[1] * sy + EPSILON).toInt().coerceIn(0, h - 1)
        val r = ceil((c[0] + c[2]) * sx - EPSILON).toInt().coerceIn(l + 1, w)
        val b = ceil((c[1] + c[3]) * sy - EPSILON).toInt().coerceIn(t + 1, h)
        return intArrayOf(l, t, r, b)
    }

    companion object {
        const val CLAP = 2
        const val IROT = 4
        const val IMIR = 8

        /** Progressive states: a layered image, stepped through layer by layer ([ACTIVE]) or not. */
        const val AVAILABLE = 1
        const val ACTIVE = 2

        const val MIRROR_TOP_BOTTOM = 0
        const val MIRROR_LEFT_RIGHT = 1

        private const val EPSILON = 1e-9
    }
}

/**
 * AVIF decoding through libavif ([LibAvif]), for every AVIF image: single images,
 * grids and layered (progressive) images, 4:4:4, 4:2:2, 4:2:0 and monochrome, 8 to 12
 * bits, with alpha. Pixels come out as 8-bit sRGB ([AvifColor]), cropped, turned and
 * mirrored as the file says (`clap`, `irot`, `imir`).
 *
 * A layered image (libavif's `--layered`: `a1lx` with no `lsel`) shows its last, full
 * layer. Its first layer, usually a small preview at the start of the file, can be had
 * on its own ([firstLayer]), or handed over on its way to the full image ([decode]).
 */
object Avif {
    private const val TAG = "Avif"

    /** Largest AVIF image decoded: libavif holds all of it in native memory, whatever the cap. */
    const val MAX_PIXELS = 40_000_000L

    /** Full decodes running at once, bounding that native memory. */
    private val gate = Semaphore(2)

    /** First layers decoded at once ([firstLayer]): small, so they never wait for full decodes. */
    private val layerGate = Semaphore(2)

    /** Threads one decode uses. */
    private val THREADS = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

    /** The decoder; JVM tests set their own. */
    @Volatile
    internal var codec: AvifCodec? = null

    private fun codec(): AvifCodec = codec ?: LibAvif.also { codec = it }

    /**
     * The image of AVIF file [bytes], reduced inside the decoder by the power-of-two
     * sample that brings it under [capPixels] ([Decoders.capSample]), point-sampled.
     * Of a layered image whose first layer is at most half its size ([isPreviewLayer]),
     * [firstLayer] gets that layer as soon as it is decoded, to keep, with the full
     * image's shown size, before the rest decodes. A first layer that fails to render
     * or to be taken is skipped; the full image still decodes.
     */
    fun decode(bytes: ByteArray, capPixels: Long, firstLayer: ((Bitmap, IntArray) -> Unit)? = null): Bitmap {
        val codec = codec()
        gate.acquireUninterruptibly()
        try {
            val handle = codec.open(bytes, bytes.size, true, THREADS, firstLayer != null)
            if (handle == 0L) throw DecodeException("Not a complete AVIF image")
            try {
                val file = AvifInfo(codec.info(handle))
                checkSize(file)
                next(codec, handle)
                if (file.layers > 1 && firstLayer != null) {
                    val layer = AvifInfo(codec.info(handle))
                    if (isPreviewLayer(layer, file)) handOver(codec, handle, file, layer, firstLayer)
                    repeat(file.layers - 1) { next(codec, handle) }
                }
                val image = AvifInfo(codec.info(handle))
                return render(codec, handle, file, image, Decoders.capSample(image.width, image.height, capPixels))
            } finally {
                codec.close(handle)
            }
        } finally {
            gate.release()
        }
    }

    /**
     * The first layer of a layered AVIF file whose first bytes are [bytes] (all of it or
     * only its start), at its own size; null when the file is not layered, [bytes] end
     * before the first layer does, or the layer is smaller than [minSize] asks of the
     * file's full size. Throws [IOException] when it does not decode.
     */
    fun firstLayer(bytes: ByteArray, minSize: (Int, Int) -> IntArray = { _, _ -> intArrayOf(0, 0) }): Bitmap? {
        val codec = codec()
        layerGate.acquireUninterruptibly()
        try {
            val handle = codec.open(bytes, bytes.size, false, THREADS, true)
            if (handle == 0L) return null
            try {
                val file = AvifInfo(codec.info(handle))
                if (file.layers < 2) return null
                checkSize(file)
                if (!codec.next(handle)) return null
                val layer = AvifInfo(codec.info(handle))
                if (layer.width > file.width || layer.height > file.height) return null
                val want = minSize(file.width, file.height)
                if (layer.width < want[0] || layer.height < want[1]) return null
                return render(codec, handle, file, layer, 1)
            } finally {
                codec.close(handle)
            }
        } finally {
            layerGate.release()
        }
    }

    /**
     * `[width, height]` of the AVIF image in the first [length] bytes of [bytes] as it is
     * shown ([AvifInfo.shownSize]), read from its metadata alone; null when they end before
     * it or it does not parse. [complete] says whether they hold the whole file.
     */
    fun size(bytes: ByteArray, length: Int = bytes.size, complete: Boolean = true): IntArray? = try {
        val codec = codec()
        val handle = codec.open(bytes, length, complete, 1, false)
        if (handle == 0L) {
            null
        } else {
            try {
                AvifInfo(codec.info(handle)).shownSize.takeIf { it[0] > 0 && it[1] > 0 }
            } finally {
                codec.close(handle)
            }
        }
    } catch (e: IOException) {
        null
    } catch (e: LinkageError) {
        null
    }

    private fun checkSize(file: AvifInfo) {
        if (file.width.toLong() * file.height > MAX_PIXELS) {
            throw DecodeException("AVIF image is too large (${file.width} x ${file.height})")
        }
    }

    /**
     * True when [layer], the first layer of [file], is at most half [file]'s size each
     * way (rounded up): a preview worth handing over. A larger one would cost about as
     * much as the full image; one larger than the full image is not a preview at all.
     */
    internal fun isPreviewLayer(layer: AvifInfo, file: AvifInfo): Boolean =
        layer.width > 0 && layer.height > 0 && layer.width <= (file.width + 1) / 2 && layer.height <= (file.height + 1) / 2

    /** [layer] rendered and given to [take]; skipped, the decode going on, when either fails. */
    private fun handOver(codec: AvifCodec, handle: Long, file: AvifInfo, layer: AvifInfo, take: (Bitmap, IntArray) -> Unit) {
        try {
            take(render(codec, handle, file, layer, 1), file.shownSize)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "First AVIF layer skipped", e)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "First AVIF layer skipped", e)
        }
    }

    private fun next(codec: AvifCodec, handle: Long) {
        if (!codec.next(handle)) throw DecodeException("Not a complete AVIF image")
    }

    /**
     * [image], the current image of [file], as an sRGB bitmap of its size divided by
     * [sample], with [file]'s crop, turn and mirror.
     */
    private fun render(codec: AvifCodec, handle: Long, file: AvifInfo, image: AvifInfo, sample: Int): Bitmap {
        val w = (image.width + sample - 1) / sample
        val h = (image.height + sample - 1) / sample
        val space = AvifColor.space(image.primaries, image.transfer)
        val bitmap = if (space == null) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, image.alpha, space)
        try {
            codec.render(handle, bitmap, sample, THREADS)
        } catch (t: Throwable) {
            bitmap.recycle()
            throw t
        }
        bitmap.setHasAlpha(image.alpha)
        return oriented(if (space == null) bitmap else srgb(bitmap), file)
    }

    /** [src] drawn as sRGB; recycles [src]. */
    private fun srgb(src: Bitmap): Bitmap {
        try {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888, src.hasAlpha(), ColorSpace.get(ColorSpace.Named.SRGB))
            Canvas(out).drawBitmap(src, 0f, 0f, null)
            return out
        } finally {
            src.recycle()
        }
    }

    /** [src], a rendering of [file]'s image, cropped, turned and mirrored as [file] says; recycles [src] when it makes another. */
    internal fun oriented(src: Bitmap, file: AvifInfo): Bitmap {
        val crop = file.cropIn(src.width, src.height)
        if (crop == null && file.rotation == 0 && file.mirror < 0) return src
        val area = crop ?: intArrayOf(0, 0, src.width, src.height)
        val matrix = Matrix()
        // irot turns anticlockwise; Android's positive angles turn clockwise.
        if (file.rotation != 0) matrix.postRotate(-90f * file.rotation)
        when (file.mirror) {
            AvifInfo.MIRROR_TOP_BOTTOM -> matrix.postScale(1f, -1f)
            AvifInfo.MIRROR_LEFT_RIGHT -> matrix.postScale(-1f, 1f)
        }
        val out = try {
            Bitmap.createBitmap(src, area[0], area[1], area[2] - area[0], area[3] - area[1], matrix, false)
        } catch (t: Throwable) {
            src.recycle()
            throw t
        }
        if (out !== src) src.recycle()
        return out
    }
}
