package minty.emanata.ui

import android.content.Context
import minty.emanata.R
import minty.emanata.ai.AiNote
import minty.emanata.ai.Fallback
import minty.emanata.ai.PageClass
import minty.emanata.ai.PageLimits
import minty.emanata.reader.ImageKind
import minty.emanata.reader.PageFormat

/** Page info's words for a page's format, e.g. "AVIF, lossy, layered, 3 layers", and for what AI did with it. */
object PageInfoText {

    fun format(context: Context, format: PageFormat?): String {
        val kind = format?.kind ?: return context.getString(R.string.page_format_unknown)
        val parts = ArrayList<String>()
        parts += name(kind)
        when (format.lossless) {
            true -> parts += context.getString(R.string.page_format_lossless)
            false -> parts += context.getString(R.string.page_format_lossy)
            null -> Unit
        }
        if (format.progressive) parts += context.getString(R.string.page_format_progressive)
        if (format.animated) parts += context.getString(R.string.page_format_animated)
        if (format.layers > 0) parts += context.resources.getQuantityString(R.plurals.page_format_layers, format.layers, format.layers)
        return parts.joinToString(", ")
    }

    /** The format's usual name. */
    fun name(kind: ImageKind): String = when (kind) {
        ImageKind.JPEG -> "JPEG"
        ImageKind.PNG -> "PNG"
        ImageKind.GIF -> "GIF"
        ImageKind.BMP -> "BMP"
        ImageKind.WEBP -> "WebP"
        ImageKind.AVIF -> "AVIF"
        ImageKind.HEIF -> "HEIF"
        ImageKind.JXL -> "JPEG XL"
        ImageKind.TIFF -> "TIFF"
    }

    /**
     * What AI last did with a page ([note]), a line each: the size it was read at and why, the
     * models not needed at that size, and why it shows without AI; "Processed" when none says more.
     */
    fun ai(context: Context, note: AiNote): String {
        val lines = ArrayList<String>()
        note.percent?.let { pct ->
            val how = note.pageClass?.takeIf { note.printed }?.let { context.getString(R.string.page_info_ai_printed, className(context, it)) }
                ?: context.getString(R.string.page_info_ai_fit)
            lines += context.getString(R.string.page_info_ai_read, Math.round(pct).toInt(), how)
        }
        if (note.small.isNotEmpty()) lines += context.getString(R.string.page_info_ai_small, note.small.joinToString(", "))
        note.why?.let(::plainReason)?.let { lines += context.getString(R.string.page_info_ai_plain, context.getString(it, (PageLimits.DEFAULT_MAX_OUTPUT_PIXELS shr 20).toInt())) }
        if (lines.isEmpty()) lines += context.getString(R.string.page_info_ai_done)
        return lines.joinToString("\n")
    }

    /** A page class's name, as in "printed size of a US comic". */
    fun className(context: Context, c: PageClass): String = context.getString(
        when (c) {
            PageClass.MANGA -> R.string.page_class_manga
            PageClass.WEB -> R.string.page_class_web
            PageClass.FRANCO_BELGIAN -> R.string.page_class_album
            PageClass.US -> R.string.page_class_us
        }
    )

    /** Why a page shows without AI, in words; null where the models not needed say it ([Fallback.UNNEEDED]). */
    private fun plainReason(why: Fallback): Int? = when (why) {
        Fallback.TIMEOUT -> R.string.ai_plain_timeout
        Fallback.TOO_LARGE -> R.string.ai_plain_too_large
        Fallback.TRANSLUCENT -> R.string.ai_plain_translucent
        Fallback.UNREADABLE -> R.string.ai_plain_unreadable
        Fallback.PAUSED -> R.string.ai_plain_paused
        Fallback.FAILED -> R.string.ai_plain_failed
        Fallback.UNOPENED -> R.string.ai_plain_unopened
        Fallback.UNNEEDED -> null
    }
}
