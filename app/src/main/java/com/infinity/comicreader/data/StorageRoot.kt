package com.infinity.comicreader.data

/**
 * A top of browsing that is never climbed above: the device's shared storage, a
 * removable volume (SD card, USB drive) or a network share. [path] is absolute
 * without a trailing slash; [name] stands in for it in crumbs and labels.
 */
data class StorageRoot(val path: String, val name: String, val kind: Kind) {
    /** Where a root lives; [REMOVABLE] is an SD card, or any removable volume not known to be USB. */
    enum class Kind { DEVICE, REMOVABLE, USB, SHARE }

    fun contains(p: String): Boolean =
        p == path || (p.length > path.length && p.startsWith(path) && (isSeparator(p[path.length]) || isSeparator(path.last())))

    /** Folder names from below the root down to [p]; empty for the root itself. */
    fun segments(p: String): List<String> =
        if (p == path || !contains(p)) emptyList() else Paths.parts(p.substring(path.length))

    companion object {
        /** The innermost of [roots] holding [path], or null when none does. */
        fun of(path: String, roots: List<StorageRoot>): StorageRoot? =
            roots.filter { it.contains(path) }.maxByOrNull { it.path.length }

        /**
         * The removable root or share of [path] among [before] that [now] no longer lists
         * (a drive pulled out, a share removed); null while it is there, or when [path] is
         * on the device itself.
         */
        fun removed(path: String, before: List<StorageRoot>, now: List<StorageRoot>): StorageRoot? {
            val root = of(path, before) ?: return null
            if (root.kind == Kind.DEVICE) return null
            return root.takeIf { r -> now.none { it.path == r.path } }
        }

        /** The last removable root in [now] that [before] did not list (a drive plugged in); null when none is new. */
        fun added(before: List<StorageRoot>, now: List<StorageRoot>): StorageRoot? =
            now.lastOrNull { r -> (r.kind == Kind.REMOVABLE || r.kind == Kind.USB) && before.none { it.path == r.path } }

        /**
         * The top [path] may be browsed up to: its root in [roots], else the first
         * folder below the system ones (`/`, `/storage`, `/storage/emulated`), e.g.
         * an unlisted volume's `/storage/1234-ABCD`. Null for a system folder itself.
         */
        fun bound(path: String, roots: List<StorageRoot>): StorageRoot? = of(path, roots) ?: implied(path)

        /**
         * [path] as a subtitle reads it: below the device's storage without its root
         * (`Comics/Series`, empty for the root itself), on another root after the
         * root's name (`SD card/Comics`), and in full under no root.
         */
        fun describe(path: String, roots: List<StorageRoot>): String {
            val root = of(path, roots) ?: return path
            val below = root.segments(path).joinToString("/")
            return when {
                root.kind == Kind.DEVICE -> below
                below.isEmpty() -> root.name
                else -> root.name + "/" + below
            }
        }

        private val SYSTEM_TOPS = setOf("/", "/storage", "/storage/emulated")

        private fun implied(path: String): StorageRoot? {
            var i = 0
            while (i < path.length) {
                while (i < path.length && isSeparator(path[i])) i++
                if (i >= path.length) break
                val start = i
                while (i < path.length && !isSeparator(path[i])) i++
                val top = path.substring(0, i)
                if (Paths.parts(top).joinToString("/", prefix = "/") !in SYSTEM_TOPS) {
                    return StorageRoot(top, path.substring(start, i), Kind.REMOVABLE)
                }
            }
            return null
        }
    }
}

/**
 * Drives whose removal one screen already told, so the screen shown next does not
 * tell it again: the reader closing a book on a pulled drive, then the list it
 * returns to. Times are milliseconds on a monotonic clock.
 */
object RemovalNotices {
    /** How long a told removal keeps the next screen quiet. */
    const val QUIET_MS = 30_000L

    private val told = HashMap<String, Long>()

    private fun clock(): Long = System.nanoTime() / 1_000_000

    /** Records that [root]'s removal was told at [now]. */
    @Synchronized
    fun told(root: StorageRoot, now: Long = clock()) {
        told[root.path] = now
    }

    /** True, once, when [root]'s removal was told less than [QUIET_MS] before [now]. */
    @Synchronized
    fun alreadyTold(root: StorageRoot, now: Long = clock()): Boolean {
        val at = told.remove(root.path) ?: return false
        return now - at in 0 until QUIET_MS
    }
}

/** One tappable step of a location: [label] shown, [path] opened. */
data class Crumb(val label: String, val path: String, val isRoot: Boolean)

object Crumbs {
    /**
     * The crumbs leading to [path]: its [StorageRoot.bound] by name, then each folder
     * below it. A system folder above every bound is a crumb of its own.
     */
    fun of(path: String, roots: List<StorageRoot>): List<Crumb> {
        val root = StorageRoot.bound(path, roots)
            ?: return listOf(Crumb(Paths.parts(path).lastOrNull() ?: "/", path, isRoot = true))
        val out = ArrayList<Crumb>()
        out.add(Crumb(root.name, root.path, isRoot = true))
        val start = root.path.length
        // Each crumb's path is the given path cut after that folder, separators untouched.
        var i = start
        while (i < path.length) {
            while (i < path.length && isSeparator(path[i])) i++
            if (i >= path.length) break
            var end = i
            while (end < path.length && !isSeparator(path[end])) end++
            out.add(Crumb(path.substring(i, end), path.substring(0, end), isRoot = false))
            i = end
        }
        return out
    }
}

/** Either path separator: Android paths use `/`, tests on a desktop JVM may see `\`. */
internal fun isSeparator(c: Char) = c == '/' || c == '\\'

internal object Paths {
    /** The non-empty names in [p], split at either separator. */
    fun parts(p: String): List<String> = p.split('/', '\\').filter { it.isNotEmpty() }
}
