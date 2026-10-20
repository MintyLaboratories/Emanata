package com.infinity.comicreader.data

/**
 * A top of browsing that is never climbed above: the device's shared storage, a
 * removable volume (SD card, USB drive) or a network share. [path] is absolute
 * without a trailing slash; [name] stands in for it in crumbs and labels.
 */
data class StorageRoot(val path: String, val name: String, val kind: Kind) {
    enum class Kind { DEVICE, REMOVABLE, SHARE }

    fun contains(p: String): Boolean = p == path || p.startsWith(prefix)

    /** Folder names from below the root down to [p]; empty for the root itself. */
    fun segments(p: String): List<String> =
        if (p == path || !contains(p)) emptyList() else p.substring(prefix.length).split('/').filter { it.isNotEmpty() }

    private val prefix: String get() = if (path.endsWith('/')) path else "$path/"

    companion object {
        /** The innermost of [roots] holding [path], or null when none does. */
        fun of(path: String, roots: List<StorageRoot>): StorageRoot? =
            roots.filter { it.contains(path) }.maxByOrNull { it.path.length }
    }
}

/** One tappable step of a location: [label] shown, [path] opened. */
data class Crumb(val label: String, val path: String, val isRoot: Boolean)

object Crumbs {
    /**
     * The crumbs leading to [path]: its root by name, then each folder below it.
     * A path under no root starts from the filesystem root, shown as `/`.
     */
    fun of(path: String, roots: List<StorageRoot>): List<Crumb> {
        val root = StorageRoot.of(path, roots)
        val out = ArrayList<Crumb>()
        if (root != null) {
            out.add(Crumb(root.name, root.path, isRoot = true))
            var at = root.path.trimEnd('/')
            for (name in root.segments(path)) {
                at = "$at/$name"
                out.add(Crumb(name, at, isRoot = false))
            }
        } else {
            out.add(Crumb("/", "/", isRoot = true))
            var at = ""
            for (name in path.split('/').filter { it.isNotEmpty() }) {
                at = "$at/$name"
                out.add(Crumb(name, at, isRoot = false))
            }
        }
        return out
    }
}
