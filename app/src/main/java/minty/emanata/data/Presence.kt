package minty.emanata.data

import minty.emanata.net.Remotes
import java.io.File
import java.io.IOException

/** Whether a book or folder Favorites, History or Bookmarks list is where it was last seen. */
enum class Presence {
    PRESENT,

    /** Not there: gone from the device, from a share, or on a share removed from the app. */
    MISSING,

    /** Where it was cannot be reached now: a share offline or failing, a volume not mounted. */
    UNREACHABLE,

    /** Not known yet: on a share, before it is asked. */
    UNKNOWN
}

/**
 * Finds the [Presence] of items by path. [quick] never uses the network: a device item
 * by whether it exists, one on a volume not mounted as unreachable, one on a share removed
 * from the app as missing at once, one on a share failing now as unreachable, else from
 * what was last found there (a check made since nothing changed, its folder listed from
 * the share within [MEMO_MS], the stat kept, the listing of its folder kept). What
 * [quick] cannot settle, [stat] asks the share for, off the main thread; its answer
 * stands until the app changes a share, its folder is listed again or [MEMO_MS] pass.
 */
class PresenceCheck(private val world: World, private val memo: Memo = Memo.shared) {

    /** What the check looks at; [live] for the device and the shares. */
    interface World {
        /** The share [path] is on; null on the device. */
        fun shareOf(path: String): String?

        /** True for share [id] still set up in the app. */
        fun shareKnown(id: String): Boolean

        /** True while share [id] counts as unreachable, without asking it. */
        fun shareUnreachable(id: String): Boolean

        /** True when a stat of [path] is kept. */
        fun statKept(path: String): Boolean

        /** The names in folder [path] as last listed; null when it never was. */
        fun namesKept(path: String): Set<String>?

        /** When folder [path] was last listed from its share, by [now]; null when it was not in this run. */
        fun listedAt(path: String): Long?

        /** True when something is at [path] on its share, asking it; throws when the share fails. */
        fun stat(path: String): Boolean

        /** True when something is at device [path]. */
        fun exists(path: String): Boolean

        /** True when the volume whose top folder is [root] is mounted. */
        fun mounted(root: String): Boolean

        /** [Remotes.changes]: what was found before it moved may be out of date. */
        fun changes(): Long

        /** Milliseconds, for how old an answer is. */
        fun now(): Long
    }

    /** [presence] found, [settled] when asking again now would tell nothing more. */
    data class Found(val presence: Presence, val settled: Boolean) {
        /** Missing for sure: what "Clear missing" takes. */
        val missing: Boolean get() = presence == Presence.MISSING && settled
    }

    /** Answers from shares kept for the app's run, by path. */
    class Memo {
        /** What the share told for a path, when the app's changes to shares were [changes], at [at]. */
        class Answer(val found: Found, val changes: Long, val at: Long)

        private val answers = HashMap<String, Answer>()

        @Synchronized
        fun get(path: String): Answer? = answers[path]

        @Synchronized
        fun put(path: String, answer: Answer) {
            if (answers.size >= MAX_MEMO) answers.clear()
            answers[path] = answer
        }

        @Synchronized
        fun clear() = answers.clear()

        companion object {
            val shared = Memo()
        }
    }

    /** What [quick] looked up for one list, kept for its next items: folder listings and whether volumes are mounted. */
    class Seen {
        internal val names = HashMap<String, Set<String>?>()
        internal val mounted = HashMap<String, Boolean>()
    }

    /** The presence of [path] from what is known now; never asks a share. What it looks up goes in [seen], when given. */
    fun quick(path: String, seen: Seen? = null): Found {
        val id = world.shareOf(path) ?: return device(path, seen)
        if (!world.shareKnown(id)) return Found(Presence.MISSING, settled = true)
        val folder = path.substringBeforeLast('/')
        memo.get(path)?.let { a ->
            val listedSince = world.listedAt(folder)?.let { it >= a.at } == true
            if (a.changes == world.changes() && world.now() - a.at in 0 until MEMO_MS && !listedSince) return a.found
        }
        if (world.shareUnreachable(id)) return Found(Presence.UNREACHABLE, settled = true)
        val name = path.substringAfterLast('/')
        fun names() = if (seen != null) seen.names.getOrPut(folder) { world.namesKept(folder) } else world.namesKept(folder)
        // Its folder just listed from the share tells as much as asking it would.
        val listed = world.listedAt(folder)
        if (listed != null && world.now() - listed in 0 until MEMO_MS) {
            names()?.let { return Found(if (name in it) Presence.PRESENT else Presence.MISSING, settled = true) }
        }
        if (world.statKept(path)) return Found(Presence.PRESENT, settled = false)
        val names = names() ?: return Found(Presence.UNKNOWN, settled = false)
        return Found(if (name in names) Presence.PRESENT else Presence.MISSING, settled = false)
    }

    /** True when [found] for [path] is to be asked of its share ([stat]). */
    fun asks(path: String, found: Found): Boolean = !found.settled && world.shareOf(path) != null

    /** The presence of [path], on a share, asked of the share; unreachable when it fails. Blocking. */
    fun stat(path: String): Found {
        val changes = world.changes()
        val found = try {
            Found(if (world.stat(path)) Presence.PRESENT else Presence.MISSING, settled = true)
        } catch (e: IOException) {
            return Found(Presence.UNREACHABLE, settled = true)
        }
        memo.put(path, Memo.Answer(found, changes, world.now()))
        return found
    }

    private fun device(path: String, seen: Seen?): Found {
        val volume = BookLocation.volumeRoot(path)
        if (volume != null) {
            val mounted = if (seen != null) seen.mounted.getOrPut(volume) { world.mounted(volume) } else world.mounted(volume)
            if (!mounted) return Found(Presence.UNREACHABLE, settled = true)
        }
        return Found(if (world.exists(path)) Presence.PRESENT else Presence.MISSING, settled = true)
    }

    companion object {
        /** How long an answer from a share, or a listing of a folder there, stands while the app changes nothing there. */
        const val MEMO_MS = 10 * 60_000L

        /** Answers kept at most; past it they are all dropped. */
        private const val MAX_MEMO = 4096

        /** Items asked of shares at once by one listing. */
        const val LANES = 4

        /** The device and the shares as they are. */
        fun live(): PresenceCheck = PresenceCheck(LiveWorld)
    }

    private object LiveWorld : World {
        override fun shareOf(path: String): String? = Remotes.idOf(path)

        override fun shareKnown(id: String): Boolean = Remotes.share(id) != null

        override fun shareUnreachable(id: String): Boolean = Remotes.unreachable(id)

        override fun statKept(path: String): Boolean = Remotes.cachedStat(path) != null

        override fun namesKept(path: String): Set<String>? = Remotes.cachedNames(path)

        override fun listedAt(path: String): Long? = Remotes.listedAt(path)

        override fun stat(path: String): Boolean = Remotes.stat(path) != null

        override fun exists(path: String): Boolean = File(path).exists()

        override fun mounted(root: String): Boolean = File(root).list() != null

        override fun changes(): Long = Remotes.changes

        override fun now(): Long = Remotes.clock()
    }
}
