package com.infinity.comicreader.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Rect
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
 *  2. with [Target.autoCrop], blank borders are found on that full image, and
 *     [Target.half] takes one half of what is left;
 *  3. it is resampled with Lanczos3 ([BitmapUtils.resample]) to the target size,
 *     and the full-resolution bitmap is recycled at once.
 *
 * Memory guard: a source above [Target.capPixels] (50 MP by default) is first reduced
 * by the smallest power-of-two sample size that brings it under the cap, inside the
 * decoder ([capSample]), and only then resampled. That reduction is the decoder's own:
 * libjpeg-turbo's DCT scaling for JPEG, an area average for WebP, point sampling for
 * PNG, jxl-coder's Mitchell filter for JPEG XL. Only images past the cap take it.
 *
 * Quick previews ([preview]) are the one exception: JPEG and WebP decode already
 * shrunk toward [Previews.TARGET], AVIF from a thumbnail or first layer inside the
 * file, to stand in until the exact page is ready.
 *
 * JPEG XL decodes with jxl-coder on every API level, TIFF with the built-in [Tiff]
 * (its cap reduction is point sampling, DCT scaling for JPEG strips and tiles). Other formats use [ImageDecoder] on API 28+
 * (AVIF from API 31), with [BitmapFactory] below that.
 */
object Decoders {

    fun decode(bytes: ByteArray, target: Target): Bitmap {
        val kind = ImageKind.sniff(bytes)
        if (kind in QUICK_ENCODED) sinks.get()?.let { sink ->
            sinks.set(null)
            sink.encoded(bytes)
        }
        val source = if (measuresCrop(target)) runCatching { headerSize(bytes.inputStream()) ?: size(bytes) }.getOrNull() else null
        return reported(kind) {
            val full = when {
                kind == ImageKind.JXL -> decodeJxl(bytes, target.capPixels)
                kind == ImageKind.TIFF -> decodeTiff(Tiff.ByteInput(bytes), target.capPixels)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                    decodePlatform(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), target.capPixels)
                else -> decodeLegacy(bytes, target.capPixels)
            }
            fit(full, target, source)
        }
    }

    fun decode(input: InputStream, target: Target): Bitmap = decode(BitmapUtils.readCapped(input), target)

    /** Streams [file] to the platform decoder; JPEG XL, and any image below API 28, is read whole first. */
    fun decode(file: File, target: Target): Bitmap {
        val kind = ImageKind.sniff(readPrefix(file, ImageKind.HEADER_BYTES))
        if (kind == ImageKind.TIFF) {
            val source = if (measuresCrop(target)) size(file) else null
            return reported(kind) { fit(RandomAccessFile(file, "r").use { decodeTiff(Tiff.FileInput(it), target.capPixels) }, target, source) }
        }
        if (kind == ImageKind.JXL || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return file.inputStream().use { decode(it, target) }
        }
        val source = if (measuresCrop(target)) size(file) else null
        return reported(kind) { fit(decodePlatform(ImageDecoder.createSource(file), target.capPixels), target, source) }
    }

    /** What a decode leaves its page preview with ([capturing]). */
    interface PreviewSink {
        /** The encoded image, for a format whose quick preview ([preview]) is a proper one; [decoded] is not called then. */
        fun encoded(bytes: ByteArray)

        /** The image at its decoded size, before any crop or resampling; must leave it as it is. */
        fun decoded(full: Bitmap)
    }

    /** Where this thread's next decode leaves its preview ([capturing]). */
    private val sinks = ThreadLocal<PreviewSink?>()

    /**
     * Runs [block], handing the first image decoded in it on this thread to [sink]:
     * JPEG and WebP bytes as they are, other formats at their decoded size.
     */
    fun <T> capturing(sink: PreviewSink, block: () -> T): T {
        val outer = sinks.get()
        sinks.set(sink)
        try {
            return block()
        } finally {
            if (outer == null) sinks.remove() else sinks.set(outer)
        }
    }

    /** Where this thread's next auto-crop leaves what it found ([measuring]). */
    private val measures = ThreadLocal<((Box, Int, Int) -> Unit)?>()

    /**
     * Runs [block], handing what the first auto-crop in it on this thread finds to
     * [measure]: the content as a [Box] (all of the image when it has no blank border),
     * and the size of the image it was found on.
     */
    fun <T> measuring(measure: (Box, Int, Int) -> Unit, block: () -> T): T {
        val outer = measures.get()
        measures.set(measure)
        try {
            return block()
        } finally {
            if (outer == null) measures.remove() else measures.set(outer)
        }
    }

    /** True when a decode to [target] hands what its auto-crop finds to this thread's [measuring]. */
    private fun measuresCrop(target: Target) = target.autoCrop && target.box == null && measures.get() != null

    /** Hands [box], found on a [w] x [h] image, to this thread's [measuring], if any. */
    internal fun measured(box: Box, w: Int, h: Int) {
        measures.get()?.let { measure ->
            measures.set(null)
            measure(box, w, h)
        }
    }

    /**
     * [full] cropped and resampled to [target]; [full] itself when it already is the result,
     * else recycled. [source] is the image's own size, when [full] may be a sampled decode
     * of it: what auto-crop finds is then told in its terms ([SampledCrop]).
     */
    fun fit(full: Bitmap, target: Target, source: IntArray? = null): Bitmap {
        val src = if (full.config == Bitmap.Config.ARGB_8888) full else full.copy(Bitmap.Config.ARGB_8888, false).also {
            full.recycle()
        }
        sinks.get()?.let { sink ->
            sinks.set(null)
            sink.decoded(src)
        }
        val bounds = if (target.autoCrop && target.box == null) {
            BitmapUtils.contentBounds(src)?.let { intArrayOf(it.left, it.top, it.right, it.bottom) }.also { found ->
                val (box, size) = SampledCrop.of(found, src.width, src.height, source)
                measured(box, size[0], size[1])
            }
        } else {
            null
        }
        val area = target.area(src.width, src.height, bounds)
        val plan = target.plan((area[2] - area[0]).toDouble(), (area[3] - area[1]).toDouble(), area[0].toDouble(), area[1].toDouble())
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

    /**
     * Like [size] on an image's first [ImageHeaders.PREFIX_BYTES] alone, without reading
     * the rest of [input]: null when they do not tell, so the caller reads it whole.
     * JPEG, PNG and JPEG XL headers are parsed here; other formats go to the platform's
     * header pass, which needs only their start too. TIFF is left to the whole read. A
     * JPEG's frame header usually lies in its first [JPEG_HEADER_BYTES], read first.
     */
    fun headerSize(input: InputStream): IntArray? {
        val head = ByteArray(ImageHeaders.PREFIX_BYTES)
        var n = readFully(input, head, 0, JPEG_HEADER_BYTES)
        if (ImageKind.sniff(head, n) == ImageKind.JPEG) {
            runCatching { ImageHeaders.jpegSize(head.copyOf(n)) }.getOrNull()?.let { return it }
        }
        if (n == JPEG_HEADER_BYTES) n = readFully(input, head, n)
        val bytes = if (n == head.size) head else head.copyOf(n)
        return runCatching {
            when (ImageKind.sniff(bytes)) {
                ImageKind.JPEG -> ImageHeaders.jpegSize(bytes) ?: platformSize(bytes)
                ImageKind.PNG -> pngSize(bytes) { platformSize(bytes) }
                ImageKind.JXL -> ImageHeaders.jxlSize(bytes)
                ImageKind.TIFF -> null
                else -> platformSize(bytes)
            }
        }.getOrNull()
    }

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

    // ------------------------------------------------------------------ edges

    /** Kinds [edge] decodes only a strip of. */
    private val REGION_KINDS = setOf(ImageKind.JPEG, ImageKind.PNG, ImageKind.WEBP)

    /** Pixels a whole decode for [edge] works on at most, sampled down by a power of two. */
    private const val EDGE_CAP_PIXELS = 4_000_000L

    /** Share of the height a whole decode for [edge] keeps, at least [Seams.ROWS] rows. */
    private const val EDGE_BAND = 0.002

    /**
     * The [Seams.Profile] of an encoded image's [bottom] or top edge, or null when it does
     * not decode. JPEG, PNG and WebP decode only a strip of its outermost rows, sampled down
     * toward [Seams.LENGTH] wide ([BitmapRegionDecoder]); other formats decode whole, capped
     * at [EDGE_CAP_PIXELS], keeping only a thin band. For analysis only, never shown.
     * Blocking.
     */
    fun edge(bytes: ByteArray, bottom: Boolean): Seams.Profile? {
        val size = runCatching { headerSize(bytes.inputStream()) ?: size(bytes) }.getOrNull() ?: return null
        val w = size[0]
        val h = size[1]
        if (w <= 0 || h <= 0) return null
        val strip = (if (ImageKind.sniff(bytes) in REGION_KINDS) runCatching { regionStrip(bytes, w, h, bottom) }.getOrNull() else null)
            ?: runCatching { bandStrip(bytes, h, bottom) }.getOrNull()
            ?: return null
        return try {
            val pixels = IntArray(strip.width * strip.height)
            strip.getPixels(pixels, 0, strip.width, 0, 0, strip.width, strip.height)
            Seams.profile(pixels, strip.width, strip.height, bottom)
        } finally {
            strip.recycle()
        }
    }

    /** The power-of-two sample that leaves a [w]-wide image at least [Seams.LENGTH] wide. */
    internal fun edgeSample(w: Int): Int {
        var sample = 1
        while (w / (sample * 2) >= Seams.LENGTH) sample *= 2
        return sample
    }

    /** The [Seams.ROWS] outermost rows of a [w] x [h] image, sampled down ([edgeSample]), decoded alone. */
    private fun regionStrip(bytes: ByteArray, w: Int, h: Int, bottom: Boolean): Bitmap? {
        val sample = edgeSample(w)
        val rows = (Seams.ROWS * sample).coerceAtMost(h)
        val rect = if (bottom) Rect(0, h - rows, w, h) else Rect(0, 0, w, rows)
        val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        } ?: return null
        return try {
            decoder.decodeRegion(rect, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } finally {
            decoder.recycle()
        }
    }

    /** A thin band at the [bottom] or top of an [h]-tall image, from a whole decode [Seams.LENGTH] wide. */
    private fun bandStrip(bytes: ByteArray, h: Int, bottom: Boolean): Bitmap {
        val band = maxOf(Seams.ROWS.toDouble() / h, EDGE_BAND).coerceAtMost(1.0)
        val box = if (bottom) Box(0.0, 1.0 - band, 1.0, 1.0) else Box(0.0, 0.0, 1.0, band)
        return decode(bytes, Target(Seams.LENGTH, 0, capPixels = EDGE_CAP_PIXELS, box = box))
    }

    /** Smallest power-of-two sample size that brings a [w] x [h] image to at most [capPixels]: 1 below the cap. */
    fun capSample(w: Int, h: Int, capPixels: Long): Int {
        if (w <= 0 || h <= 0) return 1
        var sample = 1
        while (w.toLong() * h.toLong() / (sample.toLong() * sample.toLong()) > capPixels) sample *= 2
        return sample
    }

    // ------------------------------------------------------------------ quick previews

    /**
     * Kinds with a quick preview: JPEG and WebP decoders shrink while decoding, far
     * faster than a full decode (libjpeg-turbo's DCT scaling down to 1/8, libwebp's
     * scaled output); AVIF, from API 31, when the file holds a thumbnail or a layered
     * image's small first layer ([AvifPreview]). JPEG XL (jxl-coder has no progressive
     * pass), PNG and TIFF decode whole.
     */
    private val QUICK = setOf(ImageKind.JPEG, ImageKind.WEBP) +
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setOf(ImageKind.AVIF) else emptySet()

    /** File extensions of the [QUICK] kinds, for judging pages before reading them ([QuickPreviews]). */
    val QUICK_EXTENSIONS = setOf("jpg", "jpeg", "webp") +
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setOf("avif") else emptySet()

    /** Kinds whose quick preview is as good as one made from the full decode, so a capture takes their bytes. */
    private val QUICK_ENCODED = setOf(ImageKind.JPEG, ImageKind.WEBP)

    /**
     * All of [input] when it holds a JPEG or WebP image, the start of it an AVIF
     * preview needs ([AvifPreview.needed]), or null when it has no quick preview
     * ([preview]), having read only its first [ImageKind.HEADER_BYTES] (AVIF: its
     * metadata).
     */
    fun quickBytes(input: InputStream): ByteArray? {
        var head = ByteArray(ImageKind.HEADER_BYTES)
        var n = readFully(input, head, 0)
        val kind = ImageKind.sniff(head, n)
        if (kind !in QUICK) return null
        if (kind != ImageKind.AVIF) return head.copyOf(n) + BitmapUtils.readCapped(input)
        while (true) {
            val needed = AvifPreview.needed(head, n, Previews::sizeOf)
            if (needed <= 0 || (needed > n && n < head.size)) return null
            if (needed <= n) return head.copyOf(needed)
            head = head.copyOf(needed)
            n = readFully(input, head, n)
        }
    }

    /** Fills [buf] from [input] past its first [from] bytes; how many it holds then. */
    private fun readFully(input: InputStream, buf: ByteArray, from: Int, until: Int = buf.size): Int {
        var n = from
        while (n < until) {
            val read = input.read(buf, n, until - n)
            if (read < 0) break
            n += read
        }
        return n
    }

    /** What [headerSize] reads of a JPEG before the rest of its prefix. */
    private const val JPEG_HEADER_BYTES = 8 * 1024

    /**
     * The preview ([Previews.TARGET]) of [bytes], decoded at reduced size, or null when
     * its format has no quick path. Throws [DecodeException] when it does not decode.
     */
    fun preview(bytes: ByteArray): Bitmap? {
        val kind = ImageKind.sniff(bytes)
        if (kind !in QUICK) return null
        if (kind == ImageKind.AVIF && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val small = AvifPreview.extract(bytes, Previews::sizeOf) ?: return null
            return reported(kind) { quickAvif(ImageDecoder.createSource(ByteBuffer.wrap(small))) }
        }
        return reported(kind) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                quickPlatform(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), kind == ImageKind.JPEG)
            } else {
                quickLegacy(bytes)
            }
        }
    }

    /** An AVIF thumbnail or first layer, decoded as it is and then made the preview of its own size. */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun quickAvif(source: ImageDecoder.Source): Bitmap {
        val small = eightBit(ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        })
        return finishPreview(small, Previews.sizeOf(small.width, small.height))
    }

    /** JPEG at the largest DCT scaling that still covers the preview; WebP straight at the preview size. */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun quickPlatform(source: ImageDecoder.Source, jpeg: Boolean): Bitmap {
        var size = intArrayOf(0, 0)
        val reduced = eightBit(ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            size = Previews.sizeOf(w, h)
            if (jpeg) decoder.setTargetSampleSize(Previews.sampleFor(w, h, size[0], size[1]))
            else decoder.setTargetSize(size[0], size[1])
            decoder.setOnPartialImageListener {
                it.error == ImageDecoder.DecodeException.SOURCE_INCOMPLETE ||
                    it.error == ImageDecoder.DecodeException.SOURCE_MALFORMED_DATA
            }
        })
        return finishPreview(reduced, size)
    }

    private fun quickLegacy(bytes: ByteArray): Bitmap {
        val bounds = legacyBounds { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
            ?: throw DecodeException("Unsupported image format on this Android version")
        val size = Previews.sizeOf(bounds[0], bounds[1])
        val opts = BitmapFactory.Options().apply {
            inSampleSize = Previews.sampleFor(bounds[0], bounds[1], size[0], size[1])
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val reduced = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: throw DecodeException("Could not decode image")
        return finishPreview(reduced, size)
    }

    /** [reduced] when the decoder already made it [size], else its preview, recycling [reduced]. */
    private fun finishPreview(reduced: Bitmap, size: IntArray): Bitmap {
        if (reduced.width == size[0] && reduced.height == size[1] && reduced.config == Bitmap.Config.ARGB_8888) return reduced
        try {
            return Previews.make(reduced)
        } finally {
            reduced.recycle()
        }
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

    /** One JPEG strip or tile, reduced by [sample] inside libjpeg-turbo as the cap asks. */
    private fun tiffJpeg(jpeg: ByteArray, sample: Int): Tiff.Raster {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample
        }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            ?: throw DecodeException("Could not decode a JPEG strip of this TIFF image")
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return Tiff.Raster(bitmap.width, bitmap.height, pixels, sample)
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
