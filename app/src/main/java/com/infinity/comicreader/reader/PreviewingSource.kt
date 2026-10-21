package com.infinity.comicreader.reader

import android.graphics.Bitmap

/**
 * [inner] with page previews: every page it decodes, for any reason, leaves its
 * preview in [store] unless one is there, made from the full-size decode before any
 * crop or resampling ([Decoders.capturing]). A source that renders straight at the
 * target size (PDF) leaves one only when it renders a whole page. [book] identifies
 * the book ([PreviewStore.bookId]).
 */
class PreviewingSource(
    private val inner: PageSource,
    private val store: PreviewStore,
    private val book: String
) : PageSource by inner {

    override fun loadPage(index: Int, target: Target): Bitmap {
        val key = keyOf(index)
        if (store.has(key)) return inner.loadPage(index, target)
        var preview: Bitmap? = null
        val page = Decoders.capturing({ preview = previewOf(it) }) { inner.loadPage(index, target) }
        (preview ?: if (isWholePage(target)) previewOf(page) else null)?.let { store.put(key, it) }
        return page
    }

    override fun loadDetail(index: Int, target: Target, maxBytes: Long): PageDetail {
        val key = keyOf(index)
        if (store.has(key)) return inner.loadDetail(index, target, maxBytes)
        var preview: Bitmap? = null
        val detail = Decoders.capturing({ preview = previewOf(it) }) { inner.loadDetail(index, target, maxBytes) }
        preview?.let { store.put(key, it) }
        return detail
    }

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
    private fun isWholePage(target: Target) = !target.autoCrop && (target.fit == Fit.INSIDE || target.fit == Fit.SHRINK)
}
