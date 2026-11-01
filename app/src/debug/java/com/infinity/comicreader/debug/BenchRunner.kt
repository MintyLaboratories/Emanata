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
import com.infinity.comicreader.data.BookCaches
import com.infinity.comicreader.data.BookIds
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.Thumbs
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.reader.Calibrator
import com.infinity.comicreader.reader.JxlPages
import com.infinity.comicreader.reader.NativeResampler
import com.infinity.comicreader.reader.PlatformPages
import com.infinity.comicreader.ui.ListActivity
import com.infinity.comicreader.ui.PageAdapter
import com.infinity.comicreader.ui.ViewerActivity
import com.infinity.comicreader.util.ReadProbes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * turn until the frame drawing the pages on screen visible or sharp completes, handed to
 * the display ([FrameStats.drawnBy]); the display shows it a frame or so later. Each
 * session is a JSON object ([run]).
 */
internal class BenchRunner(private val app: Application) {
    private val probe = BenchProbe()
    private val frames = FrameStats()
    private val tracker = Tracker()
    private val metricsThread = HandlerThread("bench-frames").apply { start() }
    private val metricsHandler = Handler(metricsThread.looper)
    private var watched: Window? = null

    private val metrics = Window.OnFrameMetricsAvailableListener { window, m, _ ->
        val deadline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) m.getMetric(FrameMetrics.DEADLINE) else frameNs(window)
        val vsync = m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        val total = m.getMetric(FrameMetrics.TOTAL_DURATION)
        // Drawing starts after input, animations, layout and measure; both times from the frame's vsync.
        val drawStart = vsync + m.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION) + m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) +
            m.getMetric(FrameMetrics.ANIMATION_DURATION) + m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
        frames.add(total, deadline, drawStart, vsync + total, first = m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L)
    }

    /** Runs [plan] from [host], the activity it was started in; one result per session. */
    suspend fun run(host: Activity, plan: BenchPlan): List<JSONObject> = Calibrator.hold { runHeld(host, plan) }

    private suspend fun runHeld(host: Activity, plan: BenchPlan): List<JSONObject> {
        tracker.orientation = plan.orientation
        tracker.onCreated = { if (it !is BenchActivity) watch(it.window) }
        app.registerActivityLifecycleCallbacks(tracker)
        tracker.resumed = host
        ReadProbes.current = probe
        val kept = KeptPrefs(Prefs(app), keptPrefs(app))
        val keptEngine = NativeResampler.engine
        val keptThreads = NativeResampler.threads
        val keptLater = NativeResampler.laterThreads
        val keptPinned = NativeResampler.pinned
        val keptJxl = JxlPages.enabled
        val keptJxlLater = JxlPages.laterThreads
        val keptJxlLittle = JxlPages.laterOnLittle
        val keptJxlNice = JxlPages.laterNice
        val keptPlatform = PlatformPages.cores
        val keptJpeg = PlatformPages.jpegPinned
        val keptAhead = PlatformPages.readAhead
        val keptDecoders = PageAdapter.decodeThreads
        plan.engine?.let { NativeResampler.engine = it }
        plan.pixelThreads?.let { NativeResampler.threads = it }
        plan.laterThreads?.let { NativeResampler.laterThreads = it }
        plan.pinned?.let { NativeResampler.pinned = it }
        plan.jxlNative?.let { JxlPages.enabled = it }
        plan.jxlLaterThreads?.let { JxlPages.laterThreads = it }
        plan.jxlLaterOnLittle?.let { JxlPages.laterOnLittle = it }
        plan.jxlLaterNice?.let { JxlPages.laterNice = it }
        plan.platformCores?.let {
            PlatformPages.cores = it
            PlatformPages.jpegPinned = true
        }
        plan.platformAhead?.let { PlatformPages.readAhead = it }
        plan.decodeThreads?.let { PageAdapter.decodeThreads = it }
        try {
            return when (plan.what) {
                BenchPlan.GRID -> listOf(grid(plan))
                else -> {
                    kept.apply(plan)
                    (1..plan.warmup + plan.reps).map { read(plan, it - plan.warmup) }
                }
            }
        } finally {
            NativeResampler.engine = keptEngine
            NativeResampler.threads = keptThreads
            NativeResampler.laterThreads = keptLater
            NativeResampler.pinned = keptPinned
            JxlPages.enabled = keptJxl
            JxlPages.laterThreads = keptJxlLater
            JxlPages.laterOnLittle = keptJxlLittle
            JxlPages.laterNice = keptJxlNice
            PlatformPages.cores = keptPlatform
            PlatformPages.jpegPinned = keptJpeg
            PlatformPages.readAhead = keptAhead
            PageAdapter.decodeThreads = keptDecoders
            kept.restore()
            ReadProbes.current = null
            probe.clear()
            unwatch()
            metricsThread.quitSafely()
            app.unregisterActivityLifecycleCallbacks(tracker)
        }
    }

    // ------------------------------------------------------------------ reading

    /** Session [rep] of [plan]: a warm-up from 0 down, measured from 1 up. */
    private suspend fun read(plan: BenchPlan, rep: Int): JSONObject {
        val path = plan.path ?: withContext(Dispatchers.IO) {
            DebugBooks.shareId(plan.share)?.let { DebugBooks.firstBook(Remotes.rootOf(it), SHARE_DEPTH, largest = false) }
        } ?: error("No book found on the share")
        if (plan.fresh) forgetBook(path)
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
        val open = Milestones(start)
        val openOk = framesUntil(OPEN_TIMEOUT_MS) { now ->
            val views = probe.pageViewsOnScreen()
            open.frame(now, probe.pages.ready(views), probe.pages.changedAt(views))
        }
        awaitDrawn(open.sharpAt)
        val openVisible = open.visible(frames::drawnBy)
        val openSharp = open.sharp(frames::drawnBy)
        val openFrames = frames.toJson()
        val afterOpen = ProcStats.now(app)
        val cpuOpen = afterOpen.cpuMs - before.cpuMs
        val openDecodes = probe.decodes

        frames.reset()
        val turns = JSONArray()
        val visible = ArrayList<Long>()
        val sharp = ArrayList<Long>()
        val moved = ArrayList<Long>()
        var cached = 0
        var unsharpFrames = 0
        val turnStart = ProcStats.now(app, memory = false)
        val turnsFrom = System.nanoTime()
        // The side that turns forward, or back with [BenchPlan.back]; a right-to-left book swaps them.
        var tapX = if (plan.back) 1f - TAP_X else TAP_X
        for (i in 1..plan.turns) {
            delay(plan.pace)
            val turn = turn(viewer, plan, tapX, plan.tapMs) ?: break
            turns.put(turn)
            if (turn.getBoolean("back") != plan.back) {
                // A right-to-left book: the other side turns the way asked; this turn is left out.
                tapX = 1f - tapX
                continue
            }
            visible += turn.getLong("visibleMs")
            sharp += turn.getLong("sharpMs")
            if (turn.has("movedMs")) moved += turn.getLong("movedMs")
            if (turn.getBoolean("ready")) cached++
            unsharpFrames += turn.getInt("unsharpFrames")
            if (turn.getBoolean("timedOut")) break
        }
        val turnsWall = System.nanoTime() - turnsFrom
        val turnEnd = ProcStats.now(app)
        val turnFrames = frames.toJson()
        // Work the session left running (scans, decodes) is its own: it counts until done.
        val settleFrom = System.nanoTime()
        val settled = settle(viewer)
        val settleWall = System.nanoTime() - settleFrom
        val after = ProcStats.now(app)
        unwatch()
        viewer.finish()
        awaitDestroyed(viewer)

        val cpu = after.cpuMs - before.cpuMs
        val aside = probe.asideCounts()
        val allDecodes = probe.decodes + aside.values.sum()
        return JSONObject()
            .put("kind", "read").put("rep", rep).put("warmup", rep < 1).put("cache", cacheState(plan, rep))
            .put("plan", plan.toJson()).put("screen", screen(viewer))
            .put("openVisibleMs", Stats.ms(openVisible)).put("openSharpMs", Stats.ms(openSharp)).put("openTimedOut", !openOk)
            .put("openStartMs", if (probe.opening >= 0) Stats.ms(probe.opening - start) else -1)
            .put("openCpuMs", cpuOpen).put("openDecodes", openDecodes)
            .put("cpuMs", cpu).put("cpuTurnsMs", turnEnd.cpuMs - before.cpuMs)
            .put("settleMs", Stats.ms(settleWall)).put("settled", settled)
            .put("decodes", probe.decodes).put("asideDecodes", JSONObject(aside))
            .put("cpuPerDecodeMs", if (allDecodes > 0) cpu / allDecodes else -1)
            .put("openFrames", openFrames)
            .put("turnVisible", Stats.summary(visible)).put("turnSharp", Stats.summary(sharp)).put("turnMoved", Stats.summary(moved))
            .put("turnsReady", cached).put("turnCount", turns.length()).put("turnUnsharpFrames", unsharpFrames)
            .put("turnCpuMs", turnEnd.cpuMs - turnStart.cpuMs).put("turnWallMs", Stats.ms(turnsWall))
            .put("turnFrames", turnFrames)
            .put("before", before.toJson()).put("afterOpen", afterOpen.toJson()).put("afterTurns", turnEnd.toJson())
            .put("after", after.toJson())
            .put("turns", turns)
    }

    /**
     * One turn forward in [viewer] as [plan] says, timed from the input's end (a tap's or
     * the last drag's lift, a key's press) until a frame showing the pages then on screen
     * visible and sharp completes; null when nothing moved. Frames from the input's start
     * on in which a page on screen is blank or a stand-in are counted too: a drag scrolls
     * pages in before it lifts.
     */
    private suspend fun turn(viewer: ViewerActivity, plan: BenchPlan, tapX: Float, tapMs: Long): JSONObject? = coroutineScope {
        val before = probe.pages.pages(probe.pageViewsOnScreen())
        var movedAt = -1L
        probe.onMoved = { if (movedAt < 0) movedAt = it }
        var frameCount = 0
        var unsharp = 0
        val counting = launch {
            while (true) {
                nextFrame()
                frameCount++
                if (probe.pages.ready(probe.pageViewsOnScreen()) != PageWatch.Ready.SHARP) unsharp++
            }
        }
        val start = when (plan.input) {
            BenchPlan.DRAG -> (1..DRAGS).map { drag(viewer) }.last()
            BenchPlan.KEY -> key(viewer)
            else -> tap(viewer, tapX, TAP_Y, tapMs)
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
                m.frame(now, ready, probe.pages.changedAt(views))
            }
        }
        counting.cancel()
        probe.onMoved = null
        awaitDrawn(m.sharpAt)
        if (movedAt >= 0) m.moved(movedAt)
        if (shownPages == before) return@coroutineScope null
        JSONObject()
            .put("pages", JSONArray(shownPages))
            .put("visibleMs", Stats.ms(m.visible(frames::drawnBy))).put("sharpMs", Stats.ms(m.sharp(frames::drawnBy)))
            .apply { if (m.moved >= 0) put("movedMs", Stats.ms(m.moved)) }
            .put("ready", first == PageWatch.Ready.SHARP)
            .put("frames", frameCount).put("unsharpFrames", unsharp)
            .put("back", (shownPages.minOrNull() ?: 0) < (before.minOrNull() ?: 0))
            .put("timedOut", !ok)
    }

    /**
     * What the app keeps of the book at session [rep] of [plan]: "fresh" (forgotten first),
     * "warm" (read in this run before), or "as found".
     */
    private fun cacheState(plan: BenchPlan, rep: Int): String = when {
        plan.fresh -> "fresh"
        rep + plan.warmup > 1 -> "warm"
        else -> "as found"
    }

    /** A tap at [fx], [fy] of [activity]'s window, held [ms]; the time it lifts. */
    private suspend fun tap(activity: Activity, fx: Float, fy: Float, ms: Long): Long {
        val root = activity.window.decorView
        val x = root.width * fx
        val y = root.height * fy
        val down = SystemClock.uptimeMillis()
        touch(activity, down, down, MotionEvent.ACTION_DOWN, x, y)
        delay(ms)
        val at = System.nanoTime()
        touch(activity, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
        return at
    }

    /** A slow drag up most of [activity]'s window, held still before lifting so it does not fling; the time it lifts. */
    private suspend fun drag(activity: Activity): Long {
        val root = activity.window.decorView
        val x = root.width * 0.5f
        val from = root.height * DRAG_FROM
        val to = root.height * DRAG_TO
        val down = SystemClock.uptimeMillis()
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
        val at = System.nanoTime()
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

    /**
     * Forgets what the app keeps of the book at [path] alone, by its Book ID: its facts, page
     * sizes, seams, previews and network copy ([BookCaches.forget]). The system's file cache
     * and the process's memory stay warm.
     */
    private suspend fun forgetBook(path: String) = withContext(Dispatchers.IO) {
        val file = Remotes.file(path)
        val id = BookIds.known(file) ?: BookIds.find(file)
        if (id != null) BookCaches.forget(app, id) else Log.w(TAG, "No Book ID for the book: nothing forgotten")
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

    /**
     * Waits until [viewer]'s background work ([ViewerActivity.busy]) has stopped for [SETTLE_QUIET_MS],
     * looking every [SETTLE_POLL_MS]; false when it still runs after [SETTLE_TIMEOUT_MS].
     */
    private suspend fun settle(viewer: ViewerActivity): Boolean {
        val end = SystemClock.uptimeMillis() + SETTLE_TIMEOUT_MS
        var quietSince = -1L
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (viewer.busy) quietSince = -1 else if (quietSince < 0) quietSince = now else if (now - quietSince >= SETTLE_QUIET_MS) return true
            if (now > end) return false
            delay(SETTLE_POLL_MS)
        }
    }

    /**
     * Calls [check] at the start of each frame, before it draws, with the time then, until it
     * returns true (true then) or [timeoutMs] pass (false).
     */
    private suspend fun framesUntil(timeoutMs: Long, check: (Long) -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val now = nextFrame()
            if (check(now)) return true
            if (now > end) return false
        }
    }

    private suspend fun nextFrame(): Long = suspendCancellableCoroutine { c ->
        Choreographer.getInstance().postFrameCallback { c.resume(System.nanoTime()) }
    }

    /** Waits a while for the window's frames to tell when the first one drawn from [at] on completed. */
    private suspend fun awaitDrawn(at: Long) {
        if (at < 0) return
        val end = SystemClock.uptimeMillis() + DRAWN_TIMEOUT_MS
        while (frames.drawnBy(at) < 0 && SystemClock.uptimeMillis() < end) delay(POLL_MS)
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

        /** Called with each activity created, its content set. */
        var onCreated: (Activity) -> Unit = {}

        override fun onActivityResumed(activity: Activity) {
            resumed = activity
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumed === activity) resumed = null
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            activity.requestedOrientation = orientation
            onCreated(activity)
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
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

        /** How long background work must have stopped to count as done, how often that is looked at, and the longest wait ([settle]). */
        private const val SETTLE_QUIET_MS = 500L
        private const val SETTLE_POLL_MS = 100L
        private const val SETTLE_TIMEOUT_MS = 60_000L
        private const val POLL_MS = 16L
        private const val DRAWN_TIMEOUT_MS = 1_000L

        /** Folders down a share's root its first book is looked for. */
        private const val SHARE_DEPTH = 3

        /** Where taps land: the right edge, halfway down, which turns forward in the left / right zones ([KeptPrefs.apply]). */
        private const val TAP_X = 0.92f
        private const val TAP_Y = 0.5f

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

        /** Where the settings a run changes are kept until it puts them back ([KeptPrefs]). */
        fun keptPrefs(app: Application): File = File(app.filesDir, "bench/kept-prefs.json")

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
