package com.infinity.comicreader.util

import android.view.View

/**
 * Hears what the reader and the library show, for benchmarks in debug builds; nothing
 * listens in a release build ([ReadProbes.current] stays null). Main thread only, but for
 * [decodedAside].
 */
interface ReadProbe {
    /** The reader starts opening the book at [path]. */
    fun opening(path: String) = Unit

    /** The reader decoded [page] for showing or reading ahead. */
    fun decoded(page: Int) = Unit

    /** A page was read and decoded besides the reader's own decodes, for [what] ("sides", "preview"...); any thread. */
    fun decodedAside(what: String) = Unit

    /** The reader moved to [item] (a page, or two pages side by side). */
    fun moved(item: Int) = Unit

    /** Page view [view] now stands for [page] of the open book; -1 when it stands for none. */
    fun bound(view: View, page: Int) = Unit

    /**
     * Page view [view] shows [page] (-1: nothing); [exact] when that is the page's final
     * bitmap for its size (or why it failed), not a stand-in.
     */
    fun shown(view: View, page: Int, exact: Boolean) = Unit

    /** Cover view [view] at [position] in a library list shows a cover; [exact] when it is the full one. */
    fun cover(view: View, position: Int, exact: Boolean) = Unit
}

/** Where the [ReadProbe] listening now is kept. */
object ReadProbes {
    /** The probe listening, if any; set only by debug benchmarks. */
    @Volatile
    var current: ReadProbe? = null
}
