package minty.emanata.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import minty.emanata.R
import minty.emanata.ai.AiThresholds

/**
 * The AI size setting in words: the sentence under its slider, and its handles' values. Sizes are
 * reading sizes ([minty.emanata.ai.ReadSize]) as a percentage of a page's pixel size.
 */
object AiSizeText {

    /** A handle's value as its bubble and the axis show it: "Always" or "N%". */
    fun value(context: Context, pct: Int): String =
        if (pct == AiThresholds.ALWAYS) context.getString(R.string.ai_size_always)
        else context.getString(R.string.ai_size_value, pct)

    /** When upscaling models run under [t]. */
    fun upscale(context: Context, t: AiThresholds): String = context.getString(R.string.ai_sizes_upscale, t.upscalePct)

    /** When cleanup models other than descreen run under [t]. */
    fun cleanup(context: Context, t: AiThresholds): String =
        if (t.cleanupPct == AiThresholds.ALWAYS) context.getString(R.string.ai_sizes_cleanup_always)
        else context.getString(R.string.ai_sizes_cleanup, t.cleanupPct)

    /** The clauses of [t] that differ from the defaults, for where the slider is hidden; null at the defaults. */
    fun changed(context: Context, t: AiThresholds): String? {
        val d = AiThresholds()
        val clauses = listOfNotNull(
            upscale(context, t).takeIf { t.upscalePct != d.upscalePct },
            cleanup(context, t).takeIf { t.cleanupPct != d.cleanupPct }
        )
        return if (clauses.isEmpty()) null else context.getString(R.string.ai_sizes_changed, clauses.joinToString(" "))
    }

    /** The sentence under the slider: upscale, cleanup and descreen, the first two in their handles' colors. */
    fun sentence(context: Context, t: AiThresholds, upscaleColor: Int, cleanupColor: Int): CharSequence {
        val out = SpannableStringBuilder()
        fun add(text: String, color: Int?) {
            if (out.isNotEmpty()) out.append(' ')
            val start = out.length
            out.append(text)
            if (color != null) out.setSpan(ForegroundColorSpan(color), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        add(upscale(context, t), upscaleColor)
        add(cleanup(context, t), cleanupColor)
        add(context.getString(R.string.ai_sizes_descreen), null)
        return out
    }
}
