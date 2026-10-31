package com.infinity.comicreader.compact

import android.graphics.Bitmap

/**
 * What a JPEG XL file's header tells: its size, samples ([bits] each, floating point
 * when [exponentBits] > 0), [colorChannels] (1 gray, 3 color), [alphaBits] (0 without
 * alpha) and [extraChannels] (alpha among them). [originalProfile] is false for XYB,
 * which only lossy files use; true is likely lossless. [srgb] when its profile is sRGB.
 */
data class JxlInfo(
    val width: Int,
    val height: Int,
    val bits: Int,
    val exponentBits: Int,
    val colorChannels: Int,
    val alphaBits: Int,
    val extraChannels: Int,
    val originalProfile: Boolean,
    val animated: Boolean,
    val srgb: Boolean,
    val alphaPremultiplied: Boolean,
    val alphaExponentBits: Int
) {
    /** Its channels decoded as they are: color, then alpha if any. */
    val channels: Int get() = colorChannels + (if (alphaBits > 0) 1 else 0)

    /**
     * True when it is stored losslessly as far as its header tells (not XYB) and its pixels
     * read whole as integers: one frame of up to 16 bits, no channel beyond alpha, alpha
     * not premultiplied.
     */
    val lossless: Boolean
        get() = originalProfile && !animated && exponentBits == 0 && alphaExponentBits == 0 && bits in 1..16 && alphaBits <= 16 &&
            extraChannels == (if (alphaBits > 0) 1 else 0) && !alphaPremultiplied

    /** The sample size that holds its pixels whole: 8 or 16 bits. */
    val sampleBits: Int get() = if (maxOf(bits, alphaBits) <= 8) 8 else 16

    companion object {
        fun of(values: IntArray): JxlInfo? {
            if (values.size < 12) return null
            return JxlInfo(
                values[0], values[1], values[2], values[3], values[4], values[5], values[6],
                values[7] != 0, values[8] != 0, values[9] != 0, values[10] != 0, values[11]
            )
        }
    }
}

/**
 * What compaction needs of libjxl: the byte-exact JPEG transcode and its reverse,
 * lossless encoding, decoding, and a file's header. Samples are interleaved, 16-bit
 * ones little-endian. Every call is single-threaded and returns null when it fails. On
 * the device this is [LibJxl]; JVM tests stand in their own.
 */
interface JxlCodec {
    /** True when libjxl is there to call. */
    val available: Boolean

    /** [jpeg] as a JPEG XL that rebuilds it byte for byte ([reconstruct]), at [effort] (1-9). */
    fun transcode(jpeg: ByteArray, effort: Int): ByteArray?

    /** The JPEG [jxl] was transcoded from, byte for byte; null when it holds none. */
    fun reconstruct(jxl: ByteArray): ByteArray?

    fun info(jxl: ByteArray): JxlInfo?

    /** [jxl]'s color profile as stored, to give another file ([encode]); opaque. */
    fun profile(jxl: ByteArray): ByteArray?

    /** The ICC profile of [jxl]'s decoded pixels. */
    fun iccOfPixels(jxl: ByteArray): ByteArray?

    /**
     * [jxl]'s first frame as [channels] samples of [bits] (8 or 16) per pixel, in its own
     * profile, or with [srgb] converted to sRGB (null where it cannot be: only XYB files can).
     */
    fun decode(jxl: ByteArray, channels: Int, bits: Int, srgb: Boolean = false): ByteArray?

    /**
     * Lossless JPEG XL of [pixels]: [channels] samples of [bits] per pixel (2 and 4 end
     * with alpha), in [profile] ([JxlCodec.profile]), sRGB when null, at [effort] (1-9).
     */
    fun encode(pixels: ByteArray, width: Int, height: Int, channels: Int, bits: Int, profile: ByteArray?, effort: Int): ByteArray?

    companion object {
        /** The first byte of a [profile] holding an ICC profile, which follows it (`PROFILE_ICC` in jxl_jni.c). */
        const val PROFILE_ICC: Byte = 1
    }
}

/** The libjxl release jxl_jni.c is compiled against (its headers, pinned in CMakeLists.txt). */
object JxlVersion {
    const val MAJOR = 0
    const val MINOR = 12

    /**
     * True when libjxl's [version] (major * 1000000 + minor * 1000 + patch) is of that
     * release: jxl_jni.c passes its structs by their layout, which another may change.
     */
    fun matches(version: Int): Boolean = version / 1000 == MAJOR * 1000 + MINOR
}

/**
 * libjxl as jxl-coder ships it, through our JNI binding (`src/main/cpp/jxl_jni.c`); used
 * only when it is the release the binding was compiled against ([JxlVersion]).
 */
object LibJxl : JxlCodec {
    init {
        // jxl-coder's own library, which jxl_jni.c opens by name.
        System.loadLibrary("jxl")
        System.loadLibrary("avifjni")
    }

    /** libjxl's version as major * 1000000 + minor * 1000 + patch; 0 when it is missing. */
    val version: Int by lazy { nVersion() }

    override val available: Boolean get() = JxlVersion.matches(version)

    override fun transcode(jpeg: ByteArray, effort: Int): ByteArray? = nTranscode(jpeg, effort)

    override fun reconstruct(jxl: ByteArray): ByteArray? = nReconstruct(jxl)

    override fun info(jxl: ByteArray): JxlInfo? = nInfo(jxl)?.let { JxlInfo.of(it) }

    override fun profile(jxl: ByteArray): ByteArray? = nProfile(jxl, false)

    override fun iccOfPixels(jxl: ByteArray): ByteArray? = nProfile(jxl, true)

    override fun decode(jxl: ByteArray, channels: Int, bits: Int, srgb: Boolean): ByteArray? = nDecode(jxl, channels, bits, srgb)

    override fun encode(pixels: ByteArray, width: Int, height: Int, channels: Int, bits: Int, profile: ByteArray?, effort: Int): ByteArray? =
        nEncode(pixels, width, height, channels, bits, profile, effort)

    /**
     * Decodes [jxl], shown [width] x [height], in sRGB into [out]: its rectangle [left]..[right]
     * x [top]..[bottom], every [sample]th pixel each way, so [out] is that span divided by
     * [sample], rounded up, ARGB_8888, on [threads] threads pinned to [cores] (bit n = core n;
     * 0 = any), asking [stop] between its parallel steps. The whole image decodes; only those
     * pixels are kept. [REGION_DONE]; [PAGE_CANCELED] once [stop] said so; else 0: it failed,
     * or the image is another size or has no sRGB rendering.
     */
    fun region(
        jxl: ByteArray, out: Bitmap, width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int, sample: Int, threads: Int, cores: Long,
        stop: Stop? = null
    ): Int = nRegion(jxl, out, width, height, left, top, right, bottom, sample, threads, cores, stop)

    /** What [region] returns once done. */
    const val REGION_DONE = 1

    /** Asked between a page or region decode's parallel steps, on its thread; true stops it. */
    fun interface Stop {
        fun stop(): Boolean
    }

    /** What [page] returned (`JP_*` in jxl_page.h). */
    const val PAGE_OPAQUE = 1
    const val PAGE_ALPHA = 2
    const val PAGE_UNSUPPORTED = -1
    const val PAGE_FAILED = 0
    const val PAGE_CANCELED = -2

    /**
     * Decodes [jxl]'s first frame in sRGB straight into [out], ARGB_8888 of the image's size as
     * shown, premultiplied, on [threads] threads (the caller's among them) pinned to [cores]
     * (bit n = core n; 0 = any) at Linux nice [nice] (0 = the caller's; never raised above
     * it), asking [stop] between its parallel steps. [PAGE_OPAQUE] or
     * [PAGE_ALPHA] when done; [PAGE_UNSUPPORTED] when the image is another size, has no sRGB
     * rendering without a color manager, or is HDR, or that libjxl is missing; [PAGE_FAILED]
     * when it does not decode; [PAGE_CANCELED] once [stop] said so.
     */
    fun page(jxl: ByteArray, out: Bitmap, threads: Int, cores: Long, nice: Int = 0, stop: Stop? = null): Int =
        nPage(jxl, out, threads, cores, nice, stop)

    /**
     * Runs [block] with the calling thread pinned to [cores] (bit n = core n), so threads it
     * starts, as jxl-coder's decoder does, start there too; where the system refuses, unpinned.
     */
    fun <T> onCores(cores: Long, block: () -> T): T {
        val kept = if (cores != 0L) pin(cores) else 0L
        try {
            return block()
        } finally {
            if (kept != 0L) pin(kept)
        }
    }

    /** Pins the calling thread to [cores] (bit n = core n); the mask it had, or 0 where the system refuses. */
    fun pin(cores: Long): Long = nPin(cores)

    @JvmStatic
    private external fun nPin(cores: Long): Long

    @JvmStatic
    private external fun nPage(jxl: ByteArray, out: Bitmap, threads: Int, cores: Long, nice: Int, stop: Stop?): Int

    @JvmStatic
    private external fun nVersion(): Int

    @JvmStatic
    private external fun nTranscode(jpeg: ByteArray, effort: Int): ByteArray?

    @JvmStatic
    private external fun nReconstruct(jxl: ByteArray): ByteArray?

    @JvmStatic
    private external fun nInfo(jxl: ByteArray): IntArray?

    @JvmStatic
    private external fun nProfile(jxl: ByteArray, data: Boolean): ByteArray?

    @JvmStatic
    private external fun nDecode(jxl: ByteArray, channels: Int, bits: Int, srgb: Boolean): ByteArray?

    @JvmStatic
    private external fun nRegion(
        jxl: ByteArray, out: Bitmap, width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int, sample: Int, threads: Int, cores: Long,
        stop: Stop?
    ): Int

    @JvmStatic
    private external fun nEncode(pixels: ByteArray, width: Int, height: Int, channels: Int, bits: Int, profile: ByteArray?, effort: Int): ByteArray?
}
