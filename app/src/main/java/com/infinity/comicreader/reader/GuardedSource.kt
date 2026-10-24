package com.infinity.comicreader.reader

import android.graphics.Bitmap
import java.io.IOException
import java.nio.charset.Charset

/**
 * Makes [close] safe while reads are in flight: the archive is closed once the last
 * running [loadPage] or [pageSize] returns, never under it. Reads started after
 * [close] fail as on a closed source. Never blocks the caller of [close], which is
 * usually the main thread.
 */
class GuardedSource(private val inner: PageSource) : PageSource {
    private val lock = Any()
    private var reads = 0
    private var closing = false
    private var closed = false

    override val pageCount: Int get() = inner.pageCount

    override val isVector: Boolean get() = inner.isVector

    override val cheapSizes: Boolean get() = inner.cheapSizes

    override fun loadedSize(index: Int): IntArray? = inner.loadedSize(index)

    override fun loadPage(index: Int, target: Target): Bitmap {
        if (!enter()) throw IOException("Source closed")
        try {
            return inner.loadPage(index, target)
        } finally {
            exit()
        }
    }

    override fun loadPreview(index: Int): Bitmap? {
        if (!enter()) return null
        try {
            return inner.loadPreview(index)
        } finally {
            exit()
        }
    }

    override fun loadFirstLayer(index: Int): Bitmap? {
        if (!enter()) return null
        try {
            return inner.loadFirstLayer(index)
        } finally {
            exit()
        }
    }

    override fun pageSize(index: Int): IntArray? {
        if (!enter()) return null
        try {
            return inner.pageSize(index)
        } finally {
            exit()
        }
    }

    /** The inner detail, whose renders are guarded like reads. */
    override fun loadDetail(index: Int, target: Target, maxBytes: Long): PageDetail {
        if (!enter()) throw IOException("Source closed")
        try {
            return Detail(inner.loadDetail(index, target, maxBytes))
        } finally {
            exit()
        }
    }

    private inner class Detail(private val detail: PageDetail) : PageDetail {
        override val byteCount: Long get() = detail.byteCount

        override fun render(region: Region, width: Int, height: Int, linear: Boolean, scaling: Scaling): Bitmap {
            if (!enter()) throw IOException("Source closed")
            try {
                return detail.render(region, width, height, linear, scaling)
            } finally {
                exit()
            }
        }

        override fun release() = detail.release()
    }

    override fun edge(index: Int, bottom: Boolean): Seams.Profile? {
        if (!enter()) return null
        try {
            return inner.edge(index, bottom)
        } finally {
            exit()
        }
    }

    override fun sides(index: Int): SpreadSeams.Sides? {
        if (!enter()) return null
        try {
            return inner.sides(index)
        } finally {
            exit()
        }
    }

    override fun pageName(index: Int): String? = inner.pageName(index)

    override fun comicInfo(cheapOnly: Boolean): ComicInfo? {
        if (!enter()) return null
        try {
            return inner.comicInfo(cheapOnly)
        } finally {
            exit()
        }
    }

    override fun comicInfoCostly(): Boolean {
        if (!enter()) return false
        try {
            return inner.comicInfoCostly()
        } finally {
            exit()
        }
    }

    override val nameCharset: Charset? get() = inner.nameCharset

    override fun close() {
        synchronized(lock) {
            closing = true
            if (reads > 0 || closed) return
            closed = true
        }
        runCatching { inner.close() }
    }

    private fun enter(): Boolean = synchronized(lock) {
        if (closing) return false
        reads++
        true
    }

    private fun exit() {
        synchronized(lock) {
            reads--
            if (reads > 0 || !closing || closed) return
            closed = true
        }
        runCatching { inner.close() }
    }
}
