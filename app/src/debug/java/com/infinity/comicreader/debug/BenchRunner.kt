package com.infinity.comicreader.debug

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.ReaderLayout
import com.infinity.comicreader.data.Thumbs
import com.infinity.comicreader.data.TwoPage
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.reader.PageSeams
import com.infinity.comicreader.reader.PageSizes
import com.infinity.comicreader.reader.PreviewStore
import com.infinity.comicreader.reader.TapZones
import com.infinity.comicreader.ui.ListActivity
import com.infinity.comicreader.ui.ViewerActivity
import com.infinity.comicreader.util.ReadProbes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * Runs a [BenchPlan] in the app's own process, on the main thread, driving the real
 * reader or library as a person would (taps, volume keys, drags) and timing what shows,
 * frame by frame ([BenchProbe], [Milestones]). Times are from the start of an open or a
 * turn to the first frame that finds the pages on screen visible or sharp, so up to one
 * frame late. Each session is a JSON object ([run]).
 */
internal class BenchRunner(private val app: Application) {
    private val probe = BenchProbe()
    private val frames = FrameStats()
    private val tracker = Tracker()
    private val metricsThread = HandlerThread("bench-frames").apply { start() }
    private val metricsHandler = Handler(metricsThread.looper)
    private var watched: Window? = null

    private val metrics = Window.OnFrameMetricsAvailableListener { window, m, _ ->
        if (m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
        val deadline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) m.getMetric(FrameMetrics.DEADLINE) else frameNs(window)
        frames.add(m.getMetric(FrameMetrics.TOTAL_DURATION), deadline)
    }

    /** Runs [plan] from [host], the activity it was started in; one result per session. */
    suspend fun run(host: Activity, plan: BenchPlan): List<JSONObject> {
        tracker.orientation = plan.orientation
        app.registerActivityLifecycleCallbacks(tracker)
        tracker.resumed = host
        ReadProbes.current = probe
        val kept = PrefsKept(Prefs(app))
        try {
            return when (plan.what) {
                BenchPlan.GRID -> listOf(grid(plan))
                else -> {
                    kept.apply(plan)
                    (1..plan.reps).map { read(plan, it) }
                }
            }
        } finally {
            kept.restore()
            ReadProbes.current = null
            probe.clear()
            unwatch()
            metricsThread.quitSafely()
            app.unregisterActivityLifecycleCallbacks(tracker)
        }
    }

    // ------------------------------------------------------------------ reading

    private suspend fun read(plan: BenchPlan, rep: Int): JSONObject {
        val path = plan.path ?: withContext(Dispatchers.IO) {
            DebugBooks.shareId(plan.share)?.let { DebugBooks.firstBook(Remotes.rootOf(it), SHARE_DEPTH, largest = false) }
        } ?: error("No book found on the share")
        if (plan.fresh) forgetBook()
        probe.clear()
        idle()
        val before = ProcStats.now(app)
        val start = System.nanoTime()
        val host = tracker.resumed ?: error("No activity to start the reader from")
        host.startActivity(
            Intent(host, ViewerActivity::class.java)
                .putExtra(ViewerActivity.EXTRA_PATH, path)
                .putExtra(ViewerActivity.EXTRA_PAGE, plan.page)
        )
        val viewer = awaitResumed<ViewerActivity>(OPEN_TIMEOUT_MS)
        watch(viewer.window)
        val open = Milestones(start)
        val openOk = framesUntil(OPEN_TIMEOUT_MS) { now -> open.frame(now, probe.pages.ready(probe.pageViewsOnScreen())) }
        val openFrames = frames.toJson()
        val afterOpen = ProcStats.now(app)
        val cpuOpen = afterOpen.cpuMs - before.cpuMs

        frames.reset()
        val turns = JSONArray()
        val visible = ArrayList<Long>()
        val sharp = ArrayList<Long>()
        val moved = ArrayList<Long>()
        var cached = 0
        val turnStart = ProcStats.now(app, memory = false)
        val turnsFrom = System.nanoTime()
        var tapX = TAP_X
        for (i in 1..plan.turns) {
            delay(plan.pace)
            val turn = turn(viewer, plan, tapX) ?: break
            turns.put(turn)
            if (turn.getBoolean("back")) {
                // A right-to-left book: the other side turns forward; this turn back is left out.
                tapX = 1f - tapX
                continue
            }
            visible += turn.getLong("visibleMs")
            sharp += turn.getLong("sharpMs")
            if (turn.has("movedMs")) moved += turn.getLong("movedMs")
            if (turn.getBoolean("ready")) cached++
            if (turn.getBoolean("timedOut")) break
        }
        val turnsWall = System.nanoTime() - turnsFrom
        val turnEnd = ProcStats.now(app)
        val turnFrames = frames.toJson()
        unwatch()
        viewer.finish()
        awaitDestroyed(viewer)

        return JSONObject()
            .put("kind", "read").put("rep", rep).put("plan", plan.toJson()).put("screen", screen(viewer))
            .put("openVisibleMs", Stats.ms(open.visible)).put("openSharpMs", Stats.ms(open.sharp)).put("openTimedOut", !openOk)
            .put("openStartMs", if (probe.opening >= 0) Stats.ms(probe.opening - start) else -1)
            .put("openCpuMs", cpuOpen)
            .put("openFrames", openFrames)
            .put("turnVisible", Stats.summary(visible)).put("turnSharp", Stats.summary(sharp)).put("turnMoved", Stats.summary(moved))
            .put("turnsReady", cached).put("turnCount", turns.length())
            .put("turnCpuMs", turnEnd.cpuMs - turnStart.cpuMs).put("turnWallMs", Stats.ms(turnsWall))
            .put("turnFrames", turnFrames)
            .put("before", before.toJson()).put("afterOpen", afterOpen.toJson()).put("afterTurns", turnEnd.toJson())
            .put("turns", turns)
    }

    /**
     * One turn forward in [viewer] as [plan] says, timed from the input's end (a drag's
     * start) until the pages then on screen are visible and sharp; null when nothing moved.
     */
    private suspend fun turn(viewer: ViewerActivity, plan: BenchPlan, tapX: Float): JSONObject? {
        val before = probe.pages.pages(probe.pageViewsOnScreen())
        var movedAt = -1L
        probe.onMoved = { if (movedAt < 0) movedAt = it }
        val start = when (plan.input) {
            BenchPlan.DRAG -> drag(viewer).also { repeat(DRAGS - 1) { drag(viewer) } }
            BenchPlan.KEY -> key(viewer)
            else -> tap(viewer, tapX, TAP_Y)
        }
        val m = Milestones(start)
        var first: PageWatch.Ready? = null
        var shownPages = before
        // Pages on screen count once they are new: until then the last turn's still show.
        val ok = framesUntil(TURN_TIMEOUT_MS) { now ->
            val views = probe.pageViewsOnScreen()
            val pages = probe.pages.pages(views)
            if (pages == before || pages.isEmpty()) {
                now - start > NO_MOVE_MS * 1_000_000 && (movedAt < 0 || plan.layout == BenchPlan.CONTINUOUS)
            } else {
                shownPages = pages
                val ready = probe.pages.ready(views)
                if (first == null) first = ready
                m.frame(now, ready)
            }
        }
        probe.onMoved = null
        if (movedAt >= 0) m.moved(movedAt)
        if (shownPages == before) return null
        return JSONObject()
            .put("pages", JSONArray(shownPages))
            .put("visibleMs", Stats.ms(m.visible)).put("sharpMs", Stats.ms(m.sharp))
            .apply { if (m.moved >= 0) put("movedMs", Stats.ms(m.moved)) }
            .put("ready", first == PageWatch.Ready.SHARP)
            .put("back", (shownPages.minOrNull() ?: 0) < (before.minOrNull() ?: 0))
            .put("timedOut", !ok)
    }

    /** A tap at [fx], [fy] of [activity]'s window; the time it lifts. */
    private suspend fun tap(activity: Activity, fx: Float, fy: Float): Long {
        val root = activity.window.decorView
        val x = root.width * fx
        val y = root.height * fy
        val down = SystemClock.uptimeMillis()
        touch(activity, down, down, MotionEvent.ACTION_DOWN, x, y)
        delay(TAP_MS)
        val at = System.nanoTime()
        touch(activity, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
        return at
    }

    /** A slow drag up most of [activity]'s window, held still before lifting so it does not fling; the time it starts. */
    private suspend fun drag(activity: Activity): Long {
        val root = activity.window.decorView
        val x = root.width * 0.5f
        val from = root.height * DRAG_FROM
        val to = root.height * DRAG_TO
        val down = SystemClock.uptimeMillis()
        val at = System.nanoTime()
        touch(activity, down, down, MotionEvent.ACTION_DOWN, x, from)
        for (step in 1..DRAG_STEPS) {
            delay(DRAG_STEP_MS)
            touch(activity, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, from + (to - from) * step / DRAG_STEPS)
        }
        // Held still, moving nowhere frame after frame, so the velocity at the lift is nil.
        repeat((DRAG_HOLD_MS / DRAG_STEP_MS).toInt()) {
            delay(DRAG_STEP_MS)
            touch(activity, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, to)
        }
        touch(activity, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, to)
        return at
    }

    /** A press of the volume key that turns forward; the time it goes down. */
    private fun key(activity: Activity): Long {
        val at = System.nanoTime()
        val now = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0))
        activity.dispatchKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP, 0))
        return at
    }

    private fun touch(activity: Activity, down: Long, time: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(down, time, action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        activity.dispatchTouchEvent(e)
        e.recycle()
    }

    /** Forgets what was kept of books read: page previews, sizes and seams. */
    private fun forgetBook() {
        for (dir in listOf(PreviewStore.dirIn(app.cacheDir), PageSizes.dirIn(app.cacheDir), PageSeams.dirIn(app.cacheDir))) {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ library grid

    /** Opens the library at folder [BenchPlan.path] and times its covers until none arrives for a while. */
    private suspend fun grid(plan: BenchPlan): JSONObject {
        if (plan.fresh) Thumbs.clear(app)
        probe.clear()
        idle()
        val before = ProcStats.now(app)
        val start = System.nanoTime()
        val host = tracker.resumed ?: error("No activity to start the library from")
        host.startActivity(Intent(host, ListActivity::class.java).putExtra(ListActivity.EXTRA_START_PATH, plan.path ?: error("No folder")))
        val list = awaitResumed<ListActivity>(OPEN_TIMEOUT_MS)
        watch(list.window)
        var lastEvents = -1
        var quietSince = System.nanoTime()
        val ok = framesUntil(GRID_TIMEOUT_MS) { now ->
            if (probe.coverEvents != lastEvents) {
                lastEvents = probe.coverEvents
                quietSince = now
            }
            val shown = probe.coversOnScreen()
            val settled = shown.isNotEmpty() && shown.all { it.second }
            now - quietSince > GRID_QUIET_MS * 1_000_000 && (settled || probe.coverEvents == 0 || now - quietSince > GRID_GIVE_UP_MS * 1_000_000)
        }
        val shown = probe.coversOnScreen()
        val after = ProcStats.now(app)
        val gridFrames = frames.toJson()
        unwatch()
        list.finish()
        awaitDestroyed(list)
        return JSONObject()
            .put("kind", "grid").put("plan", plan.toJson()).put("screen", screen(list))
            .put("firstCoverMs", since(start, probe.firstCover)).put("firstFullMs", since(start, probe.firstFullCover))
            .put("lastFullMs", since(start, probe.lastFullCover))
            .put("onScreen", shown.size).put("onScreenFull", shown.count { it.second }).put("coverEvents", probe.coverEvents)
            .put("timedOut", !ok).put("cpuMs", after.cpuMs - before.cpuMs).put("frames", gridFrames)
            .put("before", before.toJson()).put("after", after.toJson())
    }

    private fun since(start: Long, at: Long) = if (at < 0) -1 else Stats.ms(at - start)

    /** [activity]'s window size, "width x height". */
    private fun screen(activity: Activity): String = activity.window.decorView.let { "${it.width}x${it.height}" }

    // ------------------------------------------------------------------ plumbing

    /** Lets the last session's work settle: a second of nothing. */
    private suspend fun idle() = delay(IDLE_MS)

    /** Calls [check] on each frame with its time until it returns true (true then) or [timeoutMs] pass (false). */
    private suspend fun framesUntil(timeoutMs: Long, check: (Long) -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val now = nextFrame()
            if (check(now)) return true
            if (now > end) return false
        }
    }

    private suspend fun nextFrame(): Long = suspendCancellableCoroutine { c ->
        Choreographer.getInstance().postFrameCallback { c.resume(it) }
    }

    private suspend inline fun <reified A : Activity> awaitResumed(timeoutMs: Long): A {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            (tracker.resumed as? A)?.let { return it }
            check(SystemClock.uptimeMillis() < end) { "${A::class.java.simpleName} did not start" }
            delay(POLL_MS)
        }
    }

    private suspend fun awaitDestroyed(activity: Activity) {
        val end = SystemClock.uptimeMillis() + OPEN_TIMEOUT_MS
        while (!activity.isDestroyed && SystemClock.uptimeMillis() < end) delay(POLL_MS)
        // The activity below must be in front again before the next one starts from it.
        while (tracker.resumed == null && SystemClock.uptimeMillis() < end) delay(POLL_MS)
    }

    private fun watch(window: Window) {
        unwatch()
        frames.reset()
        window.addOnFrameMetricsAvailableListener(metrics, metricsHandler)
        watched = window
    }

    private fun unwatch() {
        watched?.let { runCatching { it.removeOnFrameMetricsAvailableListener(metrics) } }
        watched = null
    }

    @Suppress("DEPRECATION")
    private fun frameNs(window: Window): Long = (1e9 / (window.windowManager.defaultDisplay.refreshRate.takeIf { it > 0 } ?: 60f)).toLong()

    /** The activity in front, as the lifecycle tells; each one created turned to [orientation]. */
    private class Tracker : Application.ActivityLifecycleCallbacks {
        @Volatile
        var resumed: Activity? = null

        var orientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        override fun onActivityResumed(activity: Activity) {
            resumed = activity
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumed === activity) resumed = null
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            activity.requestedOrientation = orientation
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /** The settings a session changes, put back as they were afterwards. */
    private class PrefsKept(private val prefs: Prefs) {
        private val pageMode = prefs.pageMode
        private val readingDir = prefs.readingDir
        private val fit = prefs.fit
        private val twoPage = prefs.twoPage
        private val autoCrop = prefs.autoCrop
        private val perSeries = prefs.perSeries
        private val volumeKeys = prefs.volumeKeys
        private val doubleTap = prefs.doubleTapZoom
        private val tapZones = prefs.tapZones

        /** The layout and input [plan] reads in, without a folder's own layout. */
        fun apply(plan: BenchPlan) {
            val continuous = plan.layout == BenchPlan.CONTINUOUS
            prefs.pageMode = if (continuous) ReaderLayout.MODE_CONTINUOUS else ReaderLayout.MODE_PAGED
            prefs.readingDir = if (continuous) ReaderLayout.DIR_TTB else ReaderLayout.DIR_LTR
            prefs.fit = if (continuous) "width" else "screen"
            prefs.twoPage = if (plan.layout == BenchPlan.TWO) TwoPage.ON else TwoPage.OFF
            prefs.autoCrop = plan.crop
            prefs.perSeries = false
            prefs.volumeKeys = plan.input == BenchPlan.KEY
            prefs.tapZones = TapZones.LEFT_RIGHT.value
            plan.doubleTap?.let { prefs.doubleTapZoom = it }
        }

        fun restore() {
            prefs.pageMode = pageMode
            prefs.readingDir = readingDir
            prefs.fit = fit
            prefs.twoPage = twoPage
            prefs.autoCrop = autoCrop
            prefs.perSeries = perSeries
            prefs.volumeKeys = volumeKeys
            prefs.doubleTapZoom = doubleTap
            prefs.tapZones = tapZones
        }
    }

    companion object {
        const val TAG = "Bench"

        private const val OPEN_TIMEOUT_MS = 60_000L
        private const val TURN_TIMEOUT_MS = 20_000L
        private const val GRID_TIMEOUT_MS = 120_000L
        private const val GRID_QUIET_MS = 3_000L
        private const val GRID_GIVE_UP_MS = 15_000L
        private const val NO_MOVE_MS = 3_000L
        private const val IDLE_MS = 1_000L
        private const val POLL_MS = 16L

        /** Folders down a share's root its first book is looked for. */
        private const val SHARE_DEPTH = 3

        /** Where taps land: the right edge, halfway down, which turns forward in the left / right zones ([PrefsKept.apply]). */
        private const val TAP_X = 0.92f
        private const val TAP_Y = 0.5f

        /** How long a tap is held. */
        private const val TAP_MS = 60L

        /**
         * A drag between the bars (the bottom one holds the seek bar), [DRAGS] in a row: about a page
         * and a half at fit width, so the pages on screen change.
         */
        private const val DRAG_FROM = 0.8f
        private const val DRAG_TO = 0.1f
        private const val DRAGS = 2
        private const val DRAG_STEPS = 20
        private const val DRAG_STEP_MS = 12L
        private const val DRAG_HOLD_MS = 120L

        /** The results file, in [app]'s external files, which the shell reads in any build. */
        fun results(app: Application): File = File(app.getExternalFilesDir(null), "bench/results.jsonl")

        /** Appends [result] to the [results] file and the log. */
        fun record(app: Application, result: JSONObject) {
            val line = result.toString()
            results(app).apply { parentFile?.mkdirs() }.appendText(line + "\n")
            line.chunked(LOG_CHUNK).forEach { Log.i(TAG, it) }
        }

        private const val LOG_CHUNK = 3_500
    }
}
