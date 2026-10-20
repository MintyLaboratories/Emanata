package com.infinity.comicreader.reader

import android.graphics.Bitmap
import java.io.IOException

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

    override fun loadPage(index: Int, target: Target): Bitmap {
        if (!enter()) throw IOException("Source closed")
        try {
            return inner.loadPage(index, target)
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

    override fun pageName(index: Int): String? = inner.pageName(index)

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
