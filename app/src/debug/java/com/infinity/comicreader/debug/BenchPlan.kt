package com.infinity.comicreader.debug

import android.content.Intent
import android.content.pm.ActivityInfo
import com.infinity.comicreader.reader.CoreSet
import com.infinity.comicreader.reader.JxlPages
import com.infinity.comicreader.reader.NativeResampler
import com.infinity.comicreader.reader.PixelEngine
import org.json.JSONObject

/**
 * One benchmark run ([BenchActivity]): reading sessions in a book ([READ]) or filling a
 * library grid ([GRID]), from the extras the shell starts it with.
 */
internal data class BenchPlan(
    /** [READ] or [GRID]. */
    val what: String,
    /** The book read, or the folder whose grid fills; null for the first book on [share]. */
    val path: String?,
    /** A share id, or [DebugBooks.FIRST]: its first book is read, so no name need be given. */
    val share: String? = null,
    /** A name for the results, so no file name need appear in them. */
    val label: String = "",
    /** [PAGED], [TWO] or [CONTINUOUS]. */
    val layout: String = PAGED,
    /**
     * How pages are turned: [TAP] (the tap zones), [KEY] (a volume key, no gesture wait) or [DRAG]
     * (slow drags of most of a screen); [CONTINUOUS], which has no tap zones, takes [DRAG] for [TAP].
     */
    val input: String = TAP,
    /** The page a book opens at. */
    val page: Int = 0,
    /** Page turns per session. */
    val turns: Int = 10,
    /** Turns go back toward the first page, by tap only; a book opened at [page] has pages behind it. */
    val back: Boolean = false,
    /** Milliseconds between a turn's pages showing sharp and the next turn: time to read ahead. */
    val pace: Long = 1500,
    /** Sessions in a row, each opening the book anew, after [warmup]. */
    val reps: Int = 1,
    /**
     * Sessions run first and marked as warm-up, so those measured find the app's caches of the
     * book warm (sizes, seams, previews); none when [fresh], which forgets them each time.
     */
    val warmup: Int = 1,
    /**
     * First forget what the app keeps of the book (its facts, page previews, sizes, seams, network
     * copy) or, for a grid, every cover: no app caches, but the system's file cache and the
     * process's memory stay warm, so not a cold start.
     */
    val fresh: Boolean = false,
    /** Auto-crop on. */
    val crop: Boolean = false,
    /** Double-tap to zoom: on, off, or null to keep the setting. */
    val doubleTap: Boolean? = null,
    /** How long a tap is held, in ms: about as long as a person's. */
    val tapMs: Long = 100,
    /** The orientation every activity of the run is turned to ([ORIENTATIONS]): portrait unless asked, so sizes repeat. */
    val orientation: Int = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
    /** The engine pages are resampled with ([NativeResampler.engine]) for the run; null keeps it. */
    val engine: PixelEngine? = null,
    /** CPU workers of the native resampler ([NativeResampler.threads]) for the run; null keeps them. */
    val pixelThreads: Int? = null,
    /** Its workers for pages read ahead ([NativeResampler.laterThreads]); null keeps them. */
    val laterThreads: Int? = null,
    /** Whether its workers are pinned to large or little cores ([NativeResampler.pinned]); null keeps it. */
    val pinned: Boolean? = null,
    /** JPEG XL pages decoded natively ([JxlPages.enabled]) or by jxl-coder; null keeps it. */
    val jxlNative: Boolean? = null,
    /** Native JPEG XL decode threads for pages read ahead ([JxlPages.laterThreads]); null keeps them. */
    val jxlLaterThreads: Int? = null,
    /** Whether those threads are pinned to the little cores ([JxlPages.laterOnLittle]); null keeps it. */
    val jxlLaterOnLittle: Boolean? = null,
    /** Their Linux nice ([JxlPages.laterNice]); null keeps it. */
    val jxlLaterNice: Int? = null,
    /** The cores platform decodes run on ([com.infinity.comicreader.reader.PlatformPages.cores]); null keeps them. */
    val platformCores: CoreSet? = null,
    /** Whether pages read ahead are pinned too ([com.infinity.comicreader.reader.PlatformPages.readAhead]); null keeps it. */
    val platformAhead: Boolean? = null,
    /** Pages decoded at once ([com.infinity.comicreader.ui.PageAdapter.decodeThreads]); null keeps it. */
    val decodeThreads: Int? = null,
    /** Pages read ahead, the setting (0-6); null keeps it. */
    val preload: Int? = null,
    /** Spreads joined by their seams, the setting, which runs the seam scans; null keeps it. */
    val seams: Boolean? = null,
    /** The screen behind prepared ([com.infinity.comicreader.ui.PageAdapter.screenBehind]); null keeps it. */
    val behind: Boolean? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("what", what).put("label", label).put("share", share != null).put("layout", layout).put("input", input).put("page", page)
        .put("turns", turns).put("back", back).put("pace", pace).put("warmup", warmup).put("fresh", fresh).put("crop", crop)
        .put("doubleTap", doubleTap ?: JSONObject.NULL).put("tapMs", tapMs)
        .put("orientation", ORIENTATIONS.entries.first { it.value == orientation }.key)
        .put("engine", engine?.key ?: JSONObject.NULL).put("pixelThreads", pixelThreads ?: JSONObject.NULL)
        .put("laterThreads", laterThreads ?: JSONObject.NULL).put("pinned", pinned ?: JSONObject.NULL)
        .put("jxl", jxlNative?.let { if (it) "native" else "coder" } ?: JSONObject.NULL)
        .put("jxlLaterThreads", jxlLaterThreads ?: JSONObject.NULL).put("jxlLaterOnLittle", jxlLaterOnLittle ?: JSONObject.NULL)
        .put("jxlLaterNice", jxlLaterNice ?: JSONObject.NULL)
        .put("platformCores", platformCores?.key ?: JSONObject.NULL).put("decodeThreads", decodeThreads ?: JSONObject.NULL)
        .put("platformAhead", platformAhead ?: JSONObject.NULL)
        .put("preload", preload ?: JSONObject.NULL).put("seams", seams ?: JSONObject.NULL)
        .put("behind", behind ?: JSONObject.NULL)

    companion object {
        const val READ = "read"
        const val GRID = "grid"
        const val PAGED = "paged"
        const val TWO = "two"
        const val CONTINUOUS = "continuous"
        const val TAP = "tap"
        const val KEY = "key"
        const val DRAG = "drag"

        /** Orientations by name: `portrait`, `landscape`, or `any` (as the device is held). */
        val ORIENTATIONS = mapOf(
            "portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            "landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            "any" to ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        )

        /** The plan [intent]'s extras give; null when they name nothing to do. */
        fun of(intent: Intent): BenchPlan? {
            val what = intent.getStringExtra("do") ?: return null
            val path = intent.getStringExtra("path")
            val share = intent.getStringExtra("share")
            if (path == null && (share == null || what != READ)) return null
            if (what != READ && what != GRID) return null
            val layout = intent.getStringExtra("layout") ?: PAGED
            if (layout !in setOf(PAGED, TWO, CONTINUOUS)) return null
            val asked = intent.getStringExtra("input") ?: TAP
            if (asked !in setOf(TAP, KEY, DRAG)) return null
            val input = if (asked == TAP && layout == CONTINUOUS) DRAG else asked
            val orientation = ORIENTATIONS[intent.getStringExtra("orientation") ?: "portrait"] ?: return null
            val fresh = intent.getBooleanExtra("fresh", false)
            val engine = intent.getStringExtra("engine")?.let { PixelEngine.of(it) ?: return null }
            val jxl = intent.getStringExtra("jxl")?.let { if (it == "native" || it == "coder") it == "native" else return null }
            val platformCores = intent.getStringExtra("platformcores")?.let { CoreSet.of(it) ?: return null }
            return BenchPlan(
                what = what,
                path = path,
                share = share,
                label = intent.getStringExtra("label").orEmpty(),
                layout = layout,
                input = input,
                page = intent.getIntExtra("page", 0).coerceAtLeast(0),
                turns = intent.getIntExtra("turns", 10).coerceIn(0, 500),
                back = intent.getBooleanExtra("back", false),
                pace = intent.getIntExtra("pace", 1500).coerceAtLeast(0).toLong(),
                reps = intent.getIntExtra("reps", 1).coerceIn(1, 50),
                warmup = if (fresh) 0 else intent.getIntExtra("warmup", 1).coerceIn(0, 10),
                fresh = fresh,
                crop = intent.getBooleanExtra("crop", false),
                doubleTap = intent.getStringExtra("doubletap")?.let { it == "on" },
                tapMs = intent.getIntExtra("tapms", 100).coerceIn(1, 1_000).toLong(),
                orientation = orientation,
                engine = engine,
                pixelThreads = if (intent.hasExtra("pixelthreads")) intent.getIntExtra("pixelthreads", 4).coerceIn(1, 16) else null,
                laterThreads = if (intent.hasExtra("laterthreads")) intent.getIntExtra("laterthreads", 1).coerceIn(1, 16) else null,
                pinned = intent.getStringExtra("pinned")?.let { it == "on" },
                jxlNative = jxl,
                jxlLaterThreads = if (intent.hasExtra("jxllaterthreads")) intent.getIntExtra("jxllaterthreads", 4).coerceIn(1, 16) else null,
                jxlLaterOnLittle = intent.getStringExtra("jxllater")?.let { if (it == "little" || it == "big") it == "little" else return null },
                jxlLaterNice = if (intent.hasExtra("jxllaternice")) intent.getIntExtra("jxllaternice", 0).coerceIn(0, 19) else null,
                platformCores = platformCores,
                platformAhead = intent.getStringExtra("platformahead")?.let { if (it == "on" || it == "off") it == "on" else return null },
                decodeThreads = if (intent.hasExtra("decoders")) intent.getIntExtra("decoders", 2).coerceIn(1, 8) else null,
                preload = if (intent.hasExtra("preload")) intent.getIntExtra("preload", 2).coerceIn(0, 6) else null,
                seams = intent.getStringExtra("seams")?.let { if (it == "on" || it == "off") it == "on" else return null },
                behind = intent.getStringExtra("behind")?.let { if (it == "on" || it == "off") it == "on" else return null }
            )
        }
    }
}
