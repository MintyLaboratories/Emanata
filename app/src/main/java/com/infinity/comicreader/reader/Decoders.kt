package com.infinity.comicreader.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Build
import androidx.annotation.RequiresApi
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.JxlResizeFilter
import com.awxkee.jxlcoder.PreferredColorConfig
import com.awxkee.jxlcoder.ScaleMode
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore

/**
 * An image that could not be decoded; [message] says why, for the page placeholder.
 * A [transient] failure, such as running out of memory, may pass on a later try.
 */
class DecodeException(
    message: String,
    cause: Throwable? = null,
    val transient: Boolean = false
) : IOException(message, cause)

/**
 * The one place encoded images become bitmaps: page sources, cover thumbnails, the
 * crop screen and page export all decode through here. The format comes from the
 * leading bytes ([ImageKind.sniff]), never from the file name. Unknown bytes still
 * go to the platform decoder, which reads a few rarer formats too.
 *
 * Every decode takes the size it will be shown at, [reqW] x [reqH]; a dimension of
 * 1 or less leaves that axis free. The result is never smaller than the target on a
 * constrained axis unless [capPixels] forces it, and may be up to twice as large:
 * downscaling is still a power-of-two sample size ([sampleSize]) applied inside the
 * decoder. Exact-size resampling replaces that in one place, [decodeJxl],
 * [decodePlatform] and [decodeLegacy]: decode at sample size 1, then resample to
 * the target.
 *
 * JPEG XL decodes with jxl-coder on every API level. Other formats use
 * [ImageDecoder] on API 28+ (AVIF from API 31), with [BitmapFactory] below that.
 */
object Decoders {
    const val DEFAULT_CAP_PIXELS = 12_000_000L

    /** Longest side assumed for an unconstrained axis when picking a sample size. */
    private const val MAX_DIM = 4096

    fun decode(bytes: ByteArray, reqW: Int, reqH: Int, capPixels: Long = DEFAULT_CAP_PIXELS): Bitmap {
        val kind = ImageKind.sniff(bytes)
        return reported(kind) {
            when {
                kind == ImageKind.JXL -> decodeJxl(bytes, reqW, reqH, capPixels)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                    decodePlatform(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), reqW, reqH, capPixels)
                else -> decodeLegacy(bytes, reqW, reqH, capPixels)
            }
        }
    }

    fun decode(input: InputStream, reqW: Int, reqH: Int, capPixels: Long = DEFAULT_CAP_PIXELS): Bitmap =
        decode(BitmapUtils.readCapped(input), reqW, reqH, capPixels)

    /** Streams [file] to the platform decoder; JPEG XL, and any image below API 28, is read whole first. */
    fun decode(file: File, reqW: Int, reqH: Int, capPixels: Long = DEFAULT_CAP_PIXELS): Bitmap {
        val kind = ImageKind.sniff(readPrefix(file, ImageKind.HEADER_BYTES))
        if (kind == ImageKind.JXL || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return file.inputStream().use { decode(it, reqW, reqH, capPixels) }
        }
        return reported(kind) { decodePlatform(ImageDecoder.createSource(file), reqW, reqH, capPixels) }
    }

    /** Intrinsic `[width, height]` as a decode would return it at full size, without decoding pixels; null if unknown. */
    fun size(bytes: ByteArray): IntArray? = when (ImageKind.sniff(bytes)) {
        ImageKind.PNG -> pngSize(bytes) { platformSize(bytes) }
        ImageKind.JXL -> jxlSize(bytes)
        else -> platformSize(bytes)
    }

    fun size(input: InputStream): IntArray? = size(BitmapUtils.readCapped(input))

    /** Like [size] on bytes, reading only the first [ImageHeaders.PREFIX_BYTES] unless a JPEG XL header lies beyond. */
    fun size(file: File): IntArray? = runCatching {
        val head = readPrefix(file, ImageHeaders.PREFIX_BYTES)
        when (ImageKind.sniff(head)) {
            ImageKind.PNG -> pngSize(head) { platformSize(file) }
            ImageKind.JXL -> ImageHeaders.jxlSize(head) ?: file.inputStream().use { jxlSize(BitmapUtils.readCapped(it)) }
            else -> platformSize(file)
        }
    }.getOrNull()

    /** An eXIf chunk may rotate the decode, so only the [platform] reading matches it then. */
    private inline fun pngSize(head: ByteArray, platform: () -> IntArray?): IntArray? =
        if (ImageHeaders.pngMayHaveExif(head)) platform() ?: ImageHeaders.pngSize(head)
        else ImageHeaders.pngSize(head) ?: platform()

    /** Up to the first [max] bytes of [file]. */
    private fun readPrefix(file: File, max: Int): ByteArray = file.inputStream().use { input ->
        val buf = ByteArray(max)
        var n = 0
        while (n < max) {
            val read = input.read(buf, n, max - n)
            if (read < 0) break
            n += read
        }
        if (n == max) buf else buf.copyOf(n)
    }

    /** Runs a decode of a [kind] image, turning every failure into a [DecodeException] that says why. */
    private inline fun reported(kind: ImageKind?, decode: () -> Bitmap): Bitmap {
        val label = kind?.name ?: "unrecognized"
        return try {
            decode()
        } catch (e: DecodeException) {
            throw e
        } catch (e: LinkageError) {
            throw DecodeException("No $label decoder on this device", e)
        } catch (e: OutOfMemoryError) {
            throw DecodeException("Not enough memory for this $label image", e, transient = true)
        } catch (e: IOException) {
            if (isReadError(e)) throw DecodeException("Could not read this $label image", e, transient = true)
            throw DecodeException("Could not decode this $label image", e)
        } catch (e: Exception) {
            throw DecodeException("Could not decode this $label image", e)
        }
    }

    /** A failure to read the source, which may pass, rather than bytes that do not decode. */
    private fun isReadError(e: IOException): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P || e !is ImageDecoder.DecodeException ||
            e.error == ImageDecoder.DecodeException.SOURCE_EXCEPTION

    /**
     * Power-of-two sample size that keeps both dimensions at or above the target,
     * then halves further until the image fits [capPixels].
     */
    fun sampleSize(w: Int, h: Int, reqW: Int, reqH: Int, capPixels: Long): Int {
        if (w <= 0 || h <= 0) return 1
        val tw = if (reqW > 0) reqW else MAX_DIM
        val th = if (reqH > 0) reqH else MAX_DIM
        var sample = 1
        while (w / (sample * 2) >= tw && h / (sample * 2) >= th) sample *= 2
        while (w.toLong() * h.toLong() / (sample.toLong() * sample.toLong()) > capPixels) sample *= 2
        return sample
    }

    // ------------------------------------------------------------------ JPEG XL

    /**
     * Largest JPEG XL image decoded. jxl-coder holds the whole image in native memory
     * before scaling it, whatever the target size, so [capPixels] does not bound it.
     */
    const val MAX_JXL_PIXELS = 40_000_000L

    /** JPEG XL decodes running at once, bounding that native memory. */
    private val jxlGate = Semaphore(2)

    /** Downscales inside jxl-coder (Mitchell) to the power-of-two size the other formats get. */
    private fun decodeJxl(bytes: ByteArray, reqW: Int, reqH: Int, capPixels: Long): Bitmap {
        val size = jxlSize(bytes) ?: throw DecodeException("Not a valid JXL image")
        if (size[0].toLong() * size[1] > MAX_JXL_PIXELS) {
            throw DecodeException("JXL image is too large (${size[0]} x ${size[1]})")
        }
        val sample = sampleSize(size[0], size[1], reqW, reqH, capPixels)
        jxlGate.acquireUninterruptibly()
        try {
            if (sample == 1) return JxlCoder.decode(bytes, PreferredColorConfig.RGBA_8888)
            return JxlCoder.decodeSampled(
                bytes,
                (size[0] / sample).coerceAtLeast(1),
                (size[1] / sample).coerceAtLeast(1),
                PreferredColorConfig.RGBA_8888,
                ScaleMode.FIT,
                JxlResizeFilter.MITCHELL_NETRAVALI
            )
        } finally {
            jxlGate.release()
        }
    }

    /** Header size, or the library's own reading for layouts [ImageHeaders.jxlSize] does not follow. */
    private fun jxlSize(bytes: ByteArray): IntArray? =
        ImageHeaders.jxlSize(bytes) ?: try {
            JxlCoder.getSize(bytes)?.let { intArrayOf(it.width, it.height) }?.takeIf { it[0] > 0 && it[1] > 0 }
        } catch (e: Exception) {
            null
        } catch (e: LinkageError) {
            null
        }

    // ------------------------------------------------------------------ platform

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodePlatform(source: ImageDecoder.Source, reqW: Int, reqH: Int, capPixels: Long): Bitmap =
        eightBit(ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Software pixels: auto-crop and export read them back.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height, reqW, reqH, capPixels))
            // A truncated or corrupt file still shows the part that decoded.
            decoder.setOnPartialImageListener {
                it.error == ImageDecoder.DecodeException.SOURCE_INCOMPLETE ||
                    it.error == ImageDecoder.DecodeException.SOURCE_MALFORMED_DATA
            }
        })

    /**
     * Redraws a half-float bitmap (16-bit PNGs, other deep images) as 8-bit sRGB, which takes half
     * the memory in the page cache and in exported PNGs.
     */
    private fun eightBit(bitmap: Bitmap): Bitmap {
        if (bitmap.config != Bitmap.Config.RGBA_F16) return bitmap
        val out = Bitmap.createBitmap(
            bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888, bitmap.hasAlpha(), ColorSpace.get(ColorSpace.Named.SRGB)
        )
        Canvas(out).drawBitmap(bitmap, 0f, 0f, null)
        bitmap.recycle()
        return out
    }

    private fun decodeLegacy(bytes: ByteArray, reqW: Int, reqH: Int, capPixels: Long): Bitmap {
        val bounds = legacyBounds { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
            ?: throw DecodeException("Unsupported image format on this Android version")
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds[0], bounds[1], reqW, reqH, capPixels)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw DecodeException("Could not decode image")
    }

    private fun platformSize(bytes: ByteArray): IntArray? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) headerSize(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
        else legacyBounds { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }

    private fun platformSize(file: File): IntArray? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) headerSize(ImageDecoder.createSource(file))
        else legacyBounds { BitmapFactory.decodeFile(file.path, it) }

    /** Stops [ImageDecoder] once the header is read; shared, so it keeps no stack trace or suppressed exceptions. */
    private object HeaderRead : RuntimeException(null, null, false, false)

    /** Size from [ImageDecoder]'s header pass, which already applies EXIF orientation like the decode. */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun headerSize(source: ImageDecoder.Source): IntArray? {
        var size: IntArray? = null
        try {
            ImageDecoder.decodeBitmap(source) { _, info, _ ->
                size = intArrayOf(info.size.width, info.size.height)
                throw HeaderRead
            }
        } catch (e: HeaderRead) {
            // Expected: the pixels were never wanted.
        } catch (e: Exception) {
            return null
        }
        return size?.takeIf { it[0] > 0 && it[1] > 0 }
    }

    /** Size from a [BitmapFactory] bounds-only pass, which [decode] runs with the options it is given. */
    private inline fun legacyBounds(decode: (BitmapFactory.Options) -> Unit): IntArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) intArrayOf(bounds.outWidth, bounds.outHeight) else null
    }
}
