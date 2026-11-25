package minty.emanata.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.JxlResizeFilter
import com.awxkee.jxlcoder.PreferredColorConfig
import com.awxkee.jxlcoder.ScaleMode
import minty.emanata.compact.LibJxl
import minty.emanata.net.RemoteFile
import minty.emanata.net.Remotes
import minty.emanata.util.Formats
import minty.emanata.util.Traces
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
 *  3. it is resampled as [Target.scaling] says ([BitmapUtils.resample]) to the target size,
 *     and the full-resolution bitmap is recycled at once.
 *
 * Memory guard: a source above [Target.capPixels] (50 MP by default) is first reduced
 * by the smallest power-of-two sample size that brings it under the cap, inside the
 * decoder ([capSample]), and only then resampled. That reduction is the decoder's own:
 * libjpeg-turbo's DCT scaling for JPEG, an area average for WebP, point sampling for
 * PNG and AVIF, jxl-coder's Mitchell filter for JPEG XL. Only images past the cap take it.
 *
 * Quick previews ([preview]) are the one exception: JPEG and WebP decode already
 * shrunk toward [Previews.TARGET], AVIF from a thumbnail or first layer inside the
 * file, to stand in until the exact page is ready. A layered AVIF's first layer also
 * stands in while its page decodes ([standingIn]).
 *
 * JPEG XL decodes natively into the bitmap ([JxlPages]), else with jxl-coder, and AVIF
 * with libavif ([Avif]) on every API level,
 * TIFF with the built-in [Tiff] (its cap reduction is point sampling, DCT scaling for
 * JPEG strips and tiles). Other formats use [ImageDecoder] on API 28+, with
 * [BitmapFactory] below that, the reader's pages on [PlatformPages]' cores.
 */
object Decoders {
    private const val TAG = "Decoders"

    /** [bytes] decoded for [target], as app work calibration waits out ([Calibrator.working]). */
    fun decode(bytes: ByteArray, target: Target): Bitmap = Calibrator.working { decodeBytes(bytes, target) }

    private fun decodeBytes(bytes: ByteArray, target: Target): Bitmap {
        val kind = ImageKind.sniff(bytes)
        if (kind in QUICK_ENCODED) sinks.get()?.let { sink ->
            sinks.set(null)
            sink.encoded(bytes)
        }
        val size = if (measuresCrop(target) || target.banded) runCatching { headerSize(bytes.inputStream()) ?: size(bytes) }.getOrNull() else null
        val sampled = sampledFor(target, size)
        return reported(kind) {
            val full = Traces.section({ "decode ${kind?.name ?: "unknown"} ${bytes.size / 1024} KB" }) {
                when {
                    kind == ImageKind.JXL -> decodeJxl(bytes, sampled.capPixels, standIn = sampled != target)
                    kind == ImageKind.AVIF -> decodeAvif(bytes, sampled)
                    kind == ImageKind.TIFF -> decodeTiff(Tiff.ByteInput(bytes), sampled.capPixels)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> PlatformPages.placed(PlatformPages.wanted(kind == ImageKind.JPEG)) {
                        decodePlatform(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), sampled.capPixels)
                    }
                    else -> PlatformPages.placed(PlatformPages.wanted(kind == ImageKind.JPEG)) { decodeLegacy(bytes, sampled.capPixels) }
                }
            }
            sidesSinks.get()?.let { sink ->
                sidesSinks.set(null)
                runCatching { sidesOf(full) }.getOrNull()?.let(sink)
            }
            fit(full, sampled, size.takeIf { measuresCrop(target) })
        }
    }

    /** Where this thread's next decode leaves its page's sides ([findingSides]). */
    private val sidesSinks = ThreadLocal<((SpreadSeams.Sides) -> Unit)?>()

    /**
     * Runs [block], handing the [SpreadSeams.Sides] of the first image decoded from bytes in it
     * on this thread to [sink] ([sidesOf]), so a seam check need not decode that page again.
     */
    fun <T> findingSides(sink: (SpreadSeams.Sides) -> Unit, block: () -> T): T {
        val outer = sidesSinks.get()
        sidesSinks.set(sink)
        try {
            return block()
        } finally {
            if (outer == null) sidesSinks.remove() else sidesSinks.set(outer)
        }
    }

    /**
     * [target] decoding a [size] source sampled down to at most [Target.BASE_DECODE_PIXELS]
     * when it shows it in bands ([Target.bandsAt]), its bitmap only a stand-in; else itself.
     */
    internal fun sampledFor(target: Target, size: IntArray?): Target {
        if (!target.banded || size == null || size[0] <= 0 || size[1] <= 0 || target.capPixels <= Target.BASE_DECODE_PIXELS) return target
        val area = target.area(size[0], size[1])
        if (!target.bandsAt((area[2] - area[0]).toDouble(), (area[3] - area[1]).toDouble())) return target
        return target.copy(capPixels = Target.BASE_DECODE_PIXELS)
    }

    fun decode(input: InputStream, target: Target): Bitmap = decode(BitmapUtils.readCapped(input), target)

    /**
     * Streams [file] to the platform decoder; JPEG XL, AVIF, and any image below API 28,
     * is read whole first, as is one on a share.
     */
    fun decode(file: File, target: Target): Bitmap = Calibrator.working { decodeFile(file, target) }

    private fun decodeFile(file: File, target: Target): Bitmap {
        if (file is RemoteFile) return decode(Remotes.readBytes(file.path), target)
        val kind = ImageKind.sniff(readPrefix(file, ImageKind.HEADER_BYTES))
        val size = if (kind != ImageKind.JXL && kind != ImageKind.AVIF && (measuresCrop(target) || target.banded)) size(file) else null
        val sampled = sampledFor(target, size)
        val source = size.takeIf { measuresCrop(target) }
        if (kind == ImageKind.TIFF) {
            return reported(kind) { fit(RandomAccessFile(file, "r").use { decodeTiff(Tiff.FileInput(it), sampled.capPixels) }, sampled, source) }
        }
        if (kind == ImageKind.JXL || kind == ImageKind.AVIF || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return file.inputStream().use { decode(it, target) }
        }
        return reported(kind) {
            val full = Traces.section({ "decode ${kind?.name ?: "unknown"} file ${file.length() / 1024} KB" }) {
                PlatformPages.placed(PlatformPages.wanted(kind == ImageKind.JPEG)) { decodePlatform(ImageDecoder.createSource(file), sampled.capPixels) }
            }
            fit(full, sampled, source)
        }
    }

    /** What a decode leaves its page preview with ([capturing]). */
    interface PreviewSink {
        /** The encoded image, for a format whose quick preview ([preview]) is a proper one; [decoded] is not called then. */
        fun encoded(bytes: ByteArray)

        /**
         * The image at its decoded size, before any crop or resampling (of a layered AVIF,
         * its first layer, as soon as it is decoded); must leave it as it is.
         */
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

    private class StandIn(val show: (Bitmap) -> Unit, val wanted: () -> Boolean)

    /** Where this thread's next decode leaves its stand-in ([standingIn]). */
    private val standIns = ThreadLocal<StandIn?>()

    /**
     * Runs [block], handing [show] a stand-in for the first image decoded in it on this
     * thread, when that image has one ready well before itself: the first layer of a
     * layered AVIF, made to the same target but never enlarged ([fit]), to keep. It is
     * made only when [wanted] says so as it arrives.
     */
    fun <T> standingIn(show: (Bitmap) -> Unit, wanted: () -> Boolean = { true }, block: () -> T): T {
        val outer = standIns.get()
        standIns.set(StandIn(show, wanted))
        try {
            return block()
        } finally {
            if (outer == null) standIns.remove() else standIns.set(outer)
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
     *
     * A [standIn], a smaller version of the image standing in for it, is never enlarged
     * ([Plan.notEnlarged]) and leaves this thread's [capturing] and [measuring] alone.
     */
    fun fit(full: Bitmap, target: Target, source: IntArray? = null, standIn: Boolean = false): Bitmap {
        val src = if (full.config == Bitmap.Config.ARGB_8888) full else full.copy(Bitmap.Config.ARGB_8888, false).also {
            full.recycle()
        }
        if (!standIn) sinks.get()?.let { sink ->
            sinks.set(null)
            sink.decoded(src)
        }
        val bounds = if (target.autoCrop && target.box == null) {
            Traces.section("auto-crop scan") { BitmapUtils.contentBounds(src) }?.let { intArrayOf(it.left, it.top, it.right, it.bottom) }.also { found ->
                if (!standIn) {
                    val (box, size) = SampledCrop.of(found, src.width, src.height, source)
                    measured(box, size[0], size[1])
                }
            }
        } else {
            null
        }
        val area = target.area(src.width, src.height, bounds)
        val plan = target.plan((area[2] - area[0]).toDouble(), (area[3] - area[1]).toDouble(), area[0].toDouble(), area[1].toDouble())
            .let { if (standIn) it.notEnlarged() else it }
        if (plan.isIdentity(src.width, src.height) && target.scaling.sharpen == 0) return src
        // A stand-in for a page drawn in bands is only seen until they arrive: scaled the quick way.
        val quick = !standIn && target.bandsAt((area[2] - area[0]).toDouble(), (area[3] - area[1]).toDouble())
        try {
            return when {
                plan.isCrop() -> Traces.section("crop") { BitmapUtils.crop(src, plan, target.scaling) }
                quick -> Traces.section("scale quick") { BitmapUtils.scaleQuick(src, plan) }
                else -> BitmapUtils.resample(src, plan, target.linear && plan.shrinks(), target.scaling)
            }
        } finally {
            // Frees the full-resolution pixels before the caller keeps the result.
            src.recycle()
        }
    }

    /** Intrinsic `[width, height]` as a decode would return it at full size, without decoding pixels; null if unknown. */
    fun size(bytes: ByteArray): IntArray? = when (ImageKind.sniff(bytes)) {
        ImageKind.PNG -> pngSize(bytes) { platformSize(bytes) }
        ImageKind.JXL -> jxlSize(bytes)
        ImageKind.AVIF -> Avif.size(bytes)
        ImageKind.TIFF -> Tiff.size(Tiff.ByteInput(bytes))
        else -> platformSize(bytes)
    }

    fun size(input: InputStream): IntArray? = size(BitmapUtils.readCapped(input))

    /**
     * Like [size] on an image's first [ImageHeaders.PREFIX_BYTES] alone, without reading
     * the rest of [input]: null when they do not tell, so the caller reads it whole.
     * JPEG, PNG and JPEG XL headers are parsed here, AVIF metadata by libavif; other
     * formats go to the platform's header pass, which needs only their start too. TIFF
     * is left to the whole read. A JPEG's frame header usually lies in its first
     * [JPEG_HEADER_BYTES], read first.
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
                ImageKind.AVIF -> Avif.size(bytes, complete = n < head.size)
                ImageKind.TIFF -> null
                else -> platformSize(bytes)
            }
        }.getOrNull()
    }

    /** Like [size] on bytes, reading only the first [ImageHeaders.PREFIX_BYTES] unless a JPEG XL header or AVIF metadata lies beyond. */
    fun size(file: File): IntArray? = runCatching {
        if (file is RemoteFile) return size(Remotes.readBytes(file.path))
        val head = readPrefix(file, ImageHeaders.PREFIX_BYTES)
        when (ImageKind.sniff(head)) {
            ImageKind.PNG -> pngSize(head) { platformSize(file) }
            ImageKind.JXL -> ImageHeaders.jxlSize(head) ?: file.inputStream().use { jxlSize(BitmapUtils.readCapped(it)) }
            ImageKind.AVIF -> Avif.size(head, complete = head.size < ImageHeaders.PREFIX_BYTES)
                ?: file.inputStream().use { Avif.size(BitmapUtils.readCapped(it)) }
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
    private inline fun <T> reported(kind: ImageKind?, decode: () -> T): T {
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

    /** File extensions of JPEG, whose side strips ([sides]) skip the transform of the columns between. */
    private val CHEAP_SIDES_EXTENSIONS = setOf("jpg", "jpeg")

    /**
     * True when page [name] is named as JPEG, whose [sides] cost a fraction of a decode. A PNG
     * or WebP strip as tall as the page decodes every row whole; other formats decode whole.
     */
    fun cheapSidesNamed(name: String?): Boolean = Formats.ext(name.orEmpty()) in CHEAP_SIDES_EXTENSIONS

    /**
     * A region decoder of [bytes], a [REGION_KINDS] image shown [w] x [h], whose pixels are
     * laid out as shown: none where an EXIF orientation may turn or mirror the image, as
     * region decodes ignore it, or where the decoder sees another size.
     */
    private fun regionDecoder(bytes: ByteArray, w: Int, h: Int): BitmapRegionDecoder? {
        val kind = ImageKind.sniff(bytes)
        if (kind !in REGION_KINDS) return null
        if (kind == ImageKind.JPEG && ImageHeaders.jpegOrientation(bytes) != 1) return null
        if (kind == ImageKind.PNG && ImageHeaders.pngMayHaveExif(bytes)) return null
        val decoder = newRegionDecoder(bytes) ?: return null
        if (decoder.width == w && decoder.height == h) return decoder
        decoder.recycle()
        return null
    }

    private fun newRegionDecoder(bytes: ByteArray): BitmapRegionDecoder? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        }

    /**
     * Opens a [RegionReader] over encoded [bytes] as stored, before any EXIF orientation;
     * null when the platform has none for them. Tests stand in for it: Robolectric has none.
     */
    @VisibleForTesting
    internal var openRegions: (ByteArray) -> RegionReader? = { bytes -> newRegionDecoder(bytes)?.let { PlatformRegions(it) } }

    /**
     * The JPEG a JPEG XL was transcoded from, rebuilt byte for byte ([LibJxl.reconstruct]);
     * null when it holds none or libjxl is missing. Tests stand in for it.
     */
    @VisibleForTesting
    internal var jpegOf: (ByteArray) -> ByteArray? = { jxl -> runCatching { if (LibJxl.available) LibJxl.reconstruct(jxl) else null }.getOrNull() }

    /**
     * Opens a [RegionReader] over a JPEG XL of pixels [bytes] shown [w] x [h], each read
     * decoding it whole natively ([JxlRegions]); null where libjxl is missing or cannot give
     * it in sRGB. Tests stand in for it.
     */
    @VisibleForTesting
    internal var openJxlRegions: (ByteArray, Int, Int) -> RegionReader? = { bytes, w, h ->
        runCatching { LibJxl.takeIf { it.available }?.info(bytes)?.takeIf { it.srgb || !it.originalProfile } }.getOrNull()?.let { JxlRegions(bytes, w, h) }
    }

    /**
     * A [RegionReader] of [bytes], an image shown [w] x [h], reading its pixels as shown:
     * a [REGION_KINDS] image, a JPEG turned by its EXIF orientation too ([OrientedRegions]),
     * a JPEG XL holding a JPEG through that JPEG ([jpegOf]), as Compact writes them, else
     * its own pixels ([openJxlRegions]); progressive JPEGs and JPEG XL of pixels, any part
     * of which decodes all of it, through rows kept between reads ([KeptRows]). Null for
     * other kinds, a PNG an eXIf chunk may turn, or where the reader sees another size.
     */
    private fun regionReader(bytes: ByteArray, w: Int, h: Int): RegionReader? {
        val kind = ImageKind.sniff(bytes)
        if (kind == ImageKind.JXL) {
            jpegOf(bytes)?.takeIf { ImageKind.sniff(it) == ImageKind.JPEG }?.let { jpeg -> regionReader(jpeg, w, h)?.let { return it } }
            return openJxlRegions(bytes, w, h)?.let { KeptRows(it, KEPT_READ_PIXELS) }
        }
        if (kind !in REGION_KINDS) return null
        if (kind == ImageKind.PNG && ImageHeaders.pngMayHaveExif(bytes)) return null
        val orientation = if (kind == ImageKind.JPEG) ImageHeaders.jpegOrientation(bytes) ?: return null else 1
        val raw = openRegions(bytes) ?: return null
        val oriented = if (orientation in 2..8) OrientedRegions(raw, orientation) else raw
        // Any part of a progressive JPEG decodes every scan of all of it: reads share a few.
        val reader = if (kind == ImageKind.JPEG && ImageHeaders.jpegProgressive(bytes)) KeptRows(oriented, KEPT_READ_PIXELS) else oriented
        if (reader.width == w && reader.height == h) return reader
        reader.close()
        return null
    }

    /**
     * The full detail of [bytes] for [target] ([PageSource.loadDetail]): read a rectangle at
     * a time where it is large ([regionDetail]), else decoded whole within [maxBytes]. Blocking.
     */
    fun detail(bytes: ByteArray, target: Target, maxBytes: Long): PageDetail {
        regionDetail(bytes, target)?.let { return it }
        val size = runCatching { headerSize(bytes.inputStream()) ?: size(bytes) }.getOrNull()
        return BitmapDetail(decode(bytes, target.detail(size, maxBytes)))
    }

    /** Most pixels the detail of an image any part of which decodes whole keeps of one read, for the reads after it ([KeptRows]): 32 MB of ARGB. */
    private const val KEPT_READ_PIXELS = 8_000_000L

    /** Pixels above which a page's detail is read a rectangle at a time ([RegionDetail]) rather than decoded whole. */
    private const val REGION_DETAIL_PIXELS = 16_000_000L

    /**
     * Detail of [bytes], a [REGION_KINDS] image, for [target], read a rectangle at a time
     * ([RegionDetail]) when it is drawn in bands ([Target.bandsAt]) or holds more than
     * [REGION_DETAIL_PIXELS]; null otherwise, or where it cannot be read so. Auto-crop is
     * found as the page's own decode finds it, on the same sampling ([sampledFor]). Blocking.
     */
    fun regionDetail(bytes: ByteArray, target: Target): PageDetail? {
        val size = runCatching { headerSize(bytes.inputStream()) ?: size(bytes) }.getOrNull() ?: return null
        val w = size[0]
        val h = size[1]
        if (w <= 0 || h <= 0) return null
        val whole = target.area(w, h)
        val large = w.toLong() * h > REGION_DETAIL_PIXELS || target.bandsAt((whole[2] - whole[0]).toDouble(), (whole[3] - whole[1]).toDouble())
        if (!large) return null
        val reader = runCatching { regionReader(bytes, w, h) }.getOrNull() ?: return null
        try {
            val bounds = if (target.autoCrop && target.box == null) cropOf(bytes, target, size) else null
            return RegionDetail(reader, target.area(w, h, bounds), bytes.size.toLong())
        } catch (t: Throwable) {
            reader.close()
            throw t
        }
    }

    /** What auto-crop finds on [bytes], a [size] image, decoded as a page for [target] decodes it; null when nothing. */
    private fun cropOf(bytes: ByteArray, target: Target, size: IntArray): IntArray? {
        var found: Box? = null
        measuring({ box, _, _ -> found = box }) {
            decode(bytes, Target(16, 16, autoCrop = true, capPixels = sampledFor(target, size).capPixels)).recycle()
        }
        return found?.takeIf { it != Box.FULL }?.pixels(size[0], size[1])
    }

    /** A [RegionReader] over a platform region decoder. */
    private class PlatformRegions(private val decoder: BitmapRegionDecoder) : RegionReader {
        override val width: Int get() = decoder.width
        override val height: Int get() = decoder.height

        override fun read(left: Int, top: Int, right: Int, bottom: Int, sample: Int): Bitmap =
            decoder.decodeRegion(Rect(left, top, right, bottom), BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: throw DecodeException("Could not decode part of this image")

        override fun close() = decoder.recycle()
    }

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
    fun edge(bytes: ByteArray, bottom: Boolean): Seams.Profile? = Calibrator.working { edgeOf(bytes, bottom) }

    private fun edgeOf(bytes: ByteArray, bottom: Boolean): Seams.Profile? {
        val size = runCatching { headerSize(bytes.inputStream()) ?: size(bytes) }.getOrNull() ?: return null
        val w = size[0]
        val h = size[1]
        if (w <= 0 || h <= 0) return null
        val strip = runCatching { regionStrip(bytes, w, h, bottom) }.getOrNull()
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

    /** The power-of-two sample that leaves a [w]-long side of an image at least [Seams.LENGTH] long. */
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
        val decoder = regionDecoder(bytes, w, h) ?: return null
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

    /** Height a whole decode for [sides] shrinks a page to: twice [Seams.LENGTH]. */
    private const val SIDES_HEIGHT = Seams.LENGTH * 2

    /**
     * Both [SpreadSeams.Sides] of an encoded image, or null when it does not decode. JPEG,
     * PNG and WebP decode only a strip of [SpreadSeams.COLUMNS] columns at each side, sampled
     * down toward [Seams.LENGTH] tall ([BitmapRegionDecoder]); other formats decode whole,
     * [SIDES_HEIGHT] tall. For analysis only, never shown. Blocking.
     */
    fun sides(bytes: ByteArray): SpreadSeams.Sides? = Calibrator.working { sidesFrom(bytes) }

    private fun sidesFrom(bytes: ByteArray): SpreadSeams.Sides? {
        val size = runCatching { headerSize(bytes.inputStream()) ?: size(bytes) }.getOrNull() ?: return null
        val w = size[0]
        val h = size[1]
        if (w <= 0 || h <= 0) return null
        runCatching { regionSides(bytes, w, h) }.getOrNull()?.let { return it }
        return runCatching { wholeSides(bytes) }.getOrNull()
    }

    /**
     * Both sides of a decoded [page], as [sides] finds them in its encoded image: the
     * strips of its outer columns, box-averaged down by [edgeSample] of its height.
     */
    internal fun sidesOf(page: Bitmap): SpreadSeams.Sides? {
        val w = page.width
        val h = page.height
        if (w <= 0 || h <= 0) return null
        val sample = edgeSample(h)
        val columns = (SpreadSeams.COLUMNS * sample).coerceAtMost(w)
        val strip = IntArray(columns * h)
        fun side(right: Boolean): SpreadSeams.Edge? {
            page.getPixels(strip, 0, columns, if (right) w - columns else 0, 0, columns, h)
            val sw = (columns / sample).coerceAtLeast(1)
            val sh = (h / sample).coerceAtLeast(1)
            return SpreadSeams.edge(boxDown(strip, columns, h, sample), sw, sh, right)
        }
        val left = side(right = false) ?: return null
        val right = side(right = true) ?: return null
        return SpreadSeams.Sides(left, right)
    }

    /**
     * [w] x [h] ARGB [pixels] shrunk by [sample] each way, each pixel the mean of its block
     * (opaque), as a decoder's sampled output: (w / [sample]) x (h / [sample]), at least 1 x 1.
     */
    internal fun boxDown(pixels: IntArray, w: Int, h: Int, sample: Int): IntArray {
        if (sample <= 1) return pixels
        val ow = (w / sample).coerceAtLeast(1)
        val oh = (h / sample).coerceAtLeast(1)
        val out = IntArray(ow * oh)
        for (oy in 0 until oh) {
            val y0 = oy * sample
            val y1 = minOf(h, y0 + sample)
            for (ox in 0 until ow) {
                val x0 = ox * sample
                val x1 = minOf(w, x0 + sample)
                var r = 0
                var g = 0
                var b = 0
                for (y in y0 until y1) for (x in x0 until x1) {
                    val p = pixels[y * w + x]
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                }
                val n = (y1 - y0) * (x1 - x0)
                out[oy * ow + ox] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return out
    }

    /** The side strips of a [w] x [h] image, sampled down ([edgeSample] of its height), decoded alone. */
    private fun regionSides(bytes: ByteArray, w: Int, h: Int): SpreadSeams.Sides? {
        val sample = edgeSample(h)
        val columns = (SpreadSeams.COLUMNS * sample).coerceAtMost(w)
        val decoder = regionDecoder(bytes, w, h) ?: return null
        try {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val left = sideOf(decoder.decodeRegion(Rect(0, 0, columns, h), options) ?: return null, right = false) ?: return null
            val right = sideOf(decoder.decodeRegion(Rect(w - columns, 0, w, h), options) ?: return null, right = true) ?: return null
            return SpreadSeams.Sides(left, right)
        } finally {
            decoder.recycle()
        }
    }

    /** The [right] or left [SpreadSeams.Edge] of [strip], which is recycled. */
    private fun sideOf(strip: Bitmap, right: Boolean): SpreadSeams.Edge? = try {
        SpreadSeams.edge(pixelsOf(strip), strip.width, strip.height, right)
    } finally {
        strip.recycle()
    }

    /** Both sides of an image decoded whole, [SIDES_HEIGHT] tall at most. */
    private fun wholeSides(bytes: ByteArray): SpreadSeams.Sides? {
        val page = decode(bytes, Target(0, SIDES_HEIGHT, Fit.SHRINK, capPixels = EDGE_CAP_PIXELS))
        return try {
            SpreadSeams.sides(pixelsOf(page), page.width, page.height)
        } finally {
            page.recycle()
        }
    }

    private fun pixelsOf(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

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
     * scaled output); AVIF when the file holds a thumbnail or a layered image's small
     * first layer ([AvifPreview]). JPEG XL (jxl-coder has no progressive pass), PNG and
     * TIFF decode whole.
     */
    private val QUICK = setOf(ImageKind.JPEG, ImageKind.WEBP, ImageKind.AVIF)

    /** File extensions of the [QUICK] kinds, for judging pages before reading them ([QuickPreviews]). */
    val QUICK_EXTENSIONS = setOf("jpg", "jpeg", "webp", "avif")

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
        return avifPrefix(input, head, n, Previews::sizeOf)
    }

    /**
     * The start of the AVIF file [input] continues from [head] (its first [length] bytes)
     * that its preview ([AvifPreview.needed], thumbnails of at least [minSize]) needs; null
     * when it has none or [input] ends first.
     */
    private fun avifPrefix(input: InputStream, head: ByteArray, length: Int, minSize: (Int, Int) -> IntArray): ByteArray? {
        var buf = head
        var n = length
        while (true) {
            val needed = AvifPreview.needed(buf, n, minSize)
            if (needed <= 0 || (needed > n && n < buf.size)) return null
            if (needed <= n) return buf.copyOf(needed)
            buf = buf.copyOf(needed)
            n = readFully(input, buf, n)
        }
    }

    /**
     * The first layer of the layered AVIF image [input] holds, at its own size, read from
     * as little of [input] as that layer needs; null for any other image, or when it does
     * not decode. Blocking.
     */
    fun firstLayer(input: InputStream): Bitmap? {
        val head = ByteArray(ImageKind.HEADER_BYTES)
        val n = readFully(input, head, 0)
        if (ImageKind.sniff(head, n) != ImageKind.AVIF) return null
        // No thumbnail is that large, so the plan is the first layer.
        val prefix = avifPrefix(input, head, n) { _, _ -> intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE) } ?: return null
        return try {
            Avif.firstLayer(prefix)?.let { eightBit(it) }
        } catch (e: IOException) {
            null
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
    fun preview(bytes: ByteArray): Bitmap? = Calibrator.working { Traces.section("quick preview") { quick(bytes) } }

    private fun quick(bytes: ByteArray): Bitmap? {
        val kind = ImageKind.sniff(bytes)
        if (kind !in QUICK) return null
        if (kind == ImageKind.AVIF) return reported(kind) { quickAvif(bytes) }
        return reported(kind) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                quickPlatform(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), kind == ImageKind.JPEG)
            } else {
                quickLegacy(bytes)
            }
        }
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

    // ------------------------------------------------------------------ AVIF

    /**
     * Full decode through libavif. Of a layered image, the first layer goes to this
     * thread's preview sink ([capturing]) when it is as large as the preview, and to its
     * stand-in ([standingIn]) while one is wanted, as soon as it is decoded; neither ever
     * fails the decode. A sink left without it gets the full image ([fit]).
     */
    private fun decodeAvif(bytes: ByteArray, target: Target): Bitmap {
        if (sinks.get() == null && standIns.get() == null) return Avif.decode(bytes, target.capPixels)
        return Avif.decode(bytes, target.capPixels) { layer, full ->
            sinks.get()?.let { sink ->
                val size = Previews.sizeOf(full[0], full[1])
                if (layer.width >= size[0] && layer.height >= size[1]) {
                    sinks.set(null)
                    runCatching { sink.decoded(layer) }
                }
            }
            val standIn = standIns.get()
            if (standIn == null || !standIn.wanted()) {
                layer.recycle()
            } else {
                standIns.set(null)
                try {
                    standIn.show(fit(layer, target, standIn = true))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Stand-in skipped", e)
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "Stand-in skipped", e)
                }
            }
        }
    }

    /** An AVIF thumbnail or first layer ([AvifPreview]) made the preview of its own size; null when the file has none. */
    private fun quickAvif(bytes: ByteArray): Bitmap? {
        AvifPreview.thumbnail(bytes, Previews::sizeOf)?.let { file ->
            val small = Avif.decode(file, Target.DEFAULT_CAP_PIXELS)
            return finishPreview(small, Previews.sizeOf(small.width, small.height))
        }
        val layer = Avif.firstLayer(bytes, Previews::sizeOf) ?: return null
        return finishPreview(layer, Previews.sizeOf(layer.width, layer.height))
    }

    // ------------------------------------------------------------------ JPEG XL

    /** Largest JPEG XL image decoded. jxl-coder holds the whole image in native memory, whatever the cap. */
    const val MAX_JXL_PIXELS = 40_000_000L

    /** JPEG XL decodes running at once, bounding that native memory. */
    private val jxlGate = Semaphore(2)

    /** Largest decode only measured, never shown (a page's hash for the similar-books search). */
    const val MEASURE_CAP_PIXELS = 65_536L

    /**
     * How a JPEG XL decode capped at [MEASURE_CAP_PIXELS] or less shrinks: point-sampled
     * natively ([openJxlRegions]), never holding the whole image, when true; else by
     * jxl-coder's filtered sampled decode, as where libjxl is missing. On by default: about
     * five times faster on a Snapdragon 8 Gen 1 (API 36) for synthetic 4000 x 6000 pages,
     * with the same books found alike.
     */
    @Volatile
    var jxlMeasuredByRegions = true

    /**
     * Full decode, straight into the bitmap where native code takes the image ([JxlPages]),
     * else by jxl-coder; only past [capPixels] does jxl-coder's sampled decode do the coarse
     * reduction. A [standIn] for a page drawn in bands, and a decode only measured as
     * [jxlMeasuredByRegions] says, is point-sampled natively instead ([openJxlRegions]),
     * so the whole image is never held.
     */
    private fun decodeJxl(bytes: ByteArray, capPixels: Long, standIn: Boolean = false): Bitmap {
        val size = jxlSize(bytes) ?: throw DecodeException("Not a valid JXL image")
        if (size[0].toLong() * size[1] > MAX_JXL_PIXELS) {
            throw DecodeException("JXL image is too large (${size[0]} x ${size[1]})")
        }
        val sample = capSample(size[0], size[1], capPixels)
        if (sample > 1 && (standIn || (jxlMeasuredByRegions && capPixels <= MEASURE_CAP_PIXELS))) {
            runCatching { openJxlRegions(bytes, size[0], size[1])?.read(0, 0, size[0], size[1], sample) }.getOrNull()?.let { return it }
        }
        jxlGate.acquireUninterruptibly()
        try {
            if (sample == 1) return JxlPages.decode(bytes, size[0], size[1]) ?: JxlCoder.decode(bytes, PreferredColorConfig.RGBA_8888)
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

    /**
     * Rectangles of a JPEG XL of pixels shown [width] x [height], each decoding it whole natively
     * ([LibJxl.region]) on [JxlPages.regionPlacement]'s threads and cores, sampled by point.
     * Throws [CancellationException] once the thread's [Cancellation] says so.
     */
    private class JxlRegions(private val bytes: ByteArray, override val width: Int, override val height: Int) : RegionReader {
        override fun read(left: Int, top: Int, right: Int, bottom: Int, sample: Int): Bitmap {
            val out = Bitmap.createBitmap((right - left + sample - 1) / sample, (bottom - top + sample - 1) / sample, Bitmap.Config.ARGB_8888)
            val result = try {
                jxlGate.acquireUninterruptibly()
                try {
                    val (threads, cores) = JxlPages.regionPlacement(Urgency.current()())
                    val canceled = Cancellation.current()
                    LibJxl.region(bytes, out, width, height, left, top, right, bottom, sample, threads, cores) { canceled() }
                } finally {
                    jxlGate.release()
                }
            } catch (t: Throwable) {
                out.recycle()
                throw t
            }
            if (result == LibJxl.REGION_DONE) return out
            out.recycle()
            if (result == LibJxl.PAGE_CANCELED) throw CancellationException("JPEG XL region decode canceled")
            throw DecodeException("Could not decode part of this JXL image")
        }

        override fun close() = Unit
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
