package com.infinity.comicreader.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

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
     * bitmap by the [Plan]. Without a target size it renders at [NATIVE_SCALE] pixels
     * per point. With auto-crop, a first render finds the content, which a second one
     * then fills the target with.
     */
    @Synchronized
    override fun loadPage(index: Int, target: Target): Bitmap {
        try {
            val page = renderer.openPage(index)
            try {
                val pw = page.width.coerceAtLeast(1).toDouble()
                val ph = page.height.coerceAtLeast(1).toDouble()
                val sized = if (target.width <= 0 && target.height <= 0) {
                    target.copy(width = (pw * NATIVE_SCALE).toInt())
                } else {
                    target
                }
                val plan = sized.plan(pw, ph)
                val bitmap = render(page, plan)
                if (!target.autoCrop) return bitmap
                val bounds = BitmapUtils.contentBounds(bitmap) ?: return bitmap
                val sx = plan.spanW / plan.width
                val sy = plan.spanH / plan.height
                val cropped = sized.plan(
                    bounds.width() * sx, bounds.height() * sy, plan.x + bounds.left * sx, plan.y + bounds.top * sy
                )
                bitmap.recycle()
                return render(page, cropped)
            } finally {
                page.close()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to render PDF page $index", t)
            throw t
        }
    }

    /** [page] rendered on white, its region [plan] says filling the bitmap. */
    private fun render(page: PdfRenderer.Page, plan: Plan): Bitmap {
        val bitmap = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val transform = Matrix().apply {
            setTranslate(-plan.x.toFloat(), -plan.y.toFloat())
            postScale((plan.width / plan.spanW).toFloat(), (plan.height / plan.spanH).toFloat())
        }
        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
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
        private const val NATIVE_SCALE = 2
    }
}
