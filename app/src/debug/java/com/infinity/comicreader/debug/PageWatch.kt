package com.infinity.comicreader.debug

/**
 * What each page view stands for and shows, as the reader reports it
 * ([com.infinity.comicreader.util.ReadProbe]), for telling when the pages on screen are
 * visible (each shows its page, a stand-in counting) and sharp (each shows its final
 * bitmap). Views are any keys. Main thread only.
 */
internal class PageWatch {
    private class State(var bound: Int = NONE, var shown: Int = NONE, var exact: Boolean = false)

    private val states = HashMap<Any, State>()

    /** How far the pages on screen are. */
    enum class Ready { NOTHING, VISIBLE, SHARP }

    /** [view] now stands for [page]; [NONE] when for none. */
    fun bound(view: Any, page: Int) {
        val state = states.getOrPut(view) { State() }
        if (state.bound != page) {
            state.bound = page
            if (state.shown != page) state.exact = false
        }
    }

    /** [view] shows [page] ([NONE]: nothing), its final bitmap when [exact]. */
    fun shown(view: Any, page: Int, exact: Boolean) {
        val state = states.getOrPut(view) { State() }
        state.shown = page
        state.exact = exact && page != NONE
    }

    /** The pages [onScreen] views stand for, in order, without repeats. */
    fun pages(onScreen: Iterable<Any>): List<Int> =
        onScreen.mapNotNull { states[it]?.bound?.takeIf { p -> p != NONE } }.distinct()

    /**
     * How far the [onScreen] views standing for a page are: [Ready.SHARP] once each shows
     * its page's final bitmap, [Ready.VISIBLE] once each shows its page at all; nothing
     * while none stands for a page.
     */
    fun ready(onScreen: Iterable<Any>): Ready {
        var any = false
        var sharp = true
        for (view in onScreen) {
            val state = states[view] ?: continue
            if (state.bound == NONE) continue
            any = true
            if (state.shown != state.bound) return Ready.NOTHING
            if (!state.exact) sharp = false
        }
        return when {
            !any -> Ready.NOTHING
            sharp -> Ready.SHARP
            else -> Ready.VISIBLE
        }
    }

    /** The views heard of, for finding those on screen. */
    val views: Set<Any> get() = states.keys

    /** Forgets [view], gone with its window. */
    fun forget(view: Any) {
        states.remove(view)
    }

    fun clear() = states.clear()

    companion object {
        const val NONE = -1
    }
}
