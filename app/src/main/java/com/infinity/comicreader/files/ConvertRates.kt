package com.infinity.comicreader.files

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.infinity.comicreader.compact.Compactor
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.net.ShareSpeeds
import com.infinity.comicreader.reader.Calibration
import com.infinity.comicreader.reader.Calibrator
import com.infinity.comicreader.reader.ImageKind
import org.json.JSONObject
import java.io.File

/**
 * This device's [WorkRates], kept in a small file that is not backed up (speeds belong to the
 * device), read once and written after each Convert job. Seeds come from calibration's decode
 * timings ([Calibrator]) and each share's measured read speed ([ShareSpeeds]).
 */
object ConvertRates {
    @Volatile
    private var loaded: WorkRates? = null

    /** The rates, read from disk the first time. Blocking. */
    fun get(context: Context): WorkRates = loaded ?: synchronized(this) {
        loaded ?: run {
            val text = runCatching { file(context).readText() }.getOrDefault("")
            WorkRates.decode(text, seeds()).also { loaded = it }
        }
    }

    /** How many times longer work takes with the app away ([WorkRates.slowdown]); the default before the rates are read. */
    fun slowdown(): Double = loaded?.slowdown() ?: WorkRates.BACK_SLOWDOWN

    /** Writes what was measured to disk. Blocking. */
    fun save(context: Context) {
        val rates = loaded ?: return
        synchronized(this) {
            val out = file(context)
            val tmp = File(out.path + ".tmp")
            runCatching {
                tmp.writeText(rates.encode())
                if (!tmp.renameTo(out)) tmp.delete()
            }
        }
    }

    /** Where [path] is, as rates tell locations apart: a share, or the device. */
    fun location(path: String): String = Remotes.idOf(path)?.let(WorkRates::share) ?: WorkRates.DEVICE

    /** Seeds from calibration and the shares' measured speeds, read as each figure is asked for. */
    private fun seeds() = WorkRates.Seeds(
        speed = { calibrationSpeed(Calibrator.kept) },
        shareRead = { id -> ShareSpeeds.estimate(id)?.bytesPerSecond?.toDouble() }
    )

    /**
     * How much slower than the reference device this one decoded in the calibration [kept]
     * ([WorkRates.speedOf]); 1 for one made by another calibration version, whose pages differ
     * ([Calibration.sameVersion]).
     */
    internal fun calibrationSpeed(kept: JSONObject?): Double {
        if (!Calibration.sameVersion(kept)) return 1.0
        val timings = kept?.optJSONArray("timings") ?: return 1.0
        var jpeg: Double? = null
        var jxl: Double? = null
        for (i in 0 until timings.length()) {
            val t = timings.optJSONObject(i) ?: continue
            val setup = t.optString("setup")
            when {
                setup.startsWith("jpeg/") -> t.optDouble("wallMs").takeIf { it > 0 }?.let { jpeg = minOf(jpeg ?: it, it) }
                setup.startsWith("jxl/") && !setup.endsWith("/gpu") -> t.optDouble("cpuMs").takeIf { it > 0 }?.let { jxl = minOf(jxl ?: it, it) }
            }
        }
        return WorkRates.speedOf(jpeg, jxl)
    }

    private fun file(context: Context) = File(context.applicationContext.noBackupFilesDir, FILE)

    /** Forgets the rates in memory, the file too; for tests. */
    @VisibleForTesting
    fun reset(context: Context? = null) {
        synchronized(this) {
            loaded = null
            context?.let { file(it).delete() }
        }
    }

    private const val FILE = "convert_rates.txt"
}

/**
 * Feeds one book's timings ([Compactor.Meter]) for action [op] at [location] in [container]
 * to [rates], and keeps what they add up to, so the time the book took beyond them ([book])
 * and the bytes it wrote can be learned. With the app [inFront] each figure learns; with it
 * away, its background-priority workers run slower, and only how much slower than the
 * figures say is learned ([WorkRates.slowdown]).
 */
class BookMeter(
    private val rates: WorkRates,
    private val op: ConvertOp,
    private val location: String,
    private val container: Container,
    private val inFront: () -> Boolean = { Calibrator.inFront }
) : Compactor.Meter {
    private var read = 0.0
    private var convert = 0.0
    private var write = 0.0
    private var written = 0L

    override fun page(bytes: ByteArray, seconds: Double) {
        val s = op.step ?: return
        val kind = ImageKind.sniff(bytes, minOf(bytes.size, ImageKind.HEADER_BYTES))
        val lossy = ConvertEstimate.lossy(kind, bytes)
        if (inFront()) rates.page(s, kind, bytes.size.toLong(), seconds, lossy)
        else rates.away(rates.secondsPerPage(s, kind, SizeClass.of(bytes.size.toLong()), lossy), seconds)
        synchronized(this) { convert += seconds }
    }

    override fun read(bytes: Long, seconds: Double) {
        if (inFront()) rates.read(location, container, bytes, seconds)
        else rates.away(bytes / rates.readRate(location, container), seconds)
        synchronized(this) { read += seconds }
    }

    override fun written(bytes: Long, seconds: Double) {
        if (inFront()) rates.written(location, op.key, bytes, seconds)
        else rates.away(bytes / rates.writeRate(location, op.key), seconds)
        synchronized(this) {
            write += seconds
            written += bytes
        }
    }

    /** Seconds of the book's [wall] time beyond its parts, as [ConvertEstimate] adds them. */
    @Synchronized
    fun beyond(wall: Double): Double = (wall - (maxOf(read, convert) + write)).coerceAtLeast(0.0)

    /** The book, of [bytes], took [wall] seconds: learns the time beyond its parts, and the bytes written per byte of book. */
    fun book(bytes: Long, wall: Double) {
        val extra = beyond(wall)
        if (inFront()) rates.book(op.key, extra) else rates.away(rates.bookSeconds(op.key), extra)
        rates.output(op.key, bytes, synchronized(this) { written })
    }
}
