package com.infinity.comicreader.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.Thrift
import com.infinity.comicreader.reader.BitmapUtils
import com.infinity.comicreader.reader.Cancellation
import com.infinity.comicreader.reader.Plan
import com.infinity.comicreader.reader.Scaling

/**
 * When AI runs stop: with battery saver on, unless the Battery settings let AI run under it
 * ([Thrift.pausedAi]), once the device is [HOT] or hotter, or once the thermal headroom forecast
 * [FORECAST_S] seconds ahead reaches [MAX_HEADROOM].
 */
object AiConditions {
    /** The thermal status that stops runs: moderate, where the system starts throttling. */
    const val HOT = PowerManager.THERMAL_STATUS_MODERATE

    /**
     * The forecast headroom that stops runs: 1.0 is where the system throttles hard, and the
     * status lags it; stopping a little before keeps runs from adding to the heat.
     */
    const val MAX_HEADROOM = 0.9f

    /** Seconds ahead the headroom is forecast. */
    const val FORECAST_S = 10

    /** The least time between headroom readings: the system answers NaN to more frequent ones. */
    const val HEADROOM_EVERY_MS = 1_000L

    /** Why to stop for battery saver [saver], thermal status [thermal] and forecast [headroom] (NaN: unknown); null to go on. */
    fun stop(saver: Boolean, thermal: Int, headroom: Float = Float.NaN): Fallback? =
        if (saver || thermal >= HOT || headroom >= MAX_HEADROOM) Fallback.PAUSED else null

    /** A gate for [TileEngine.run] reading [context]'s power state and the Battery settings at each call. */
    fun gate(context: Context): () -> Fallback? {
        val pm = context.getSystemService(PowerManager::class.java) ?: return { null }
        val prefs = Prefs(context)
        val headroom = Headroom(SystemClock::elapsedRealtime) {
            if (Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(FORECAST_S) else Float.NaN
        }
        return {
            val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else PowerManager.THERMAL_STATUS_NONE
            stop(Thrift.of(pm.isPowerSaveMode, prefs).pausedAi, thermal, headroom.get())
        }
    }

    /** The headroom [read] gives, read at most every [HEADROOM_EVERY_MS] by [clock]; a NaN reading keeps the one before. */
    internal class Headroom(private val clock: () -> Long, private val read: () -> Float) {
        private var at = Long.MIN_VALUE
        private var last = Float.NaN

        @Synchronized
        fun get(): Float {
            val now = clock()
            if (at == Long.MIN_VALUE || now - at >= HEADROOM_EVERY_MS) {
                at = now
                val h = read()
                if (!h.isNaN()) last = h
            }
            return last
        }
    }
}

/** A page rendered for display: [bitmap], from the model's output when [run] finished, else from the page alone. */
class AiRendered(val bitmap: Bitmap, val run: PageRun)

/**
 * A page through a chain ([AiPages.chain]): [bitmap] when it finished, else why not
 * ([fallback]); [ran] models in [ms]. [early]: it fell back as [Fallback.TIMEOUT] before any
 * model ran, its expected time past the limit.
 */
class ChainRun(val bitmap: Bitmap?, val fallback: Fallback?, val ran: Int, val ms: Long, val early: Boolean = false)

/** A model of a chain that runs on a page: [index] in the chain, on an input of [pixels]. */
data class ChainStep(val index: Int, val pixels: Long)

/**
 * Pages through a model, then to their display size by Lanczos3; a run that falls back
 * leaves the page as it would be without AI: the page itself by Lanczos3. An upscaling model
 * does not run for a page shown no larger than it is ([Fallback.UNNEEDED]): the resampling
 * after it would throw its added pixels away. A gray page stays gray: each model's output on it
 * is collapsed to gray before anything reads it ([GrayPages]).
 */
internal object AiPages {
    /**
     * [src] rendered as [plan] (in [src]'s pixels) and [scaling] say: through [session]'s model
     * and then resampled, or, when the run falls back, [src] resampled alone. Throws
     * [java.util.concurrent.CancellationException] once [canceled].
     */
    fun render(
        session: Session,
        src: Bitmap,
        plan: Plan,
        scaling: Scaling = Scaling.DEFAULT,
        linear: Boolean = false,
        limits: PageLimits = PageLimits(),
        canceled: () -> Boolean = { false },
        gate: () -> Fallback? = { null }
    ): AiRendered {
        val run = if (session.geometry.scale > 1 && plan.shrinks()) {
            PageRun(null, Fallback.UNNEEDED, RunStats.NONE)
        } else {
            TileEngine.run(session, src, limits, canceled, gate, gray = GrayPages.isGray(src))
        }
        val big = run.bitmap
        return Cancellation.during(canceled) {
            if (big == null) {
                AiRendered(BitmapUtils.resample(src, plan, linear, scaling), run)
            } else {
                try {
                    AiRendered(BitmapUtils.resample(big, scaled(plan, session.geometry.scale), linear, scaling), run)
                } finally {
                    big.recycle()
                }
            }
        }
    }

    /**
     * [src] through [sessions]' models in turn, then resampled as [plan] (in [src]'s pixels)
     * and [scaling] say; no bitmap when the chain falls back ([ChainRun.fallback]), when
     * nothing else is made: the page shows as it would without AI. An upscaling model is
     * skipped where the page shows no larger than its input to it; the chain falls back as
     * [Fallback.UNNEEDED] when every model is. All models share [limits]' time, toward which
     * time [hold] held them back does not count. [expect] gives the milliseconds a model is
     * expected to take on an input of so many pixels (0: unknown): a chain expected to take
     * longer than the limit falls back at once, and each model stops as soon as it would leave
     * the models after it too little time. [onStep] hears each model's run on its input, once
     * it ran a tile. Throws [java.util.concurrent.CancellationException] once
     * [canceled]. [src] is only read.
     */
    fun chain(
        sessions: List<Session>,
        src: Bitmap,
        plan: Plan,
        scaling: Scaling = Scaling.DEFAULT,
        linear: Boolean = false,
        limits: PageLimits = PageLimits(),
        canceled: () -> Boolean = { false },
        gate: () -> Fallback? = { null },
        clock: () -> Long = SystemClock::elapsedRealtime,
        hold: () -> Boolean = { false },
        expect: (index: Int, pixels: Long) -> Long = { _, _ -> 0L },
        onStep: (index: Int, pixels: Long, stats: RunStats) -> Unit = { _, _, _ -> }
    ): ChainRun {
        val start = clock()
        val steps = steps(sessions.map { it.geometry.scale }, src.width, src.height, plan)
        if (steps.isEmpty()) return ChainRun(null, Fallback.UNNEEDED, 0, 0)
        val expected = steps.map { expect(it.index, it.pixels).coerceAtLeast(0) }
        if (expected.sum() > limits.timeoutMs) return ChainRun(null, Fallback.TIMEOUT, 0, 0, early = true)
        // Read once: each model's output on a gray page is collapsed to gray, so the next model's input stays gray.
        val gray = GrayPages.isGray(src)
        // The models' own time, without time held.
        var spent = 0L
        var page = src
        var scale = 1
        var ran = 0
        try {
            for ((n, step) in steps.withIndex()) {
                val session = sessions[step.index]
                // Time left for this model: what the models after it are expected to take is kept for them.
                val left = limits.timeoutMs - spent - expected.drop(n + 1).sum()
                if (left <= 0) return ChainRun(null, Fallback.TIMEOUT, ran, clock() - start)
                val run = TileEngine.run(session, page, limits.copy(timeoutMs = left), canceled, gate, clock, hold, gray)
                spent += run.stats.totalMs
                if (run.stats.tiles > 0) onStep(step.index, step.pixels, run.stats)
                val out = run.bitmap ?: return ChainRun(null, run.fallback ?: Fallback.FAILED, ran, clock() - start)
                if (page !== src) page.recycle()
                page = out
                scale *= session.geometry.scale
                ran++
            }
            val out = Cancellation.during(canceled) { BitmapUtils.resample(page, scaled(plan, scale), linear, scaling) }
            return ChainRun(out, null, ran, clock() - start)
        } finally {
            if (page !== src) page.recycle()
        }
    }

    /**
     * The models of a chain, by their [scales], that run on a [width] x [height] page shown as
     * [plan], each with its input's pixels: an upscaler is left out where the page shows no
     * larger than its input.
     */
    fun steps(scales: List<Int>, width: Int, height: Int, plan: Plan): List<ChainStep> {
        val out = ArrayList<ChainStep>()
        var scale = 1
        for ((i, s) in scales.withIndex()) {
            if (s > 1 && scaled(plan, scale).shrinks()) continue
            out += ChainStep(i, width.toLong() * height * scale * scale)
            scale *= s
        }
        return out
    }

    /** [plan] for a source [scale] times larger. */
    fun scaled(plan: Plan, scale: Int): Plan =
        plan.copy(x = plan.x * scale, y = plan.y * scale, spanW = plan.spanW * scale, spanH = plan.spanH * scale)
}
