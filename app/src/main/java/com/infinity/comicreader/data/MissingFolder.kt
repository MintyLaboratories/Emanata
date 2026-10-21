package com.infinity.comicreader.data

/** Where browsing goes instead of a folder that no longer exists. */
object MissingFolder {

    /**
     * [path] when [isDir] says it exists, else its nearest existing parent within its
     * storage root ([StorageRoot.bound]); null when not even the root exists.
     */
    fun nearest(path: String, roots: List<StorageRoot>, isDir: (String) -> Boolean): String? {
        val root = StorageRoot.bound(path, roots) ?: return path.takeIf(isDir)
        var p = path.trimEnd('/', '\\')
        while (true) {
            if (isDir(p)) return p
            if (p == root.path || !root.contains(p)) return null
            val cut = p.indexOfLast { isSeparator(it) }
            if (cut <= 0) return null
            p = p.substring(0, cut)
        }
    }

    /** The forward-crumb [trail] once [missing] is found gone: dropped when it runs through [missing]. */
    fun trailAfter(trail: String?, missing: String): String? =
        trail?.takeUnless { ForwardCrumbs.isWithin(it, missing) }
}
