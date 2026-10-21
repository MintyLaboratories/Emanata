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
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
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
 * Every decode produces exactly the [Target] it is given:
 *  1. the image is decoded at full resolution, with no decoder-side scaling;
 *  2. with [Target.autoCrop], blank borders are found on that full image;
 *  3. it is resampled with Lanczos3 ([BitmapUtils.resample]) to the target size,
 *     and the full-resolution bitmap is recycled at once.
 *
 * Memory guard: a source above [Target.capPixels] (50 MP by default) is first reduced
 * by the smallest power-of-two sample size that brings it under the cap, inside the
 * decoder ([capSample]), and only then resampled. That reduction is the decoder's own:
 * libjpeg-turbo's DCT scaling for JPEG, an area average for WebP, point sampling for
 * PNG, jxl-coder's Mitchell filter for JPEG XL. Only images past the cap take it.
 *
 * JPEG XL decodes with jxl-coder on every API level, TIFF with the built-in [Tiff]
 * (its cap reduction is point sampling). Other formats use [ImageDecoder] on API 28+
 * (AVIF from API 31), with [BitmapFactory] below that.
 */
object Decoders {

    fun decode(bytes: ByteArray, target: Target): Bitmap {
        val kind = ImageKind.sniff(bytes)
        return reported(kind) {
            val full = when {
                kind == ImageKind.JXL -> decodeJxl(bytes, target.capPixels)
                kind == ImageKind.TIFF -> decodeTiff(Tiff.ByteInput(bytes), target.capPixels)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                    decodePlatform(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), target.capPixels)
                else -> decodeLegacy(bytes, target.capPixels)
            }
            fit(full, target)
        }
    }

    fun decode(input: InputStream, target: Target): Bitmap = decode(BitmapUtils.readCapped(input), target)

    /** Streams [file] to the platform decoder; JPEG XL, and any image below API 28, is read whole first. */
    fun decode(file: File, target: Target): Bitmap {
        val kind = ImageKind.sniff(readPrefix(file, ImageKind.HEADER_BYTES))
        if (kind == ImageKind.TIFF) {
            return reported(kind) { fit(RandomAccessFile(file, "r").use { decodeTiff(Tiff.FileInput(it), target.capPixels) }, target) }
        }
        if (kind == ImageKind.JXL || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return file.inputStream().use { decode(it, target) }
        }
        return reported(kind) { fit(decodePlatform(ImageDecoder.createSource(file), target.capPixels), target) }
    }

    /** [full] cropped and resampled to [target]; [full] itself when it already is the result, else recycled. */
    fun fit(full: Bitmap, target: Target): Bitmap {
        val src = if (full.config == Bitmap.Config.ARGB_8888) full else full.copy(Bitmap.Config.ARGB_8888, false).also {
            full.recycle()
        }
        val bounds = if (target.autoCrop) BitmapUtils.contentBounds(src) else null
        val plan = if (bounds != null) {
            target.plan(bounds.width().toDouble(), bounds.height().toDouble(), bounds.left.toDouble(), bounds.top.toDouble())
        } else {
            target.plan(src.width.toDouble(), src.height.toDouble())
        }
        if (plan.isIdentity(src.width, src.height)) return src
        try {
            return if (plan.isCrop()) Bitmap.createBitmap(src, plan.x.toInt(), plan.y.toInt(), plan.width, plan.height)
            else BitmapUtils.resample(src, plan, target.linear && plan.shrinks())
        } finally {
            // Frees the full-resolution pixels before the caller keeps the result.
            src.recycle()
        }
    }

    /** Intrinsic `[width, height]` as a decode would return it at full size, without decoding pixels; null if unknown. */
    fun size(bytes: ByteArray): IntArray? = when (ImageKind.sniff(bytes)) {
        ImageKind.PNG -> pngSize(bytes) { platformSize(bytes) }
        ImageKind.JXL -> jxlSize(bytes)
        ImageKind.TIFF -> Tiff.size(Tiff.ByteInput(bytes))
        else -> platformSize(bytes)
    }

    fun size(input: InputStream): IntArray? = size(BitmapUtils.readCapped(input))

    /** Like [size] on bytes, reading only the first [ImageHeaders.PREFIX_BYTES] unless a JPEG XL header lies beyond. */
    fun size(file: File): IntArray? = runCatching {
        val head = readPrefix(file, ImageHeaders.PREFIX_BYTES)
        when (ImageKind.sniff(head)) {
            ImageKind.PNG -> pngSize(head) { platformSize(file) }
            ImageKind.JXL -> ImageHeaders.jxlSize(head) ?: file.inputStream().use { jxlSize(BitmapUtils.readCapped(it)) }
            ImageKind.TIFF -> RandomAccessFile(file, "r").use { Tiff.size(Tiff.FileInput(it)) }
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
        } catch (e: CancellationException) {
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

    /** Smallest power-of-two sample size that brings a [w] x [h] image to at most [capPixels]: 1 below the cap. */
    fun capSample(w: Int, h: Int, capPixels: Long): Int {
        if (w <= 0 || h <= 0) return 1
        var sample = 1
        while (w.toLong() * h.toLong() / (sample.toLong() * sample.toLong()) > capPixels) sample *= 2
        return sample
    }

    // ------------------------------------------------------------------ JPEG XL

    /** Largest JPEG XL image decoded. jxl-coder holds the whole image in native memory, whatever the cap. */
    const val MAX_JXL_PIXELS = 40_000_000L

    /** JPEG XL decodes running at once, bounding that native memory. */
    private val jxlGate = Semaphore(2)

    /** Full decode; only past [capPixels] does jxl-coder's sampled decode do the coarse reduction. */
    private fun decodeJxl(bytes: ByteArray, capPixels: Long): Bitmap {
        val size = jxlSize(bytes) ?: throw DecodeException("Not a valid JXL image")
        if (size[0].toLong() * size[1] > MAX_JXL_PIXELS) {
            throw DecodeException("JXL image is too large (${size[0]} x ${size[1]})")
        }
        val sample = capSample(size[0], size[1], capPixels)
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

    // ------------------------------------------------------------------ TIFF

    /** The first page, point-sampled under [capPixels]; JPEG strips and tiles go to the platform decoder. */
    private fun decodeTiff(input: Tiff.Input, capPixels: Long): Bitmap {
        val page = Tiff.page(input)
        val sample = capSample(page.width, page.height, capPixels)
        val bitmap = Bitmap.createBitmap(
            (page.width + sample - 1) / sample, (page.height + sample - 1) / sample, Bitmap.Config.ARGB_8888
        )
        try {
            Tiff.decode(input, page, sample, ::tiffJpeg) { x, y, pixels, count -> bitmap.setPixels(pixels, 0, count, x, y, count, 1) }
        } catch (t: Throwable) {
            bitmap.recycle()
            throw t
        }
        bitmap.setHasAlpha(page.hasAlpha)
        return bitmap
    }

    private fun tiffJpeg(jpeg: ByteArray): Tiff.Raster {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            ?: throw DecodeException("Could not decode a JPEG strip of this TIFF image")
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return Tiff.Raster(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    // ------------------------------------------------------------------ platform

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodePlatform(source: ImageDecoder.Source, capPixels: Long): Bitmap =
        eightBit(ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Software pixels: the resampler, auto-crop and export read them back.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(capSample(info.size.width, info.size.height, capPixels))
            // A truncated or corrupt file still shows the part that decoded.
            decoder.setOnPartialImageListener {
                it.error == ImageDecoder.DecodeException.SOURCE_INCOMPLETE ||
                    it.error == ImageDecoder.DecodeException.SOURCE_MALFORMED_DATA
            }
        })

    /**
     * Redraws a half-float bitmap (16-bit PNGs, other deep images) as 8-bit sRGB, which takes half
     * the memory and is what the resampler reads.
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

    private fun decodeLegacy(bytes: ByteArray, capPixels: Long): Bitmap {
        val bounds = legacyBounds { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
            ?: throw DecodeException("Unsupported image format on this Android version")
        val opts = BitmapFactory.Options().apply {
            inSampleSize = capSample(bounds[0], bounds[1], capPixels)
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
