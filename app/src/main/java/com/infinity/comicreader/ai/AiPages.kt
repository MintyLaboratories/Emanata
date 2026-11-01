package com.infinity.comicreader.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import com.infinity.comicreader.reader.BitmapUtils
import com.infinity.comicreader.reader.Cancellation
import com.infinity.comicreader.reader.Plan
import com.infinity.comicreader.reader.Scaling

/** When AI runs stop: with battery saver on, or once the device is [HOT] or hotter. */
object AiConditions {
    /** The thermal status that stops runs: moderate, where the system starts throttling. */
    const val HOT = PowerManager.THERMAL_STATUS_MODERATE

    /** Why to stop for battery saver [saver] and thermal status [thermal]; null to go on. */
    fun stop(saver: Boolean, thermal: Int): Fallback? = if (saver || thermal >= HOT) Fallback.PAUSED else null

    /** A gate for [TileEngine.run] reading [context]'s power state at each call. */
    fun gate(context: Context): () -> Fallback? {
        val pm = context.getSystemService(PowerManager::class.java) ?: return { null }
        return {
            val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else PowerManager.THERMAL_STATUS_NONE
            stop(pm.isPowerSaveMode, thermal)
        }
    }
}

/** A page rendered for display: [bitmap], from the model's output when [run] finished, else from the page alone. */
class AiRendered(val bitmap: Bitmap, val run: PageRun)

/**
 * Pages through a model, then to their display size by Lanczos3; a run that falls back
 * leaves the page as it would be without AI: the page itself by Lanczos3.
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
        val run = TileEngine.run(session, src, limits, canceled, gate)
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
