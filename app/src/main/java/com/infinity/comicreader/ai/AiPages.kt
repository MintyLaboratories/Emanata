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
 * Pages through a model, then to their display size by Lanczos3; a run that falls back
 * leaves the page as it would be without AI: the page itself by Lanczos3. An upscaling model
 * does not run for a page shown no larger than it is ([Fallback.UNNEEDED]): the resampling
 * after it would throw its added pixels away.
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
            TileEngine.run(session, src, limits, canceled, gate)
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

    /** [plan] for a source [scale] times larger. */
    fun scaled(plan: Plan, scale: Int): Plan =
        plan.copy(x = plan.x * scale, y = plan.y * scale, spanW = plan.spanW * scale, spanH = plan.spanH * scale)
}
