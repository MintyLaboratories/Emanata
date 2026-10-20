package com.infinity.comicreader.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import kotlin.math.roundToInt

/** Renders PDF pages with the platform [PdfRenderer]. */
class PdfSource(file: File) : PageSource {
    private val pfd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(pfd)
    } catch (t: Throwable) {
        // Encrypted or corrupt PDF: do not leak the descriptor.
        runCatching { pfd.close() }
        throw t
    }

    override val pageCount: Int get() = renderer.pageCount

    /**
     * [PdfRenderer] allows only one open [PdfRenderer.Page] at a time and is not
     * safe for concurrent use; the adapter loads neighbours concurrently, so
     * access is serialized here.
     *
     * The page is rendered straight at the target size, its points mapped onto the
     * bitmap by the [Plan] from [layout].
     */
    @Synchronized
    override fun loadPage(index: Int, target: Target): Bitmap {
        try {
            val page = renderer.openPage(index)
            try {
                return render(page, layout(page, target))
            } finally {
                page.close()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to render PDF page $index", t)
            throw t
        }
    }

    /**
     * Vector detail: regions are rendered straight at their size from the page's
     * points, laid out as [loadPage] lays them out for [target]; holds no pixels.
     */
    @Synchronized
    override fun loadDetail(index: Int, target: Target, maxBytes: Long): PageDetail {
        val page = renderer.openPage(index)
        try {
            return VectorDetail(index, layout(page, target))
        } finally {
            page.close()
        }
    }

    /** Where [target] lays out [page], for [loadPage] and [loadDetail] alike ([plan]). */
    private fun layout(page: PdfRenderer.Page, target: Target): Plan =
        plan(target, page.width.coerceAtLeast(1).toDouble(), page.height.coerceAtLeast(1).toDouble()) { first ->
            val bitmap = render(page, first)
            BitmapUtils.contentBounds(bitmap).also { bitmap.recycle() }
        }

    private inner class VectorDetail(private val index: Int, private val page: Plan) : PageDetail {
        override val byteCount: Long get() = 0L

        override fun render(region: Region, width: Int, height: Int, linear: Boolean): Bitmap =
            renderPlan(index, region.plan(width, height, page.x, page.y, page.spanW, page.spanH))

        override fun release() {
            // Holds nothing: every render opens the page anew.
        }
    }

    @Synchronized
    private fun renderPlan(index: Int, plan: Plan): Bitmap {
        val page = renderer.openPage(index)
        try {
            return render(page, plan)
        } finally {
            page.close()
        }
    }

    /** [page] rendered on white, its region [plan] says filling the bitmap. */
    private fun render(page: PdfRenderer.Page, plan: Plan): Bitmap {
        val bitmap = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, transform(plan), PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    @Synchronized
    override fun pageSize(index: Int): IntArray? {
        if (index < 0 || index >= renderer.pageCount) return null
        return runCatching {
            val page = renderer.openPage(index)
            try {
                intArrayOf(page.width, page.height)
            } finally {
                page.close()
            }
        }.getOrNull()
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }

    companion object {
        private const val TAG = "PdfSource"

        /** Pixels per PDF point (1/72 inch) for a render without a target size. */
        const val NATIVE_SCALE = 2

        /**
         * [target] for a [pw]-point-wide page, whose points stand in for source pixels. A
         * vector page has no native resolution, so [Fit.SHRINK] enlarges like [Fit.INSIDE],
         * and with no size at all the page renders at [NATIVE_SCALE] pixels per point.
         */
        fun vectorTarget(target: Target, pw: Double): Target {
            val t = if (target.fit == Fit.SHRINK) target.copy(fit = Fit.INSIDE) else target
            return if (t.width <= 0 && t.height <= 0) t.copy(width = (pw * NATIVE_SCALE).roundToInt()) else t
        }

        /**
         * Where [target] lays out a [pw] x [ph]-point page ([vectorTarget]). With auto-crop,
         * [content] finds the content in a first render at the uncropped plan, and the
         * content then fills the target ([cropPlan]); without content found the page does.
         */
        fun plan(target: Target, pw: Double, ph: Double, content: (Plan) -> Rect?): Plan {
            val sized = vectorTarget(target, pw)
            val plan = sized.plan(pw, ph)
            if (!target.autoCrop) return plan
            val bounds = content(plan) ?: return plan
            return cropPlan(sized, plan, bounds.left, bounds.top, bounds.width(), bounds.height())
        }

        /**
         * The second render's plan: content found at ([left], [top]) sized [w] x [h] in the
         * pixels of the [first] render, mapped back to points and onto [target].
         */
        fun cropPlan(target: Target, first: Plan, left: Int, top: Int, w: Int, h: Int): Plan {
            val sx = first.spanW / first.width
            val sy = first.spanH / first.height
            return target.plan(w * sx, h * sy, first.x + left * sx, first.y + top * sy)
        }

        /** Maps page points onto bitmap pixels: the [plan]'s span fills the bitmap. */
        fun transform(plan: Plan): Matrix = Matrix().apply {
            setTranslate(-plan.x.toFloat(), -plan.y.toFloat())
            postScale((plan.width / plan.spanW).toFloat(), (plan.height / plan.spanH).toFloat())
        }
    }
}
