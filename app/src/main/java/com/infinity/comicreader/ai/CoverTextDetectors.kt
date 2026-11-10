package com.infinity.comicreader.ai

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import androidx.annotation.VisibleForTesting
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.SmartCrop
import com.infinity.comicreader.net.NetPolicy
import com.infinity.comicreader.net.NetWork

/**
 * Picks the text detector ([CoverTextDetector]) the Smart crop anchor finds cover text with:
 * Google's ML Kit when the user turned it on ([optedIn], off by default), Play services is there
 * ([play]) and ML Kit has not failed this session; PP-OCR ([ppocr]) otherwise. Covers of books
 * marked right to left always use PP-OCR ([forBook]): ML Kit's recognizer reads Latin script only.
 *
 * The pick holds for the session, so no cover mixes detectors: it changes when the setting does,
 * and once, for the rest of the session, when ML Kit fails. ML Kit's model is a module Play
 * services downloads: asked about on the first cover, and requested as optional network work
 * ([mayDownload], [NetWork.DOWNLOAD]). While it downloads, ML Kit does not run (a focus found
 * meanwhile is not kept). A request the network policy holds back or Play services refuses, or
 * [FAILURES] failed runs in a row, leave PP-OCR for the rest of the session.
 */
class CoverTextDetectors(
    private val optedIn: () -> Boolean,
    private val play: PlayServices,
    /** ML Kit's detector; null where the app has none. */
    private val mlKit: (() -> CoverTextDetector)?,
    private val ppocr: () -> CoverTextDetector,
    private val mayDownload: () -> Boolean = { NetPolicy.may(NetWork.DOWNLOAD) }
) {
    /** A detector covers find text with. */
    enum class Use { ML_KIT, PP_OCR }

    /** Why covers do not use ML Kit. */
    enum class Why {
        /** The setting is off. */
        OFF,

        /** Play services is not on the device, or ML Kit is not part of the app. */
        UNAVAILABLE,

        /** The module download waits for a connection the network policy allows. */
        HELD_BACK,

        /** Play services could not tell about, or download, the module. */
        MODULE_FAILED,

        /** ML Kit failed [FAILURES] times in a row. */
        FAILED
    }

    /** The detector covers use, [why] not ML Kit (null when it is), and whether ML Kit's module is [downloading]. */
    data class Status(val use: Use, val why: Why?, val downloading: Boolean = false)

    private enum class Module { UNKNOWN, DOWNLOADING, READY }

    private val lock = Any()

    /** The setting as last seen: turning it on gives ML Kit a new try. */
    private var wasOn = false

    /** Why ML Kit was given up this session; null while it was not. */
    private var gaveUp: Why? = null
    private var module = Module.UNKNOWN
    private var failures = 0

    /** Which detector covers use now and why. Quick: fine on the main thread. */
    fun status(): Status = synchronized(lock) {
        val on = optedIn()
        if (on && !wasOn) {
            gaveUp = null
            failures = 0
        }
        wasOn = on
        when {
            !on -> Status(Use.PP_OCR, Why.OFF)
            mlKit == null || !play.available() -> Status(Use.PP_OCR, Why.UNAVAILABLE)
            gaveUp != null -> Status(Use.PP_OCR, gaveUp)
            else -> Status(Use.ML_KIT, null, module == Module.DOWNLOADING)
        }
    }

    /** True where the setting can turn ML Kit on: Play services is there. */
    fun offered(): Boolean = play.available()

    /** The detector of covers of books not marked right to left, and of a book's direction: the session's pick. */
    fun session(): CoverTextDetector = if (status().use == Use.ML_KIT) gate else ppocr()

    /** The detector of covers of books marked right to left: PP-OCR. */
    fun rightToLeft(): CoverTextDetector = ppocr()

    /** The detector of a book's cover; [rightToLeft] tells whether it is marked so, asked only while ML Kit is picked. */
    fun forBook(rightToLeft: () -> Boolean): CoverTextDetector = if (status().use == Use.ML_KIT && !rightToLeft()) gate else ppocr()

    private val ml: CoverTextDetector by lazy { mlKit!!.invoke() }

    /** ML Kit's detector once its module is on the device; counts its failures. */
    private val gate = object : CoverTextDetector {
        override val id: String get() = ml.id

        override fun lines(src: Bitmap): List<SmartCrop.TextBox>? {
            if (!ready()) return null
            val found = runCatching { ml.lines(src) }.getOrNull()
            synchronized(lock) {
                if (found != null) failures = 0
                else if (++failures >= FAILURES) giveUp(Why.FAILED)
            }
            return found
        }
    }

    /**
     * True when ML Kit may run now: its module is on the device. Asks Play services once; a
     * module missing is requested when the network policy allows, else ML Kit is given up.
     */
    private fun ready(): Boolean {
        synchronized(lock) {
            if (gaveUp != null || !optedIn()) return false
            when (module) {
                Module.READY -> return true
                Module.DOWNLOADING -> return false
                Module.UNKNOWN -> Unit
            }
        }
        val installed = play.moduleInstalled()
        synchronized(lock) {
            if (module != Module.UNKNOWN || gaveUp != null) return module == Module.READY && gaveUp == null
            when {
                installed == true -> module = Module.READY
                installed == null -> giveUp(Why.MODULE_FAILED)
                !mayDownload() -> giveUp(Why.HELD_BACK)
                else -> module = Module.DOWNLOADING
            }
            if (module != Module.DOWNLOADING) return module == Module.READY && gaveUp == null
        }
        play.requestModule { ok ->
            synchronized(lock) {
                if (ok) module = Module.READY
                else {
                    module = Module.UNKNOWN
                    giveUp(Why.MODULE_FAILED)
                }
            }
        }
        return false
    }

    /** Leaves ML Kit for [why] for the rest of the session, or until the setting is turned on again. Under [lock]. */
    private fun giveUp(why: Why) {
        if (gaveUp == null) gaveUp = why
    }

    companion object {
        /** Failed ML Kit runs in a row that leave PP-OCR for the rest of the session. */
        const val FAILURES = 3

        @Volatile
        private var shared: CoverTextDetectors? = null

        /**
         * The app's: the setting from [Prefs.coverMlKit], Play services as [GmsPlayServices] finds
         * it (absent while the debug flag [Prefs.debugNoPlayServices] is on in a debuggable build),
         * PP-OCR as [TextDetector.shared].
         */
        fun shared(context: Context): CoverTextDetectors = shared ?: synchronized(this) {
            shared ?: run {
                val app = context.applicationContext
                val prefs = Prefs(app)
                val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                CoverTextDetectors(
                    optedIn = { prefs.coverMlKit },
                    play = WithoutPlayServices(GmsPlayServices(app)) { debuggable && prefs.debugNoPlayServices },
                    // None until ML Kit's detector is part of the app.
                    mlKit = null,
                    ppocr = { TextDetector.shared(app) }
                ).also { shared = it }
            }
        }

        /** Stands [detectors] in as the app's ([shared]); null makes it anew. */
        @VisibleForTesting
        fun standIn(detectors: CoverTextDetectors?) {
            shared = detectors
        }
    }
}
