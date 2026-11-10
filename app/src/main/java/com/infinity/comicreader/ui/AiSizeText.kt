package com.infinity.comicreader.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiThresholds

/** The AI size setting in words: the sentence under its slider, and its handles' values. */
object AiSizeText {

    /** [pct] of a page's side as a size: "their pixels" at 100, "half size" at 50, else "N% size". */
    fun size(context: Context, pct: Int): String = when (pct) {
        25 -> context.getString(R.string.ai_size_quarter)
        50 -> context.getString(R.string.ai_size_half)
        100 -> context.getString(R.string.ai_size_own)
        200 -> context.getString(R.string.ai_size_double)
        else -> context.getString(R.string.ai_size_percent, pct)
    }

    /** A handle's value as its bubble and the axis show it: "Always" or "N%". */
    fun value(context: Context, pct: Int): String =
        if (pct == AiThresholds.ALWAYS) context.getString(R.string.ai_size_always)
        else context.getString(R.string.ai_size_value, pct)

    /** When upscaling models run under [t]. */
    fun upscale(context: Context, t: AiThresholds): String = context.getString(R.string.ai_sizes_upscale, size(context, t.upscalePct))

    /** When cleanup models other than descreen run under [t]. */
    fun cleanup(context: Context, t: AiThresholds): String =
        if (t.cleanupPct == AiThresholds.ALWAYS) context.getString(R.string.ai_sizes_cleanup_always)
        else context.getString(R.string.ai_sizes_cleanup, size(context, t.cleanupPct))

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
