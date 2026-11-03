package com.infinity.comicreader.reader

import android.graphics.Bitmap

/**
 * [inner] with page previews: every page it loads ([loadPage]) leaves its preview in
 * [store] unless one is there. JPEG and WebP previews are decoded from the page's
 * bytes on the store's writer; other formats are halved once from the full-size decode
 * before any crop or resampling ([Decoders.capturing]), and finished on the writer.
 * A source that renders straight at the target size (PDF) leaves one only when it
 * renders a whole page no smaller than its preview ([Previews.isFullSize]), so a small
 * cover never stands in for later renders. Detail loads ([loadDetail]) follow a
 * [loadPage] of the same page and leave none. [book] identifies the book
 * ([PreviewStore.bookId]).
 */
class PreviewingSource(
    private val inner: PageSource,
    private val store: PreviewStore,
    private val book: String
) : PageSource by inner {

    override val bookKey: String get() = book

    override fun loadPage(index: Int, target: Target): Bitmap {
        val key = keyOf(index)
        if (store.has(key)) return inner.loadPage(index, target)
        var captured = false
        val sink = object : Decoders.PreviewSink {
            override fun encoded(bytes: ByteArray) {
                captured = true
                store.putMade(key) { Decoders.preview(bytes) }
            }

            override fun decoded(full: Bitmap) {
                captured = true
                if (!store.wants(key)) return
                val size = Previews.sizeOf(full.width, full.height)
                val step = runCatching { Previews.firstStep(full) }.getOrNull() ?: return
                val taken = store.putMade(key) {
                    try {
                        Previews.make(step, size)
                    } finally {
                        step.recycle()
                    }
                }
                if (!taken) step.recycle()
            }
        }
        val page = Decoders.capturing(sink) { inner.loadPage(index, target) }
        if (!captured && isWholePage(target) && Previews.isFullSize(page.width, page.height)) {
            previewOf(page)?.let { store.put(key, it) }
        }
        return page
    }

    override fun keptPreview(index: Int): Bitmap? = store.get(keyOf(index))

    /** The stored preview of page [index], else a quick one from [inner], which is stored. */
    override fun loadPreview(index: Int): Bitmap? {
        val key = keyOf(index)
        store.get(key)?.let { return it }
        return inner.loadPreview(index)?.also { store.put(key, it) }
    }

    private fun keyOf(index: Int) = PreviewStore.keyOf(book, index, inner.pageName(index))

    /** A preview of [page]; null when making one fails, which never fails the page. */
    private fun previewOf(page: Bitmap): Bitmap? = try {
        Previews.make(page)
    } catch (t: Throwable) {
        null
    }

    /** True when a page made to [target] shows all of the page, uncropped and undistorted. */
    private fun isWholePage(target: Target) =
        !target.autoCrop && target.box == null && target.half == Half.WHOLE && (target.fit == Fit.INSIDE || target.fit == Fit.SHRINK)
}
