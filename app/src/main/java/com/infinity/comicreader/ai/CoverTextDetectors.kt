package com.infinity.comicreader.ai

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.preference.PreferenceManager
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.SmartCrop
import com.infinity.comicreader.net.NetPolicy
import com.infinity.comicreader.net.NetWork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * Picks the text detector ([CoverTextDetector]) the Smart crop anchor finds cover text with:
 * Google's ML Kit when the user turned it on ([optedIn], off by default), Play services is there
 * ([play]) and ML Kit has not failed this session; PP-OCR ([ppocr]) otherwise. Covers of books
 * marked right to left always use PP-OCR ([rightToLeft]): ML Kit's recognizer reads Latin script only.
 *
 * The pick holds for the session, so no cover mixes detectors: it changes when the setting does,
 * when a held-back download is allowed, and once, for the rest of the session, when ML Kit fails.
 * ML Kit's model is a module Play services downloads: asked about once, by the first cover
 * (covers asking meanwhile wait for that answer), and requested as optional network work
 * ([mayDownload], [NetWork.DOWNLOAD]).
 * While it downloads, ML Kit does not run (a focus found meanwhile is not kept); the module is
 * asked about again every [RECHECK_MS], and a download not done within [DOWNLOAD_WAIT_MS] counts
 * as failed. A request the network policy holds back leaves PP-OCR until the policy allows it,
 * asked again at most every [DetectorModel.HELD_MS]. A request Play services refuses,
 * [CHECK_TRIES] checks Play services does not answer, ML Kit not starting, or [FAILURES] failed
 * runs in a row leave PP-OCR for the rest of the session, or until the setting is turned on
 * again ([settingChanged]).
 */
class CoverTextDetectors(
    private val optedIn: () -> Boolean,
    private val play: PlayServices,
    /** ML Kit's detector; null where the app has none. */
    private val mlKit: MlKit?,
    private val ppocr: () -> CoverTextDetector,
    private val mayDownload: () -> Boolean = { NetPolicy.may(NetWork.DOWNLOAD) },
    /** Milliseconds on a monotonic clock. */
    private val now: () -> Long = { SystemClock.elapsedRealtime() }
) {
    /** ML Kit's detector: its [id], known without making it, and how to [make] it, which happens only once its module is on the device. */
    class MlKit(val id: String, val make: () -> CoverTextDetector)

    /** A detector covers find text with. */
    enum class Use { ML_KIT, PP_OCR }

    /** Why covers do not use ML Kit. */
    enum class Why {
        /** The setting is off. */
        OFF,

        /** Play services is not on the device, or ML Kit is not part of the app. */
        UNAVAILABLE,

        /** The network policy holds the module download back; asked again after [DetectorModel.HELD_MS]. */
        HELD_BACK,

        /** Play services could not tell about, or download, the module. */
        MODULE_FAILED,

        /** ML Kit did not start, or failed [FAILURES] times in a row. */
        FAILED
    }

    /** The detector covers use, [why] not ML Kit (null when it is), and whether ML Kit's module is [downloading]. */
    data class Status(val use: Use, val why: Why?, val downloading: Boolean = false)

    private enum class Module { UNKNOWN, DOWNLOADING, READY }

    private val lock = Any()

    /** Why ML Kit was given up this session; null while it was not. */
    private var gaveUp: Why? = null
    private var module = Module.UNKNOWN
    private var failures = 0

    /** Checks of the module Play services did not answer. */
    private var misses = 0

    /** The check of the module under way, which every cover asking meanwhile waits for. */
    private var check: FutureTask<Boolean>? = null

    /** When the module was last asked about, and when its download was requested ([now]). */
    private var askedAt = 0L
    private var requestedAt = 0L

    /** When the network policy last held the module download back ([now]). */
    private var heldAt = 0L

    /** What detaches the app from the download request under way, and its number, so an answer to an earlier one is ignored. */
    private var detach: (() -> Unit)? = null
    private var request = 0

    /** A cover went without ML Kit's text since its module was last known to be on the device. Under [lock]. */
    private var servedWithout = false

    /**
     * Times covers made without a detector's text may now find it: ML Kit's module or PP-OCR's
     * model ([modelArrived]) arrived after covers went without it, or the setting gave ML Kit a
     * new try after it was given up.
     */
    @Volatile
    var arrivals = 0
        private set

    private val changed = MutableStateFlow(0)

    /** Counts changes that make covers on screen out of date: [arrivals], ML Kit given up, and a hold lifted. */
    val changes: StateFlow<Int> = changed.asStateFlow()

    @Volatile
    private var made: CoverTextDetector? = null
    private val making = Any()

    /** Which detector covers use now and why. Quick: fine on the main thread. */
    fun status(): Status {
        val on = optedIn()
        var stop: (() -> Unit)? = null
        val status = synchronized(lock) {
            if (on) stop = expire()
            when {
                !on -> Status(Use.PP_OCR, Why.OFF)
                mlKit == null || !play.available() -> Status(Use.PP_OCR, Why.UNAVAILABLE)
                gaveUp != null -> Status(Use.PP_OCR, gaveUp)
                else -> Status(Use.ML_KIT, null, module == Module.DOWNLOADING)
            }
        }
        stop?.invoke()
        return status
    }

    /** True where the setting can turn ML Kit on: ML Kit is part of the app and Play services is there. */
    fun offered(): Boolean = mlKit != null && play.available()

    /** The detector of covers of books not marked right to left: the session's pick. */
    fun session(): CoverTextDetector = if (status().use == Use.ML_KIT) gate else ppocr()

    /** The detector of covers of books marked right to left: PP-OCR. */
    fun rightToLeft(): CoverTextDetector = ppocr()

    /**
     * Gives ML Kit a new try when the setting is on: what was given up this session is forgotten,
     * and a download under way gets a new [DOWNLOAD_WAIT_MS]. Turned off, the app detaches from a
     * download request under way and releases the module ([PlayServices.releaseModule]); a request
     * still being sent is released by [asked]. The app's ([shared]) hears of every change of the setting.
     */
    fun settingChanged() {
        if (!optedIn()) {
            val stop = synchronized(lock) {
                if (module != Module.DOWNLOADING) return
                module = Module.UNKNOWN
                request++
                detach.also { detach = null }
            } ?: return
            stop()
            release()
            return
        }
        synchronized(lock) {
            // Covers made with ML Kit before it was given up went without its text.
            if (gaveUp != null) arrive()
            gaveUp = null
            failures = 0
            misses = 0
            if (module == Module.DOWNLOADING) requestedAt = now()
        }
    }

    /** ML Kit's detector once its module is on the device; counts its failures. */
    private val gate = object : CoverTextDetector {
        override val id: String get() = mlKit!!.id

        override fun inputSize(w: Int, h: Int): IntArray = (if (moduleReady()) made() else null)?.inputSize(w, h) ?: SmartCrop.inputSize(w, h)

        override fun lines(src: Bitmap): List<SmartCrop.TextBox>? {
            if (!moduleReady()) {
                synchronized(lock) { servedWithout = true }
                return null
            }
            val ml = made() ?: return null
            val found = try {
                ml.lines(src)
            } catch (e: InterruptedException) {
                // Stopped from outside: no failure of ML Kit's.
                Thread.currentThread().interrupt()
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            synchronized(lock) {
                if (found != null) failures = 0
                else if (++failures >= FAILURES) giveUp(Why.FAILED)
            }
            return found
        }
    }

    /** ML Kit's detector, made on first use; null, and ML Kit given up, when it does not start. */
    private fun made(): CoverTextDetector? {
        made?.let { return it }
        synchronized(making) {
            made?.let { return it }
            return try {
                mlKit!!.make().also { made = it }
            } catch (e: Exception) {
                gaveUpMaking()
            } catch (e: LinkageError) {
                gaveUpMaking()
            }
        }
    }

    /** Gives ML Kit up as not starting ([Why.FAILED]); null for [made]. */
    private fun gaveUpMaking(): CoverTextDetector? {
        synchronized(lock) { giveUp(Why.FAILED) }
        return null
    }

    /**
     * True when ML Kit may run now: its module is on the device. The first cover asks Play
     * services ([PlayServices.moduleInstalled]), covers asking meanwhile wait for its answer
     * ([asked]); while the module downloads, it is asked about again every [RECHECK_MS].
     */
    private fun moduleReady(): Boolean {
        var mine = false
        val stop: (() -> Unit)?
        val task: FutureTask<Boolean>? = synchronized(lock) {
            if (!optedIn()) return false
            stop = expire()
            when {
                gaveUp != null -> null
                module == Module.READY -> return true
                module == Module.DOWNLOADING && check == null && now() - askedAt < RECHECK_MS -> null
                else -> check ?: newCheck().also {
                    check = it
                    askedAt = now()
                    mine = true
                }
            }
        }
        stop?.invoke()
        if (task == null) return false
        if (mine) task.run()
        return try {
            task.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (e: ExecutionException) {
            false
        }
    }

    /** A check of the module ([asked]); dropped as [check] when it ends, even by an error. */
    private fun newCheck(): FutureTask<Boolean> {
        lateinit var task: FutureTask<Boolean>
        task = FutureTask {
            try {
                asked(installed())
            } finally {
                synchronized(lock) { if (check === task) check = null }
            }
        }
        return task
    }

    /**
     * Settles what Play services told of the module ([PlayServices.moduleInstalled]): true when
     * ML Kit may run now. A module missing is requested when the network policy allows, else ML
     * Kit is given up; never once the setting is off. Turned off while it is being requested, the
     * app detaches from the request and releases the module.
     */
    private fun asked(installed: Boolean?): Boolean {
        val interrupted = Thread.currentThread().isInterrupted
        var number = 0
        var stop: (() -> Unit)? = null
        synchronized(lock) {
            check = null
            when {
                gaveUp != null -> return false
                module == Module.READY -> return true
                installed == true -> stop = arrived()
                module == Module.DOWNLOADING -> return false
                // Turned off during the check: nothing is requested.
                !optedIn() -> return false
                // An interrupted wait tells nothing of Play services: the next cover asks again.
                installed == null && interrupted -> return false
                installed == null -> {
                    if (++misses >= CHECK_TRIES) giveUp(Why.MODULE_FAILED)
                    return false
                }
                !mayDownload() -> {
                    heldAt = now()
                    giveUp(Why.HELD_BACK)
                    return false
                }
                else -> {
                    module = Module.DOWNLOADING
                    requestedAt = now()
                    number = ++request
                }
            }
        }
        if (number == 0) {
            // The module arrived while it downloaded: the request is done with.
            stop?.invoke()
            return true
        }
        synchronized(lock) {
            // Settled or turned off since: nothing is requested.
            if (request != number || module != Module.DOWNLOADING) return gaveUp == null && module == Module.READY
            if (!optedIn()) {
                module = Module.UNKNOWN
                request++
                return false
            }
        }
        val requested = try {
            play.requestModule { ok -> requested(number, ok) }
        } catch (e: Exception) {
            requested(number, false)
            return false
        } catch (e: LinkageError) {
            requested(number, false)
            return false
        }
        synchronized(lock) {
            if (request == number && module == Module.DOWNLOADING) {
                if (optedIn()) {
                    detach = requested
                    return false
                }
                // Turned off while it was being requested.
                module = Module.UNKNOWN
                request++
            }
        }
        // Settled, given up or turned off while it was being requested.
        requested()
        if (!optedIn()) release()
        return synchronized(lock) { gaveUp == null && module == Module.READY }
    }

    /** Tells Play services the app no longer needs the module ([PlayServices.releaseModule]). */
    private fun release() {
        try {
            play.releaseModule()
        } catch (e: Exception) {
            // Play services keeps the module until it drops it on its own.
        } catch (e: LinkageError) {
            // ML Kit's classes are missing: there is nothing to release.
        }
    }

    /**
     * What Play services tells of the module ([PlayServices.moduleInstalled]); null when it fails
     * to. ML Kit missing its classes is given up ([Why.FAILED]).
     */
    private fun installed(): Boolean? = try {
        play.moduleInstalled()
    } catch (e: Exception) {
        null
    } catch (e: LinkageError) {
        synchronized(lock) { giveUp(Why.FAILED) }
        null
    }

    /** Settles download request [number]: the module arrived when [ok], else ML Kit is given up. */
    private fun requested(number: Int, ok: Boolean) = synchronized(lock) {
        if (number != request || module != Module.DOWNLOADING) return@synchronized
        detach = null
        if (ok) arrived()
        else {
            module = Module.UNKNOWN
            giveUp(Why.MODULE_FAILED)
        }
    }

    /** The module is on the device: ML Kit runs from now on. Returns what detaches a request still under way. Under [lock]. */
    private fun arrived(): (() -> Unit)? {
        if (module == Module.DOWNLOADING || servedWithout) arrive()
        module = Module.READY
        request++
        return detach.also { detach = null }
    }

    /** PP-OCR's model arrived ([DetectorModel]) after covers went without it: they find their text now. */
    fun modelArrived() = synchronized(lock) {
        arrivals++
        changed.update { it + 1 }
    }

    /** Counts an arrival ([arrivals]). Under [lock]. */
    private fun arrive() {
        arrivals++
        servedWithout = false
        changed.update { it + 1 }
    }

    /**
     * Lifts a hold ([Why.HELD_BACK]) at least [DetectorModel.HELD_MS] old once the network policy
     * allows the download, so the next cover asks again and covers on screen are made again under
     * ML Kit ([changes]); gives a download not done within [DOWNLOAD_WAIT_MS] up
     * ([Why.MODULE_FAILED]) and returns what detaches it. Under [lock].
     */
    private fun expire(): (() -> Unit)? {
        if (gaveUp == Why.HELD_BACK && now() - heldAt >= DetectorModel.HELD_MS) {
            if (mayDownload()) {
                gaveUp = null
                changed.update { it + 1 }
            } else heldAt = now()
        }
        if (module != Module.DOWNLOADING || gaveUp != null || now() - requestedAt < DOWNLOAD_WAIT_MS) return null
        module = Module.UNKNOWN
        giveUp(Why.MODULE_FAILED)
        request++
        return detach.also { detach = null }
    }

    /** Leaves ML Kit for [why] for the rest of the session, or until the setting is turned on again or [expire] lifts a hold. Under [lock]. */
    private fun giveUp(why: Why) {
        if (gaveUp != null) return
        gaveUp = why
        changed.update { it + 1 }
    }

    companion object {
        /** ML Kit's detector's [CoverTextDetector.id]: part of the cover-focus version of covers it finds text on. */
        const val ML_KIT_ID = MlKitTextDetector.ID

        /** Failed ML Kit runs in a row that leave PP-OCR for the rest of the session. */
        const val FAILURES = 3

        /** Checks of the module Play services does not answer that leave PP-OCR for the rest of the session. */
        const val CHECK_TRIES = 2

        /** How often a module being downloaded is asked about again. */
        const val RECHECK_MS = 30_000L

        /** How long a module download may take before PP-OCR is used for the rest of the session. */
        const val DOWNLOAD_WAIT_MS = 10 * 60_000L

        @Volatile
        private var shared: CoverTextDetectors? = null

        /** Tells [shared] of changes of the setting; held here, as preferences hold their listeners weakly. */
        private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

        /** The app's ([forApp]), told of changes of the setting. */
        fun shared(context: Context): CoverTextDetectors = shared ?: synchronized(this) {
            shared ?: run {
                val app = context.applicationContext
                forApp(app).also { d ->
                    val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == Prefs.KEY_COVER_MLKIT) d.settingChanged() }
                    PreferenceManager.getDefaultSharedPreferences(app).registerOnSharedPreferenceChangeListener(l)
                    listener = l
                    shared = d
                }
            }
        }

        /**
         * The app's detectors: the setting from [Prefs.coverMlKit], [play] (Play services as
         * [GmsPlayServices] finds it on devices; absent while the debug flag
         * [Prefs.debugNoPlayServices] is on in a debuggable build), ML Kit as [MlKitTextDetector],
         * PP-OCR as [TextDetector.shared].
         */
        @VisibleForTesting
        internal fun forApp(app: Context, play: PlayServices = GmsPlayServices(app)): CoverTextDetectors {
            val prefs = Prefs(app)
            val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            return CoverTextDetectors(
                optedIn = { prefs.coverMlKit },
                play = WithoutPlayServices(play) { debuggable && prefs.debugNoPlayServices },
                mlKit = MlKit(ML_KIT_ID) { MlKitTextDetector.create(app) },
                ppocr = { TextDetector.shared(app) }
            )
        }

        /** Stands [detectors] in as the app's ([shared]); null makes it anew. */
        @VisibleForTesting
        fun standIn(detectors: CoverTextDetectors?) {
            shared = detectors
        }
    }
}
