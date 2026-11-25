package minty.emanata.data

/**
 * Browser-style forward crumbs: going up keeps the deeper path visible, so it can
 * be gone back into. The trail is the deepest folder of the branch being browsed.
 */
object ForwardCrumbs {
    /**
     * The trail after opening [dir] with [trail] before it: kept while [dir] lies on
     * it (going up, or into its next folder), else [dir] itself.
     */
    fun next(trail: String?, dir: String): String =
        if (trail != null && isWithin(trail, dir)) trail else dir

    /** True when [path] is [ancestor] or lies below it. */
    fun isWithin(path: String, ancestor: String): Boolean {
        val a = ancestor.trimEnd('/', '\\')
        if (path.trimEnd('/', '\\') == a) return true
        return path.length > a.length && path.startsWith(a) && isSeparator(path[a.length])
    }

    /**
     * The crumbs to show at [current] with [trail]: [current]'s crumbs, then the
     * trail's deeper folders, with the index of the current crumb.
     */
    fun crumbs(current: String, trail: String?, roots: List<StorageRoot>): Pair<List<Crumb>, Int> {
        val here = Crumbs.of(current, roots)
        if (trail == null || !isWithin(trail, current) || trail.trimEnd('/', '\\') == current.trimEnd('/', '\\')) {
            return here to here.lastIndex
        }
        val deeper = Crumbs.of(trail, roots)
        // The trail's crumbs start with the same ones when it shares the current root.
        if (deeper.size <= here.size || deeper.take(here.size).map { it.path } != here.map { it.path }) {
            return here to here.lastIndex
        }
        return deeper to here.lastIndex
    }
}
