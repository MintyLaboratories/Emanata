package com.infinity.comicreader.net

import android.content.Context
import android.net.ConnectivityManager
import android.os.Looper
import com.infinity.comicreader.util.Timing
import kotlinx.coroutines.asContextElement
import java.io.File
import java.io.InputStream
import kotlin.coroutines.CoroutineContext

/**
 * Network shares as the app's paths see them: share `id` is the folder `/net/id`,
 * with the share's files below it. Paths there make [RemoteFile]s ([file]); this
 * keeps one [RemoteFs] per share, remembers what listings found ([cachedStat]), and
 * fails fast, without touching the network, while offline, for a while after a share
 * could not be reached, and after a share turned the sign-in down until it is edited
 * ([changed]) or the user retries ([retry]).
 */
object Remotes {
    /** The folder every share's root sits in. */
    const val TOP = "/net"
    private const val PREFIX = "$TOP/"

    /** How long a share that could not be reached is not tried again unasked. */
    const val QUIET_MS = 10_000L

    /** How long a share's sleeping disk may take to spin up before its first answer. */
    const val SPIN_UP_MS = 20_000L

    /** [answerMs] unless the settings say otherwise: outlasts a [SPIN_UP_MS] with room to spare. */
    const val DEFAULT_ANSWER_MS = 30_000L

    /**
     * How long a request to a share waits for an answer, or for more of one, before the
     * share counts as gone: connecting, signing in, listing, stating and reading alike.
     * Set from the settings; a connection made with another value is made anew.
     */
    @Volatile
    var answerMs: Long = DEFAULT_ANSWER_MS

    private const val MAX_STATS = 4096

    /** The shares to serve; [init] reads them from the [ShareStore]. */
    @Volatile
    private var source: () -> List<Share> = { emptyList() }

    /** Makes the file system of a share; tests stand in fakes. */
    @Volatile
    var connector: (Share) -> RemoteFs = { if (it.kind == ShareKind.WEBDAV) DavFs(it) else SmbFs(it) }

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
    private val refused = HashMap<String, RemoteException>()

    /**
     * One party asking shares, such as the reader, its requests timed apart from everyone
     * else's ([silentFor]): its reads, listings and stats waiting for an answer, and since
     * when they had none.
     */
    class Asker {
        private var count = 0
        private var since = 0L

        @Synchronized
        internal fun start(now: Long) {
            if (count++ == 0) since = now
        }

        @Synchronized
        internal fun end(now: Long) {
            since = now
            count--
        }

        @Synchronized
        internal fun silentAt(now: Long): Long = if (count > 0) (now - since).coerceAtLeast(0) else 0
    }

    /** Whose requests the current thread makes ([askingAs]). */
    private val asker = ThreadLocal<Asker?>()

    private val stats = object : LinkedHashMap<String, RemoteStat>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RemoteStat>) = size > MAX_STATS
    }

    /** Folder listings as last found ([cachedList]); on disk once [init] names where. */
    @Volatile
    private var listings = Listings(null)

    /** Serves the shares stored for [context]'s app, checking the network through its connectivity service. */
    fun init(context: Context) {
        val app = context.applicationContext
        val store = ShareStore(app)
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        source = { store.all() }
        online = { connectivity == null || runCatching { connectivity.activeNetwork != null }.getOrDefault(true) }
        onMainThread = { runCatching { Looper.getMainLooper().thread === Thread.currentThread() }.getOrDefault(false) }
        listings = Listings(Listings.dirIn(app.cacheDir))
        changed(null)
    }

    /** Serves [list] through [fs], online, as if from a background thread, listings kept in [listingsDir]; for tests. */
    fun serve(list: List<Share>, listingsDir: File? = null, fs: (Share) -> RemoteFs) = serve({ list }, listingsDir, fs)

    /** Serves the shares [list] gives at each [changed]; for tests. */
    fun serve(list: () -> List<Share>, listingsDir: File? = null, fs: (Share) -> RemoteFs) {
        source = list
        listings = Listings(listingsDir)
        connector = fs
        online = { true }
        onMainThread = { false }
        clock = { System.nanoTime() / 1_000_000 }
        answerMs = DEFAULT_ANSWER_MS
        changed(null)
    }

    fun rootOf(id: String): String = PREFIX + id

    /** True for a path on a share. */
    fun isRemote(path: String): Boolean = path.startsWith(PREFIX) && path.length > PREFIX.length

    /** The id of the share [path] is on; null off shares. */
    fun idOf(path: String): String? = if (isRemote(path)) path.substring(PREFIX.length).substringBefore('/') else null

    /** [path] below its share's root, `/`-separated; empty for the root. */
    fun relOf(path: String): String = path.substring(PREFIX.length).substringAfter('/', "").trim('/')

    /**
     * A [File] for [path]: a [RemoteFile] on a share, else a plain one. Without
     * [askNetwork], a share's file states what is remembered or guessed, never asking.
     */
    fun file(path: String, askNetwork: Boolean = true): File =
        if (isRemote(path)) RemoteFile(path.trimEnd('/'), askNetwork = askNetwork) else File(path)

    @Synchronized
    fun shares(): List<Share> = shares ?: source().also { shares = it }

    fun share(id: String): Share? = shares().firstOrNull { it.id == id }

    /** False for a path on a share set not to load thumbnails ([Share.thumbnails]). */
    fun thumbnails(path: String): Boolean = idOf(path)?.let { share(it)?.thumbnails } ?: true

    /** True for a path on a share that was removed from the app: nothing is there any more. */
    fun isOrphan(path: String): Boolean = idOf(path)?.let { share(it) == null } == true

    /**
     * Forgets what was held for share [id] (every share when null): its connection,
     * findings, failures and listings. A share changed in nothing that reaching it takes
     * (its address, user and password), or not at all, keeps all but its failures.
     */
    fun changed(id: String?) {
        val closing = ArrayList<RemoteFs>()
        var forget = false
        synchronized(this) {
            val before = shares?.firstOrNull { it.id == id }
            shares = null
            if (id != null && before != null && sameWay(before, share(id))) {
                retry(id)
                return
            }
            forget = id != null
            if (id == null) {
                closing += open.values
                open.clear()
                failedAt.clear()
                refused.clear()
                stats.clear()
            } else {
                open.remove(id)?.let { closing += it }
                failedAt.remove(id)
                refused.remove(id)
                val root = rootOf(id)
                stats.keys.removeAll { it == root || it.startsWith("$root/") }
            }
        }
        for (fs in closing) runCatching { fs.close() }
        if (forget && id != null) listings.forget(id)
    }

    /** True when [b] is reached as [a] is: the same address, user and password. */
    private fun sameWay(a: Share, b: Share?): Boolean =
        b != null && a.path == b.path && a.user == b.user && a.password == b.password && a.passwordLost == b.passwordLost

    /** True while share [id] counts as unreachable: offline, or it failed less than [QUIET_MS] ago. */
    @Synchronized
    fun unreachable(id: String): Boolean {
        if (!online()) return true
        val at = failedAt[id] ?: return false
        return clock() - at < QUIET_MS
    }

    /** Lets the next call to share [id] try the network again at once, signing in again after a refusal. */
    @Synchronized
    fun retry(id: String) {
        failedAt.remove(id)
        refused.remove(id)
        open[id]?.retry()
    }

    @Synchronized
    fun cachedStat(path: String): RemoteStat? = if (isRoot(path)) ROOT else stats[path]

    /** Drops the stat kept for [path], so the next [stat] asks the share. */
    fun forgetStat(path: String) = remember(path, null)

    @Synchronized
    private fun remember(path: String, stat: RemoteStat?) {
        if (stat == null) stats.remove(path) else stats[path] = stat
    }

    /** Remembers [stat] for [path] only when none is: a kept one never replaces a newer one. */
    @Synchronized
    private fun rememberIfUnknown(path: String, stat: RemoteStat) {
        if (path !in stats) stats[path] = stat
    }

    private fun isRoot(path: String) = isRemote(path) && relOf(path).isEmpty()

    /**
     * The entries of the folder at [path], their stats remembered; names no file can
     * have (empty, `.`, `..`, or holding `/`, `\` or NUL) are left out. Throws when
     * the share fails.
     */
    fun list(path: String): List<RemoteFile> {
        val base = path.trimEnd('/')
        val found = Timing.span("share list") { call(path) { fs -> asked { fs.list(relOf(path)) } } }.filter { isName(it.name) }
        listings.put(base, found)
        return filesOf(base, found, fresh = true)
    }

    /**
     * The entries of the folder at [path] as last listed, in memory or on disk, with the
     * stats they had then (remembered only where none is); null when it never was. Never
     * asks the network.
     */
    fun cachedList(path: String): List<RemoteFile>? {
        val base = path.trimEnd('/')
        val id = idOf(base) ?: return null
        if (share(id) == null) return null
        return listings.get(base)?.let { filesOf(base, it, fresh = false) }
    }

    /** True when the folder at [path] was listed before ([cachedList]). Never asks the network. */
    fun isListed(path: String): Boolean {
        val base = path.trimEnd('/')
        return idOf(base)?.let(::share) != null && listings.get(base) != null
    }

    private fun filesOf(base: String, found: List<RemoteStat>, fresh: Boolean): List<RemoteFile> = found.map { s ->
        val child = "$base/${s.name}"
        if (fresh) remember(child, s) else rememberIfUnknown(child, s)
        RemoteFile(child, s)
    }

    private fun isName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }

    /** What is at [path] now; null when nothing is. Throws when the share fails. */
    fun stat(path: String): RemoteStat? {
        if (isRoot(path)) return ROOT
        return Timing.span("share stat") { call(path) { fs -> asked { fs.stat(relOf(path)) } } }.also { remember(path, it) }
    }

    /** Opens the file at [path]; opened for an [Asker] ([askingAs]), its reads are that asker's from any thread. */
    fun open(path: String): RemoteInput = Timing.span("share open") {
        call(path) { fs ->
            val by = asker.get()
            val input = asked(by) { fs.open(relOf(path)) }
            if (by == null) input else Asked(by, input)
        }
    }

    /** [input], its reads timed for [by] ([silentFor]). */
    private class Asked(private val by: Asker, private val input: RemoteInput) : RemoteInput by input {
        override fun read(position: Long, buffer: ByteArray, offset: Int, count: Int): Int =
            asked(by) { input.read(position, buffer, offset, count) }
    }

    /** Runs [block], a request to a share, counting it as [by]'s waiting for an answer until it returns or fails. */
    private inline fun <T> asked(by: Asker? = asker.get(), block: () -> T): T {
        by ?: return block()
        by.start(clock())
        try {
            return block()
        } finally {
            by.end(clock())
        }
    }

    /** Makes the coroutine it is added to ask shares as [by]: its requests, and those of the files it opens. */
    fun askingAs(by: Asker): CoroutineContext.Element = asker.asContextElement(by)

    /** Runs [block] asking shares as [by] ([askingAs]); for tests, which block. */
    internal fun <T> askingAs(by: Asker, block: () -> T): T {
        val before = asker.get()
        asker.set(by)
        try {
            return block()
        } finally {
            asker.set(before)
        }
    }

    /**
     * How long, in [clock] milliseconds, a read, listing or stat [by] made has waited with
     * nothing answered since; 0 while it asks nothing. Requests of others, to the same
     * share too, never count.
     */
    fun silentFor(by: Asker): Long = by.silentAt(clock())

    /** False when the file at [path] can be read only front to back ([RemoteFs.seekable]). */
    fun seekable(path: String): Boolean = call(path) { it.seekable(relOf(path)) }

    /** Opens the file at [path] as a stream from its start. */
    fun inputStream(path: String): InputStream = RemoteInputStream(open(path))

    /** The whole file at [path]; for small files such as loose images. */
    fun readBytes(path: String): ByteArray = open(path).use { input ->
        require(input.length <= Int.MAX_VALUE - 8) { "Too large: $path" }
        val bytes = ByteArray(input.length.toInt())
        var n = 0
        while (n < bytes.size) {
            val r = input.read(n.toLong(), bytes, n, bytes.size - n)
            if (r < 0) break
            n += r
        }
        if (n == bytes.size) bytes else bytes.copyOf(n)
    }

    /** Makes the folder [path]; fails when anything is there. */
    fun mkdir(path: String) {
        try {
            Timing.span("share mkdir") { call(path) { it.mkdir(relOf(path)) } }
        } finally {
            changedAt(path)
        }
    }

    /** Writes [length] bytes from [input] as the new file [path] ([RemoteFs.create]). */
    fun create(path: String, input: InputStream, length: Long) {
        try {
            Timing.span("share create") { call(path) { it.create(relOf(path), input, length) } }
        } finally {
            changedAt(path)
        }
    }

    /** Renames or moves [from] to [to] on the same share; fails when anything is at [to]. */
    fun rename(from: String, to: String) {
        require(idOf(from) != null && idOf(from) == idOf(to)) { "Not on one share: $from, $to" }
        require(!isRoot(from) && !isRoot(to)) { "Not renaming a share" }
        try {
            Timing.span("share rename") { call(from) { it.rename(relOf(from), relOf(to)) } }
        } finally {
            changedAt(from)
            changedAt(to)
        }
    }

    /** Deletes the file or folder at [path], with everything inside; never a share's root. */
    fun delete(path: String) {
        require(!isRoot(path)) { "Not deleting a share" }
        try {
            Timing.span("share delete") { call(path) { it.delete(relOf(path)) } }
        } finally {
            changedAt(path)
        }
    }

    /**
     * Deletes the file or folder at [path], left behind by a failure: tried even while the
     * share counts as unreachable, as the failure may have made it so.
     */
    fun discard(path: String) {
        require(!isRoot(path)) { "Not deleting a share" }
        try {
            Timing.span("share discard") { call(path, eager = true) { it.delete(relOf(path)) } }
        } finally {
            changedAt(path)
        }
    }

    /** Deletes the file, link or empty folder at [path] ([RemoteFs.remove]). */
    fun remove(path: String) {
        require(!isRoot(path)) { "Not deleting a share" }
        try {
            Timing.span("share remove") { call(path) { it.remove(relOf(path)) } }
        } finally {
            changedAt(path)
        }
    }

    /** Copies file [from] to the new file [to] on the share's server itself; false when it cannot ([RemoteFs.copy]). */
    fun copy(from: String, to: String, progress: (Long) -> Unit): Boolean {
        require(idOf(from) != null && idOf(from) == idOf(to)) { "Not on one share: $from, $to" }
        try {
            return Timing.span("share copy") { call(from) { it.copy(relOf(from), relOf(to), progress) } }
        } finally {
            changedAt(to)
        }
    }

    /** Forgets what was found at and below [path], and the kept listings of its folder and of itself. */
    private fun changedAt(path: String) {
        val base = path.trimEnd('/')
        synchronized(this) { stats.keys.removeAll { it == base || it.startsWith("$base/") } }
        listings.drop(base)
        base.substringBeforeLast('/', "").takeIf { isRemote(it) }?.let { listings.drop(it) }
    }

    /** Free and total bytes of share [id]; null when it does not say. Throws when it fails. */
    fun space(id: String): Pair<Long, Long>? = Timing.span("share space") { call(rootOf(id)) { it.space() } }

    /** Runs [block] on [path]'s share; when [eager], even while it counts as unreachable, its own memory of failing too. */
    private fun <T> call(path: String, eager: Boolean = false, block: (RemoteFs) -> T): T {
        val id = idOf(path) ?: throw RemoteException(RemoteException.Reason.UNKNOWN_SHARE)
        synchronized(this) { refused[id] }?.let { throw RemoteException(it.reason, it.message, it, lasting = true) }
        // A password that could not be opened is never tried: signing in with a wrong one could lock the account.
        if (share(id)?.passwordLost == true) throw RemoteException(RemoteException.Reason.DENIED, "Password to enter again", lasting = true)
        if (!eager && unreachable(id)) throw RemoteException(RemoteException.Reason.UNREACHABLE)
        // Never from the main thread: a caller there gets what is remembered, or a guess.
        if (onMainThread()) throw RemoteException(RemoteException.Reason.UNREACHABLE, "Network on the main thread")
        val fs = fsOf(id)
        if (eager) fs.retry()
        return try {
            block(fs)
        } catch (e: RemoteException) {
            synchronized(this) {
                if (e.lasting) refused[id] = e
                else if (e.reason == RemoteException.Reason.UNREACHABLE) failedAt[id] = clock()
            }
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
