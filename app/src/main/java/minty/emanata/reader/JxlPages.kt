package minty.emanata.reader

import android.graphics.Bitmap
import minty.emanata.compact.LibJxl
import java.util.concurrent.CancellationException

/**
 * What [JxlPages] needs of native code: a JPEG XL decoded straight into a bitmap
 * ([LibJxl.page]). On the device this is [LibJxl]; JVM tests stand in their own.
 */
internal interface JxlPageKernel {
    /** A `LibJxl.PAGE_*` result. */
    fun page(jxl: ByteArray, out: Bitmap, threads: Int, cores: Long, nice: Int, stop: LibJxl.Stop?): Int
}

/** [LibJxl.page], where its library loads. */
private object LibJxlPages : JxlPageKernel {
    override fun page(jxl: ByteArray, out: Bitmap, threads: Int, cores: Long, nice: Int, stop: LibJxl.Stop?): Int =
        LibJxl.page(jxl, out, threads, cores, nice, stop)
}

/**
 * JPEG XL pages decoded natively, whole, in sRGB, straight into the bitmap that holds them:
 * 8-bit RGBA from libjxl's own output stage, no float buffer, no copy. libjxl's work runs on
 * one thread per large core ([CpuCores]), the caller's among them, pinned there: the little
 * cores add almost nothing to a decode and stall its last groups. Work nobody waits on yet
 * ([Urgency]), pages read ahead and previews, runs on [laterThreads], [laterOnLittle] and
 * [laterNice], read once when the decode starts: by default the same placement as a page
 * shown, which fast page turns measured best, so a page read ahead that comes on screen
 * mid-decode loses nothing.
 *
 * Images this path does not take (no sRGB rendering without a color manager, HDR) return
 * null, for jxl-coder to decode.
 */
object JxlPages {
    /** Off leaves every JPEG XL to jxl-coder, for A/B runs. */
    @Volatile
    var enabled: Boolean = true

    /** Threads for a page someone waits on: one per large core. */
    @Volatile
    var threads: Int = NativeResampler.defaultThreads(CpuCores.device, Runtime.getRuntime().availableProcessors())

    /** Threads for a page nobody waits on yet. */
    @Volatile
    var laterThreads: Int = threads

    /** Whether threads are pinned to the large cores; off leaves placement to the system. */
    @Volatile
    var pinned: Boolean = true

    /** Pins work nobody waits on to the little cores instead, for A/B runs. */
    @Volatile
    internal var laterOnLittle: Boolean = false

    /** Linux nice (0..19) of work nobody waits on, so it yields to the page shown; 0 keeps the caller's. */
    @Volatile
    var laterNice: Int = 0

    internal var cores: CpuCores? = null
        get() = field ?: CpuCores.device

    internal var kernel: JxlPageKernel? = null
        get() = field ?: loaded

    private val loaded: JxlPageKernel? by lazy { runCatching { LibJxl.version; LibJxlPages }.getOrNull() }

    /** The threads and cores (bit n = core n; 0 = any) a decode runs on, as [urgent] says. */
    internal fun placement(urgent: Boolean): Pair<Int, Long> {
        val c = cores!!
        val n = (if (urgent) threads else laterThreads).coerceIn(1, MAX_THREADS)
        val on = if (!pinned) 0L else if (laterOnLittle && !urgent) c.little else c.big
        return n to on
    }

    /**
     * [placement] for a region decode ([LibJxl.region]); off ([enabled]), one unpinned thread,
     * as region decodes ran before this path.
     */
    internal fun regionPlacement(urgent: Boolean): Pair<Int, Long> = if (enabled) placement(urgent) else 1 to 0L

    /**
     * [bytes], a JPEG XL shown [width] x [height], decoded whole into a new ARGB_8888 bitmap.
     * Null when this path does not take it or native code is missing, so the caller decodes it
     * another way. Throws [CancellationException] once [canceled] returns true, asked between
     * the decoder's parallel steps.
     */
    fun decode(bytes: ByteArray, width: Int, height: Int, urgent: Boolean = Urgency.current()(), canceled: () -> Boolean = Cancellation.current()): Bitmap? {
        if (!enabled) return null
        val k = kernel ?: return null
        val (n, on) = placement(urgent)
        val nice = if (urgent) 0 else laterNice.coerceIn(0, 19)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val result = try {
            k.page(bytes, out, n, on, nice) { canceled() }
        } catch (t: Throwable) {
            out.recycle()
            throw t
        }
        return when (result) {
            LibJxl.PAGE_OPAQUE -> out.apply { setHasAlpha(false) }
            LibJxl.PAGE_ALPHA -> out
            LibJxl.PAGE_CANCELED -> {
                out.recycle()
                throw CancellationException("JPEG XL decode canceled")
            }
            else -> {
                out.recycle()
                null
            }
        }
    }

    /** At most this many threads, whatever is asked. */
    private const val MAX_THREADS = 16
}
