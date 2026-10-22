package com.infinity.comicreader.net

import android.content.Context
import android.net.ConnectivityManager
import android.os.Looper
import java.io.File

/**
 * Network shares as the app's paths see them: share `id` is the folder `/net/id`,
 * with the share's files below it. Paths there make [RemoteFile]s ([file]); this
 * keeps one [RemoteFs] per share, remembers what listings found ([cachedStat]), and
 * fails fast, without touching the network, while offline or for a while after a
 * share could not be reached ([retry] ends that early).
 */
object Remotes {
    /** The folder every share's root sits in. */
    const val TOP = "/net"
    private const val PREFIX = "$TOP/"

    /** How long a share that could not be reached is not tried again unasked. */
    const val QUIET_MS = 10_000L

    private const val MAX_STATS = 4096

    /** The shares to serve; [init] reads them from the [ShareStore]. */
    @Volatile
    private var source: () -> List<Share> = { emptyList() }

    /** Makes the file system of a share; tests stand in fakes. */
    @Volatile
    var connector: (Share) -> RemoteFs = { SmbFs(it) }

    /** True while the device has a network at all. */
    @Volatile
    var online: () -> Boolean = { true }

    /** True on the main thread, where the network is never touched. */
    @Volatile
    var onMainThread: () -> Boolean = { false }

    /** The clock failures are timed by, in milliseconds. */
    @Volatile
    var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    private var shares: List<Share>? = null
    private val open = HashMap<String, RemoteFs>()
    private val failedAt = HashMap<String, Long>()
    private val stats = object : LinkedHashMap<String, RemoteStat>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RemoteStat>) = size > MAX_STATS
    }

    /** Serves the shares stored for [context]'s app, checking the network through its connectivity service. */
    fun init(context: Context) {
        val app = context.applicationContext
        val store = ShareStore(app)
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        source = { store.all() }
        online = { connectivity == null || runCatching { connectivity.activeNetwork != null }.getOrDefault(true) }
        onMainThread = { runCatching { Looper.getMainLooper().thread === Thread.currentThread() }.getOrDefault(false) }
        changed(null)
    }

    /** Serves [list] through [fs]; for tests. */
    fun serve(list: List<Share>, fs: (Share) -> RemoteFs) {
        source = { list }
        connector = fs
        changed(null)
    }

    fun rootOf(id: String): String = PREFIX + id

    /** True for a path on a share. */
    fun isRemote(path: String): Boolean = path.startsWith(PREFIX) && path.length > PREFIX.length

    /** The id of the share [path] is on; null off shares. */
    fun idOf(path: String): String? = if (isRemote(path)) path.substring(PREFIX.length).substringBefore('/') else null

    /** [path] below its share's root, `/`-separated; empty for the root. */
    fun relOf(path: String): String = path.substring(PREFIX.length).substringAfter('/', "").trim('/')

    /** A [File] for [path]: a [RemoteFile] on a share, else a plain one. */
    fun file(path: String): File = if (isRemote(path)) RemoteFile(path.trimEnd('/')) else File(path)

    @Synchronized
    fun shares(): List<Share> = shares ?: source().also { shares = it }

    fun share(id: String): Share? = shares().firstOrNull { it.id == id }

    /** Forgets what was held for share [id] (every share when null): its connection, findings and failures. */
    fun changed(id: String?) {
        val closing = ArrayList<RemoteFs>()
        synchronized(this) {
            shares = null
            if (id == null) {
                closing += open.values
                open.clear()
                failedAt.clear()
                stats.clear()
            } else {
                open.remove(id)?.let { closing += it }
                failedAt.remove(id)
                val root = rootOf(id)
                stats.keys.removeAll { it == root || it.startsWith("$root/") }
            }
        }
        for (fs in closing) runCatching { fs.close() }
    }

    /** True while share [id] counts as unreachable: offline, or it failed less than [QUIET_MS] ago. */
    @Synchronized
    fun unreachable(id: String): Boolean {
        if (!online()) return true
        val at = failedAt[id] ?: return false
        return clock() - at < QUIET_MS
    }

    /** Lets the next call to share [id] try the network again at once. */
    @Synchronized
    fun retry(id: String) {
        failedAt.remove(id)
    }

    @Synchronized
    fun cachedStat(path: String): RemoteStat? = if (isRoot(path)) ROOT else stats[path]

    @Synchronized
    private fun remember(path: String, stat: RemoteStat?) {
        if (stat == null) stats.remove(path) else stats[path] = stat
    }

    private fun isRoot(path: String) = isRemote(path) && relOf(path).isEmpty()

    /** The entries of the folder at [path], their stats remembered. Throws when the share fails. */
    fun list(path: String): List<RemoteFile> {
        val found = call(path) { it.list(relOf(path)) }
        val base = path.trimEnd('/')
        return found.map { s ->
            val child = "$base/${s.name}"
            remember(child, s)
            RemoteFile(child, s)
        }
    }

    /** What is at [path] now; null when nothing is. Throws when the share fails. */
    fun stat(path: String): RemoteStat? {
        if (isRoot(path)) return ROOT
        return call(path) { it.stat(relOf(path)) }.also { remember(path, it) }
    }

    /** Opens the file at [path]. */
    fun open(path: String): RemoteInput = call(path) { it.open(relOf(path)) }

    /** Free and total bytes of share [id]; null when it does not say. Throws when it fails. */
    fun space(id: String): Pair<Long, Long>? = call(rootOf(id)) { it.space() }

    private fun <T> call(path: String, block: (RemoteFs) -> T): T {
        val id = idOf(path) ?: throw RemoteException(RemoteException.Reason.UNKNOWN_SHARE)
        if (unreachable(id)) throw RemoteException(RemoteException.Reason.UNREACHABLE)
        // Never from the main thread: a caller there gets what is remembered, or a guess.
        if (onMainThread()) throw RemoteException(RemoteException.Reason.UNREACHABLE, "Network on the main thread")
        val fs = fsOf(id)
        return try {
            block(fs)
        } catch (e: RemoteException) {
            if (e.reason == RemoteException.Reason.UNREACHABLE) synchronized(this) { failedAt[id] = clock() }
            throw e
        }
    }

    @Synchronized
    private fun fsOf(id: String): RemoteFs {
        open[id]?.let { return it }
        val share = share(id) ?: throw RemoteException(RemoteException.Reason.UNKNOWN_SHARE)
        return connector(share).also { open[id] = it }
    }

    /** A share's root: always a folder, known without asking. */
    private val ROOT = RemoteStat("", isDirectory = true, size = 0, modified = 0, created = 0)
}
